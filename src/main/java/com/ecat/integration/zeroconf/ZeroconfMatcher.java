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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zeroconf 匹配器（peer 模型：归属 integration-zeroconf；**core 不匹配**）。
 * <p>HA-faithful 匹配规则（[HA architecture #198]）：
 * <ul>
 *   <li>{@code type} 精确匹配（归一化：去前后空白、转小写、去尾点）</li>
 *   <li>{@code properties} 每声明键须在 TXT 中存在，且值 {@link Fnmatch} 通配（如 {@code prod-*} 匹配 {@code prod-v1}）</li>
 *   <li>{@code name}（若声明）{@link Fnmatch} 匹配实例名</li>
 * </ul>
 * 一条订阅三项全满足才命中；同一服务可命中多个集成（各起各的 flow）。
 *
 * @author coffee
 */
public class ZeroconfMatcher {

    /**
     * 单条订阅是否匹配 payload。
     */
    public boolean matches(ZeroconfSubscription sub, ZeroconfDiscoveryPayload payload) {
        if (sub == null || payload == null) {
            return false;
        }
        // (a) type 精确（归一化）
        if (!typeEquals(sub.getType(), payload.getType())) {
            return false;
        }
        // (b) properties 子集 + 值 fnmatch
        Map<String, String> declared = sub.getProperties();
        Map<String, String> actual = payload.getProperties();
        for (Map.Entry<String, String> e : declared.entrySet()) {
            String actualVal = actual.get(e.getKey());
            if (actualVal == null) {
                return false; // 声明键 TXT 中不存在
            }
            if (!Fnmatch.match(e.getValue(), actualVal)) {
                return false; // 值不匹配 glob
            }
        }
        // (c) name fnmatch（若声明）
        if (sub.getName() != null && !sub.getName().isEmpty()) {
            if (payload.getName() == null) {
                return false;
            }
            if (!Fnmatch.match(sub.getName(), payload.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 遍历 registry，返回所有被此 payload 命中的 coordinate 列表（同一集成多条订阅命中只返回一次）。
     */
    public List<String> resolveCoordinates(ZeroconfDiscoveryPayload payload, ZeroconfSubscriptionRegistry registry) {
        List<String> hit = new ArrayList<String>();
        if (payload == null || registry == null) {
            return hit;
        }
        for (Map.Entry<String, List<ZeroconfSubscription>> entry : registry.all()) {
            String coordinate = entry.getKey();
            for (ZeroconfSubscription sub : entry.getValue()) {
                if (matches(sub, payload)) {
                    if (!hit.contains(coordinate)) {
                        hit.add(coordinate);
                    }
                    break; // 该集成任一订阅命中即可，不再查其剩余订阅
                }
            }
        }
        return hit;
    }

    /**
     * type 归一化比较：trim + lowercase + 去尾点。
     * <p>mDNS 服务类型可能带尾点（{@code _ecat-test._tcp.local.}），两边归一化后精确相等。
     */
    private boolean typeEquals(String a, String b) {
        return normalizeType(a).equals(normalizeType(b));
    }

    private String normalizeType(String t) {
        if (t == null) {
            return "";
        }
        String s = t.trim().toLowerCase();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
