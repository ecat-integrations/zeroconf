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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Zeroconf 订阅描述符（peer 模型：归属 integration-zeroconf）。
 * <p>由设备集成 onLoad 时经 {@code ZeroconfDiscoveryIntegration.subscribe(coordinate, subs...)} 声明，
 * 描述"我要被什么样的 mDNS 服务发现"。HA {@code manifest.json} 的 {@code zeroconf:[{type,properties,name}]}
 * 数组元素的 Java 化（一集成可声明多条，覆盖厂家历史多服务类型）。
 *
 * <p><b>匹配语义</b>（由 {@link ZeroconfMatcher} 实现，HA-faithful）：
 * <ul>
 *   <li>{@code type}：mDNS 服务类型，精确匹配（忽略大小写、去尾点归一化）</li>
 *   <li>{@code properties}：TXT 键→值 glob(fnmatch) 的**子集匹配**（服务 TXT 须含全部声明键、且值 fnmatch）</li>
 *   <li>{@code name}：实例名 glob(fnmatch)，可空（null=不按实例名过滤——合法缺席，非默认值）</li>
 * </ul>
 * 任一订阅全部满足即命中。
 *
 * <p><b>Builder 构造</b>：Java 8 无 {@code Map.of}，故用 Builder fluent 累加 properties。
 * 严格模式：{@code type} 必填，{@code build()} 缺失时抛异常（不兜底默认）。
 *
 * @author coffee
 */
public final class ZeroconfSubscription {

    private final String type;                    // mDNS 服务类型（精确匹配；必填）
    private final Map<String, String> properties; // TXT 键→值 glob(fnmatch)；可空
    private final String name;                    // 实例名 glob(fnmatch)；可空=不按实例名过滤

    private ZeroconfSubscription(Builder b) {
        this.type = b.type;
        this.properties = b.properties.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(b.properties));
        this.name = b.name;
    }

    public String getType() {
        return type;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public String getName() {
        return name;
    }

    /**
     * 订阅 Builder。
     * <p>用法：{@code new ZeroconfSubscription.Builder().type("_ecat-test._tcp.local.").property("model","Test-*").build()}
     */
    public static final class Builder {
        private String type;
        private final Map<String, String> properties = new HashMap<String, String>();
        private String name;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /** 累加一个 TXT 键→值 glob 过滤（多次调用累加）。 */
        public Builder property(String key, String valueGlob) {
            this.properties.put(key, valueGlob);
            return this;
        }

        /** 实例名 glob 过滤（可选）。null 或不调用 = 不按实例名过滤。 */
        public Builder name(String glob) {
            this.name = glob;
            return this;
        }

        public ZeroconfSubscription build() {
            // 严格模式：type 必填，不写兜底默认
            if (type == null || type.trim().isEmpty()) {
                throw new IllegalStateException("ZeroconfSubscription.type 必填");
            }
            return new ZeroconfSubscription(this);
        }
    }

    @Override
    public String toString() {
        return "ZeroconfSubscription{type='" + type + "', properties=" + properties + ", name='" + name + "'}";
    }
}
