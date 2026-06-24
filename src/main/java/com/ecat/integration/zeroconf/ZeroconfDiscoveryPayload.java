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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Zeroconf discovery 运行时 payload（peer 模型：归属 integration-zeroconf，**不在 core**）。
 * <p>由 integration-zeroconf 的 jmdns 监听器在服务解析后构造，传给 core 的
 * {@code ConfigFlowService.startDiscoveryFlow} 触发目标设备集成的 ZEROCONF handler。
 * core 对此对象完全不透明（当 Object 透传，不解析）。
 *
 * <p><b>equals/hashCode</b>：基于全部 5 字段——供 core Layer2（R12）按
 * (coordinate, ZEROCONF, payload) 去重（同一服务重复广播不应触发多个 flow）。
 *
 * @author coffee
 */
public final class ZeroconfDiscoveryPayload {

    private final String type;                    // mDNS 服务类型，如 "_ecat-test._tcp.local."
    private final String name;                    // 服务实例名
    private final List<String> addresses;         // 设备地址列表（不可变）
    private final int port;                       // 服务端口
    private final Map<String, String> properties; // TXT 记录（model/sn/vendor/...，不可变）

    public ZeroconfDiscoveryPayload(String type, String name, List<String> addresses,
                                    int port, Map<String, String> properties) {
        this.type = type;
        this.name = name;
        this.addresses = addresses == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(addresses));
        this.port = port;
        this.properties = properties == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(properties));
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public List<String> getAddresses() {
        return addresses;
    }

    public int getPort() {
        return port;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZeroconfDiscoveryPayload)) {
            return false;
        }
        ZeroconfDiscoveryPayload that = (ZeroconfDiscoveryPayload) o;
        return port == that.port
                && Objects.equals(type, that.type)
                && Objects.equals(name, that.name)
                && Objects.equals(addresses, that.addresses)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name, addresses, port, properties);
    }

    @Override
    public String toString() {
        return "ZeroconfDiscoveryPayload{type='" + type + "', name='" + name
                + "', addresses=" + addresses + ", port=" + port + ", properties=" + properties + "}";
    }
}
