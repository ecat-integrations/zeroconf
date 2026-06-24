/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ecat.integration.zeroconf;

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigFlowException;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigFlowService;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Integration.IntegrationLoadOption;
import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zeroconf discovery 集成（peer 模型 cohesive owner / broker）。
 * <p>拥有 zeroconf discovery 的全部机制（payload / subscription / registry / matcher / jmdns 监听 + 生产），
 * core 一无所知——本集成命中匹配后调 core 通用入口 {@code startDiscoveryFlow} 跑目标设备集成的 flow。
 *
 * <p><b>broker 职责</b>：jmdns 监听服务类型 → 解析 ServiceInfo（TXT/地址/端口）→ 构造
 * {@link ZeroconfDiscoveryPayload} → {@link ZeroconfMatcher#resolveCoordinates} 查本集成订阅表
 * → 逐个调 {@code core.getConfigFlowService().startDiscoveryFlow(coord, ZEROCONF, payload)}。
 * 未就绪/重复抛异常时由本集成记录日志（mDNS 会周期性重解，故不暂存）。
 *
 * <p><b>设备集成订阅</b>：设备集成 onLoad 调 {@link #subscribe} 声明触发条件（进 registry），
 * 本集成确保为该服务类型注册 jmdns listener（支持订阅早于/晚于 onStart 两种时序）。
 *
 * <p>本类为协议集成（broker），**无自己的 ConfigFlow**——它触发其他设备集成的 flow。
 *
 * @author coffee
 */
public class ZeroconfDiscoveryIntegration extends IntegrationBase implements ServiceListener {

    public static final String COORDINATE = "com.ecat:integration-zeroconf";

    private final Log log = LogFactory.getLogger(getClass());
    private final ZeroconfSubscriptionRegistry registry = new ZeroconfSubscriptionRegistry();
    private final ZeroconfMatcher matcher = new ZeroconfMatcher();

    private JmDNS jmdns;
    private final Set<String> listenedTypes = new HashSet<String>(); // 已注册 listener 的服务类型
    private volatile boolean started = false;

    public ZeroconfDiscoveryIntegration() {
        super();
    }

    // ==================== 生命周期 ====================

    @Override
    public void onLoad(EcatCore core, IntegrationLoadOption loadOption) {
        super.onLoad(core, loadOption); // 注入 core/log/registries
        log.info("[integration-zeroconf] onLoad");
    }

    @Override
    public void onInit() {
        // 无设备/属性，无需初始化
    }

    @Override
    public void onStart() {
        log.info("[integration-zeroconf] onStart: 启动 jmdns 监听");
        try {
            jmdns = JmDNS.create();
            started = true;
            // 为 registry 中已有的所有服务类型补注册 listener（处理 subscribe 早于 onStart 的情况）
            synchronized (listenedTypes) {
                for (ZeroconfSubscription sub : collectAllSubscriptions()) {
                    ensureListenerLocked(sub.getType());
                }
            }
            log.info("[integration-zeroconf] jmdns 已就绪，监听类型: {}", listenedTypes);
        } catch (Exception e) {
            // 严格模式：jmdns 启动失败不静默——记录错误（不抛以避免拖垮整个 core 启动）
            log.error("[integration-zeroconf] jmdns 启动失败，zeroconf discovery 不可用", e);
        }
    }

    @Override
    public void onPause() {
        closeJmdns();
    }

    @Override
    public void onRelease() {
        closeJmdns();
        super.onRelease(); // 清日志上下文
    }

    private void closeJmdns() {
        started = false;
        JmDNS j = jmdns;
        if (j != null) {
            try {
                j.close();
            } catch (Exception e) {
                log.warn("[integration-zeroconf] jmdns close 异常", e);
            }
            jmdns = null;
        }
        synchronized (listenedTypes) {
            listenedTypes.clear();
        }
    }

    // ==================== 设备集成订阅入口（选项 A 代码依赖）====================

    /**
     * 设备集成 onLoad 声明订阅（触发条件）。
     * <p>累加到 registry，并为新出现的服务类型注册 jmdns listener。
     */
    public void subscribe(String coordinate, ZeroconfSubscription... subscriptions) {
        registry.register(coordinate, subscriptions);
        if (subscriptions != null) {
            synchronized (listenedTypes) {
                for (ZeroconfSubscription sub : subscriptions) {
                    ensureListenerLocked(sub.getType());
                }
            }
        }
        log.info("[integration-zeroconf] subscribe: coordinate={}, subs={}", coordinate, subscriptions == null ? 0 : subscriptions.length);
    }

    /** 供测试/调试取 registry。 */
    public ZeroconfSubscriptionRegistry getRegistry() {
        return registry;
    }

    // ==================== ServiceListener（jmdns 回调）====================

    @Override
    public void serviceAdded(ServiceEvent event) {
        // 主动请求完整解析（地址/TXT），触发 serviceResolved
        JmDNS j = jmdns;
        if (j != null && event.getInfo() != null) {
            j.requestServiceInfo(event.getType(), event.getName(), true);
        }
    }

    @Override
    public void serviceRemoved(ServiceEvent event) {
        // 发现场景不处理移除（设备离线由设备状态机负责）
    }

    @Override
    public void serviceResolved(ServiceEvent event) {
        ServiceInfo info = event.getInfo();
        if (info == null) {
            return;
        }
        ZeroconfDiscoveryPayload payload = buildPayload(info);
        if (payload == null) {
            return; // 数据未就绪，等下次解析
        }
        log.info("[integration-zeroconf] serviceResolved: {}", payload);

        List<String> coordinates = matcher.resolveCoordinates(payload, registry);
        if (coordinates.isEmpty()) {
            return; // 无人订阅此服务
        }
        ConfigFlowService service = core != null ? core.getConfigFlowService() : null;
        if (service == null) {
            log.warn("[integration-zeroconf] ConfigFlowService 未就绪，跳过（mDNS 周期重解会重试）: {}", payload);
            return;
        }
        for (String coordinate : coordinates) {
            try {
                ConfigFlowService.ConfigFlowInstance inst = service.startDiscoveryFlow(coordinate, SourceType.ZEROCONF, payload);
                if (inst.getResult() != null && inst.getResult().getType() == ConfigFlowResult.ResultType.ABORT) {
                    // R5 去重命中（已添加设备重复发现 = 正常路径）→ DEBUG，非 WARN（drive 已转 clean ABORT）
                    log.debug("[integration-zeroconf] discovery 已忽略({}): coordinate={}",
                            inst.getResult().getReason(), coordinate);
                } else {
                    log.info("[integration-zeroconf] 已触发 discovery flow: coordinate={}", coordinate);
                }
            } catch (ConfigFlowException e) {
                // Layer2(R12) 重复 / 未就绪 / 无 handler：mDNS 周期重解会重试；均为非致命 → DEBUG
                log.debug("[integration-zeroconf] startDiscoveryFlow 未生效（可能 R12 重复/未就绪/无 handler）: coordinate={}, reason={}",
                        coordinate, e.getMessage());
            } catch (RuntimeException e) {
                // 防御：async listener 绝不让异常逃逸到 jmdns
                log.warn("[integration-zeroconf] startDiscoveryFlow 运行时异常（忽略，mDNS 会重试）: coordinate={}, reason={}",
                        coordinate, e.getMessage());
            }
        }
    }

    // ==================== 内部 ====================

    /**
     * 从 ServiceInfo 构造 payload；若关键数据未就绪返回 null。
     */
    private ZeroconfDiscoveryPayload buildPayload(ServiceInfo info) {
        if (!info.hasData()) {
            return null;
        }
        String type = info.getType();
        String name = info.getName();
        if (type == null || type.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }
        String[] addrs = info.getHostAddresses();
        List<String> addresses = new java.util.ArrayList<String>();
        if (addrs != null) {
            for (String a : addrs) {
                addresses.add(a);
            }
        }
        Map<String, String> txt = new HashMap<String, String>();
        Enumeration<String> names = info.getPropertyNames();
        while (names != null && names.hasMoreElements()) {
            String key = names.nextElement();
            String val = info.getPropertyString(key);
            txt.put(key, val != null ? val : "");
        }
        return new ZeroconfDiscoveryPayload(type, name, addresses, info.getPort(), txt);
    }

    /**
     * 已在 registry 中的全部订阅（去重 by type 用于补 listener）。
     */
    private List<ZeroconfSubscription> collectAllSubscriptions() {
        List<ZeroconfSubscription> all = new java.util.ArrayList<ZeroconfSubscription>();
        for (Map.Entry<String, List<ZeroconfSubscription>> e : registry.all()) {
            all.addAll(e.getValue());
        }
        return all;
    }

    /**
     * 确保某服务类型已注册 listener（调用方持 listenedTypes 锁）。
     */
    private void ensureListenerLocked(String type) {
        if (type == null || type.trim().isEmpty()) {
            return;
        }
        if (listenedTypes.contains(type)) {
            return;
        }
        JmDNS j = jmdns;
        if (j == null) {
            // onStart 前的 subscribe：仅登记类型，onStart 后补 listener
            listenedTypes.add(type);
            return;
        }
        j.addServiceListener(type, this);
        listenedTypes.add(type);
    }
}
