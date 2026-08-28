/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ecat.integration.zeroconf;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * zeroconf serviceResolved 投递回归：
 * jmdns 库回调线程只构 payload + {@code HostedExecutors.bounded(1, this)} 单飞道 execute
 * O(1) 即返；匹配/discovery flow 业务在道 worker 线程执行；道队列满/宿主已拆卸（REE）
 * 只记账；单道 FIFO 串行契约。全程确定性同步（latch/占道阻塞），禁 sleep。
 *
 * <p>替身形态：被测 {@link ZeroconfDiscoveryIntegration#discoveryLane} 是真实
 * HostedExecutors 池（测试经包内可见字段关停注入拒绝）；「投递即返」断言用「先占住
 * 单飞道线程的阻塞任务」达成确定性（道忙期间新投递必然未执行，非定时猜测）。
 *
 * @author coffee
 */
public class ZeroconfDiscoveryDeliveryTest {

    /**
     * 测试观察子类：记录业务执行的线程与收到的 payload（业务体本身无用户回调可挂）；
     * receivedAll 按用例期望投递数建 latch（latch 同步替代定时轮询）。
     */
    private static final class ObservingIntegration extends ZeroconfDiscoveryIntegration {
        final AtomicReference<Thread> bizThread = new AtomicReference<>();
        final List<ZeroconfDiscoveryPayload> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch bizRan = new CountDownLatch(1);
        final CountDownLatch receivedAll;

        ObservingIntegration(int expectedDeliveries) {
            this.receivedAll = new CountDownLatch(expectedDeliveries);
        }

        @Override
        protected void triggerDiscoveryFlows(ZeroconfDiscoveryPayload payload) {
            bizThread.set(Thread.currentThread());
            received.add(payload);
            bizRan.countDown();
            receivedAll.countDown();
        }
    }

    private ObservingIntegration integration;

    @Before
    public void setUp() {
        integration = new ObservingIntegration(1);
    }

    @After
    public void tearDown() {
        // 池生命周期测试自管（宿主是集成实例，测试结束后统一关停防线程跨用例滞留）
        integration.discoveryLane.shutdownNow();
    }

    private static ZeroconfDiscoveryPayload payload(String name) {
        List<String> addresses = new ArrayList<>();
        Map<String, String> txt = new HashMap<>();
        txt.put("model", "Test-1");
        return new ZeroconfDiscoveryPayload("_ecat-test._tcp.local.", name, addresses, 8080, txt);
    }

    /** 构造携带 ServiceInfo 的 ServiceEvent（jmdns 抽象类，公开构造器可匿名子类化） */
    private static ServiceEvent eventOf(final ServiceInfo info) {
        return new ServiceEvent(new Object()) {
            @Override public javax.jmdns.JmDNS getDNS() { return null; }
            @Override public String getType() { return info.getType(); }
            @Override public String getName() { return info.getName(); }
            @Override public ServiceInfo getInfo() { return info; }
        };
    }

    /** jmdns ServiceInfo.create（无 host）hasData()==false，精确复现「数据未就绪」路径 */
    @Test
    public void unresolvedInfoIsNotSubmitted() {
        Map<String, String> props = new HashMap<>();
        props.put("model", "Test-1");
        ServiceInfo info = ServiceInfo.create("_ecat-test._tcp.local.", "dev1", 8080, 0, 0, props);
        assertFalse(info.hasData());

        integration.serviceResolved(eventOf(info));

        assertTrue(integration.received.isEmpty());
    }

    /** T1：业务在道 worker 线程执行，非库（触发）线程 */
    @Test
    public void businessRunsOnLaneWorkerThreadNotCallerThread() throws Exception {
        integration.submitDiscoveryEvent(payload("dev1"));

        assertTrue(integration.bizRan.await(10, TimeUnit.SECONDS));
        assertNotNull(integration.bizThread.get());
        assertNotEquals("业务应在道 worker 线程执行（非回调调用线程）",
                Thread.currentThread(), integration.bizThread.get());
    }

    /**
     * T2：投递即返——先占住单飞道（阻塞任务持道），此时投递事件，业务必然未执行
     * （确定性：道单线程被占，新任务只能排队）；放行后业务才执行。
     */
    @Test
    public void callbackReturnsBeforeBusinessRuns() throws Exception {
        CountDownLatch laneOccupied = new CountDownLatch(1);
        CountDownLatch releaseLane = new CountDownLatch(1);
        integration.discoveryLane.execute(() -> {
            laneOccupied.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(laneOccupied.await(5, TimeUnit.SECONDS));

        integration.submitDiscoveryEvent(payload("dev1"));
        assertEquals("道忙期间投递的业务不得已执行", 0, integration.received.size());

        releaseLane.countDown();
        assertTrue(integration.bizRan.await(5, TimeUnit.SECONDS));
        assertEquals(1, integration.received.size());
    }

    /** T3：道拒绝（已关停同步抛 REE）只记账不向上抛，业务不执行 */
    @Test
    public void laneRejectionIsAccountedNotThrown() {
        integration.discoveryLane.shutdownNow();

        integration.submitDiscoveryEvent(payload("dev1"));

        assertTrue(integration.received.isEmpty());
    }

    /** T4：串行契约——单飞道 FIFO（两事件按提交序执行） */
    @Test
    public void sameLaneSubmissionsSerializeInFifoOrder() throws Exception {
        integration = new ObservingIntegration(2);
        integration.submitDiscoveryEvent(payload("dev1"));
        integration.submitDiscoveryEvent(payload("dev2"));

        // 单线程道：两业务都完成即序稳定；FIFO 由单飞道队列结构保证
        assertTrue("两事件都应完成（latch 同步，非定时猜测）",
                integration.receivedAll.await(10, TimeUnit.SECONDS));
        assertEquals("dev1", integration.received.get(0).getName());
        assertEquals("dev2", integration.received.get(1).getName());
    }

    /** T5：载荷完整性——道侧业务收到的 payload 与回调侧构造一致（字段逐一比对） */
    @Test
    public void payloadIntactThroughDelivery() throws Exception {
        ZeroconfDiscoveryPayload sent = payload("dev1");
        integration.submitDiscoveryEvent(sent);

        assertTrue(integration.bizRan.await(10, TimeUnit.SECONDS));
        assertEquals(1, integration.received.size());
        assertSame(sent, integration.received.get(0));
        assertEquals("_ecat-test._tcp.local.", integration.received.get(0).getType());
        assertEquals("dev1", integration.received.get(0).getName());
        assertEquals("Test-1", integration.received.get(0).getProperties().get("model"));
    }

    /** 满拒边界：占道 + 64 排队后第 65 发被拒（REE 记账即返，不向上抛）——确定性触发，非定时 */
    @Test
    public void queueFull_rejectionAccountedQueuedSurvive() throws Exception {
        integration = new ObservingIntegration(64);
        CountDownLatch laneOccupied = new CountDownLatch(1);
        CountDownLatch releaseLane = new CountDownLatch(1);
        integration.discoveryLane.execute(() -> {
            laneOccupied.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(laneOccupied.await(5, TimeUnit.SECONDS));

        // 64 发填满排队界（HostedExecutors.QUEUE_CAPACITY）
        for (int i = 0; i < 64; i++) {
            integration.submitDiscoveryEvent(payload("dev" + i));
        }
        // 第 65 发：队列满 → REE → submitDiscoveryEvent 记账即返不抛
        integration.submitDiscoveryEvent(payload("dev-overflow"));

        // 放行：已排队 64 发全执行（拒绝只弃当发，不毒死道内后续）
        releaseLane.countDown();
        assertTrue("64 发排队事件应全执行", integration.receivedAll.await(10, TimeUnit.SECONDS));
        assertEquals("拒绝只弃当发，已排队 64 发须全执行（不含被拒的第 65 发）",
                64, integration.received.size());
    }

    /** 集成装配面：discoveryLane 是非 null 的可用执行器（提交即受理）。 */
    @Test
    public void laneFieldIsUsableExecutor() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        integration.discoveryLane.execute(ran::countDown);
        assertTrue("集成自有道应可直接受理提交", ran.await(5, TimeUnit.SECONDS));
    }
}
