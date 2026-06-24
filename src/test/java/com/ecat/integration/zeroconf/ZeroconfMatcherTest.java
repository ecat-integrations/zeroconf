package com.ecat.integration.zeroconf;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ZeroconfMatcher} 单测——HA-faithful 匹配：type 精确(归一化) ∧ properties 子集 fnmatch ∧ name fnmatch；
 * resolveCoordinates 命中收集 + 同集成多订阅命中只返回一次。
 */
public class ZeroconfMatcherTest {

    private ZeroconfMatcher matcher = new ZeroconfMatcher();

    private ZeroconfDiscoveryPayload payload(String type, String name, Map<String, String> txt) {
        return new ZeroconfDiscoveryPayload(type, name, Collections.singletonList("192.168.1.10"), 8080, txt);
    }

    private Map<String, String> txt(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void testMatches_TypeExact() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder().type("_ecat-test._tcp.local.").build();
        assertTrue(matcher.matches(sub, payload("_ecat-test._tcp.local.", "n1", txt())));
        assertFalse("type 不同不匹配", matcher.matches(sub, payload("_other._tcp.local.", "n1", txt())));
    }

    @Test
    public void testMatches_TypeNormalize_TrailingDotAndCase() {
        // 订阅不带尾点，payload 带尾点 → 归一化后应匹配；大小写不敏感
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder().type("_ECAT-TEST._tcp.local").build();
        assertTrue(matcher.matches(sub, payload("_ecat-test._tcp.local.", "n1", txt())));
    }

    @Test
    public void testMatches_PropertiesSubsetFnmatch() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder()
                .type("_t._tcp.local.")
                .property("model", "SMS*")
                .property("vendor", "ECAT")
                .build();
        // 全部声明键存在 + 值匹配 glob
        assertTrue(matcher.matches(sub, payload("_t._tcp.local.", "n", txt("model", "SMS8200", "vendor", "ECAT", "sn", "X"))));
        // 缺一个声明键
        assertFalse(matcher.matches(sub, payload("_t._tcp.local.", "n", txt("model", "SMS8200"))));
        // 值不匹配 glob
        assertFalse(matcher.matches(sub, payload("_t._tcp.local.", "n", txt("model", "ABC", "vendor", "ECAT"))));
    }

    @Test
    public void testMatches_NameGlob() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder().type("_t._tcp.local.").name("FooDevice-*").build();
        assertTrue(matcher.matches(sub, payload("_t._tcp.local.", "FooDevice-01", txt())));
        assertFalse(matcher.matches(sub, payload("_t._tcp.local.", "BarDevice-01", txt())));
    }

    @Test
    public void testMatches_NameNull_SkipsNameCheck() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder().type("_t._tcp.local.").build(); // name 未设
        assertTrue(matcher.matches(sub, payload("_t._tcp.local.", "anything", txt())));
    }

    @Test
    public void testResolveCoordinates_Hits() {
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register("com.ecat:a",
                new ZeroconfSubscription.Builder().type("_t._tcp.local.").property("model", "SMS*").build());
        r.register("com.ecat:b",
                new ZeroconfSubscription.Builder().type("_t._tcp.local.").property("model", "OTHER*").build());
        r.register("com.ecat:c",
                new ZeroconfSubscription.Builder().type("_other._tcp.local.").build()); // type 不同，不命中

        ZeroconfDiscoveryPayload p = payload("_t._tcp.local.", "n", txt("model", "SMS8200"));
        List<String> hit = matcher.resolveCoordinates(p, r);
        assertEquals("只 a 命中", Arrays.asList("com.ecat:a"), hit);
    }

    @Test
    public void testResolveCoordinates_MultiSubscriptionSameCoordinate_Dedup() {
        // 同一集成的两条订阅都命中 → coordinate 只返回一次
        ZeroconfSubscriptionRegistry r = new ZeroconfSubscriptionRegistry();
        r.register("com.ecat:a",
                new ZeroconfSubscription.Builder().type("_t._tcp.local.").property("model", "SMS-*").build(),
                new ZeroconfSubscription.Builder().type("_t._tcp.local.").name("n1").build());
        ZeroconfDiscoveryPayload p = payload("_t._tcp.local.", "n1", txt("model", "SMS8200"));
        List<String> hit = matcher.resolveCoordinates(p, r);
        assertEquals(Arrays.asList("com.ecat:a"), hit);
    }

    @Test
    public void testResolveCoordinates_EmptyRegistry() {
        List<String> hit = matcher.resolveCoordinates(
                payload("_t._tcp.local.", "n", txt()), new ZeroconfSubscriptionRegistry());
        assertTrue(hit.isEmpty());
    }

    @Test
    public void testMatches_NullSafe() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder().type("_t._tcp.local.").build();
        assertFalse(matcher.matches(null, payload("_t._tcp.local.", "n", txt())));
        assertFalse(matcher.matches(sub, null));
    }
}
