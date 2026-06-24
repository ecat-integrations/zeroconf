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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zeroconf 订阅表（peer 模型：归属 integration-zeroconf，**core 不持**）。
 * <p>存储 {@code coordinate → List<ZeroconfSubscription>}：哪个设备集成声明了哪些 mDNS 订阅。
 * 由设备集成 onLoad 经 {@code subscribe(coordinate, subs...)} 填充，由 {@link ZeroconfMatcher} 遍历匹配。
 *
 * <p>线程安全：注册可能来自不同集成的 onLoad（加载阶段单线程，但保守起见用同步）。
 *
 * @author coffee
 */
public class ZeroconfSubscriptionRegistry {

    private final Map<String, List<ZeroconfSubscription>> subs = new LinkedHashMap<String, List<ZeroconfSubscription>>();

    /**
     * 登记一个设备集成的订阅（累加，不覆盖已有）。
     *
     * @param coordinate 设备集成坐标（如 "com.ecat:integration-foo"）
     * @param subscriptions 1+ 订阅描述符
     */
    public synchronized void register(String coordinate, ZeroconfSubscription... subscriptions) {
        if (coordinate == null || subscriptions == null || subscriptions.length == 0) {
            return;
        }
        List<ZeroconfSubscription> list = subs.get(coordinate);
        if (list == null) {
            list = new ArrayList<ZeroconfSubscription>();
            subs.put(coordinate, list);
        }
        Collections.addAll(list, subscriptions);
    }

    /** 取某 coordinate 的订阅列表（不可变快照；无则空）。 */
    public synchronized List<ZeroconfSubscription> get(String coordinate) {
        List<ZeroconfSubscription> list = subs.get(coordinate);
        if (list == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<ZeroconfSubscription>(list));
    }

    /** 所有已登记 coordinate（不可变快照）。 */
    public synchronized Set<String> getCoordinates() {
        return Collections.unmodifiableSet(new HashSet<String>(subs.keySet()));
    }

    /** 全部订阅条目（不可变快照），供 matcher 遍历。 */
    public synchronized Set<Map.Entry<String, List<ZeroconfSubscription>>> all() {
        Map<String, List<ZeroconfSubscription>> snapshot = new LinkedHashMap<String, List<ZeroconfSubscription>>();
        for (Map.Entry<String, List<ZeroconfSubscription>> e : subs.entrySet()) {
            snapshot.put(e.getKey(), Collections.unmodifiableList(new ArrayList<ZeroconfSubscription>(e.getValue())));
        }
        return snapshot.entrySet();
    }
}
