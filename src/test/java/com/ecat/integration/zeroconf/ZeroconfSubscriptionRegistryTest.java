package com.ecat.integration.zeroconf;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ZeroconfSubscriptionRegistry} 单测——register 累加、get/coordinates/all 快照。
 */
public class ZeroconfSubscriptionRegistryTest {

    private ZeroconfSubscription sub(String type) {
        return new ZeroconfSubscription.Builder().type(type).build();
    }

    @Test
    public void testRegisterAndGet() {
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register("com.ecat:a", sub("_a._tcp.local."), sub("_a2._tcp.local."));
        r.register("com.ecat:b", sub("_b._tcp.local."));

        assertEquals(2, r.get("com.ecat:a").size());
        assertEquals(1, r.get("com.ecat:b").size());
        assertTrue("未登记的 coordinate 应返回空列表", r.get("com.ecat:none").isEmpty());
    }

    @Test
    public void testRegister_AccumulateSameCoordinate() {
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register("com.ecat:a", sub("_a._tcp.local."));
        r.register("com.ecat:a", sub("_a2._tcp.local."), sub("_a3._tcp.local."));
        assertEquals("同 coordinate 多次 register 应累加", 3, r.get("com.ecat:a").size());
    }

    @Test
    public void testCoordinatesAndAll() {
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register("com.ecat:a", sub("_a._tcp.local."));
        r.register("com.ecat:b", sub("_b._tcp.local."));

        Set<String> coords = r.getCoordinates();
        assertEquals(2, coords.size());
        assertTrue(coords.contains("com.ecat:a"));
        assertTrue(coords.contains("com.ecat:b"));

        int total = 0;
        for (Map.Entry<String, List<ZeroconfSubscription>> e : r.all()) {
            total += e.getValue().size();
        }
        assertEquals(2, total);
    }

    @Test
    public void testRegister_NullArgs_NoOp() {
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register(null, sub("_a._tcp.local."));    // coordinate null
        r.register("com.ecat:a", (ZeroconfSubscription[]) null); // subs null
        r.register("com.ecat:a");                   // 无 subs
        assertTrue(r.getCoordinates().isEmpty());
    }
}
