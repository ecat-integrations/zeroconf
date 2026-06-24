package com.ecat.integration.zeroconf;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ZeroconfSubscription.Builder} 单测——严格模式：type 必填、properties 累加、name 可空。
 */
public class ZeroconfSubscriptionBuilderTest {

    @Test
    public void testBuild_Ok_TypeAndPropertiesAndName() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder()
                .type("_ecat-test._tcp.local.")
                .property("model", "Test-*")
                .property("vendor", "ECAT")
                .name("device-*")
                .build();
        assertEquals("_ecat-test._tcp.local.", sub.getType());
        assertEquals(2, sub.getProperties().size());
        assertEquals("Test-*", sub.getProperties().get("model"));
        assertEquals("ECAT", sub.getProperties().get("vendor"));
        assertEquals("device-*", sub.getName());
    }

    @Test
    public void testBuild_Ok_NameOptional() {
        ZeroconfSubscription sub = new ZeroconfSubscription.Builder()
                .type("_foo._tcp.local.")
                .build();
        assertNotNull(sub);
        assertTrue("未设 name 应为 null（合法缺席）", sub.getName() == null);
        assertTrue("未设 properties 应为空", sub.getProperties().isEmpty());
    }

    @Test
    public void testBuild_Fail_TypeMissing() {
        try {
            new ZeroconfSubscription.Builder().property("model", "X").build();
            fail("type 必填，build 应抛 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue("异常消息应提示 type 必填", e.getMessage().contains("type 必填"));
        }
    }

    @Test
    public void testBuild_Fail_TypeBlank() {
        try {
            new ZeroconfSubscription.Builder().type("   ").build();
            fail("空白 type 应抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("type 必填"));
        }
    }
}
