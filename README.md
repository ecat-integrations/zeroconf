# ECAT Zeroconf 集成模块

让**设备集成支持 mDNS 自动发现**：设备在局域网广播 mDNS 服务 → 本集成匹配命中 → 自动触发该设备集成的 ConfigFlow（`ZEROCONF` source）入网，免手动配置连接参数。

底层用 [jmdns](https://github.com/jmdns/jmdns) 3.5.9（pure Java，无 native lib）。

---

## 如何使用（你的设备集成要做 3 件事）

你要让自己写的设备集成支持 zeroconf 自动发现，只需：

### 1. 加 Maven 依赖

```xml
<dependency>
    <groupId>com.ecat</groupId>
    <artifactId>integration-zeroconf</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
```

> `provided`：编译期拿类型；运行期 core 已加载本集成。

### 2. `onLoad` 里声明订阅（告诉 broker 关注什么服务）

```java
@Override
public void onLoad(EcatCore core, IntegrationLoadOption loadOption) {
    super.onLoad(core, loadOption);

    Object z = core.getIntegrationRegistry()
        .getIntegration(ZeroconfDiscoveryIntegration.COORDINATE);  // "com.ecat:integration-zeroconf"
    if (z instanceof ZeroconfDiscoveryIntegration) {
        ((ZeroconfDiscoveryIntegration) z).subscribe(COORDINATE,   // 你集成的 coordinate
            new ZeroconfSubscription.Builder()
                .type("_mydev._tcp.local.")     // 必填：mDNS 服务类型，精确匹配
                .property("model", "MyDev-*")   // 可选：TXT 属性值 glob，可多次累加
                .name("MyDev-*")                 // 可选：实例名 glob
                .build());
    }
}
```

- `type` **必填**（不填 `build()` 抛异常），与设备广播的服务类型**精确相等**才命中。
- `property(k, vGlob)` / `name(glob)` 用 shell 通配（`*` `?`），可选、可多个。
- 一个集成可 `subscribe` 多个 `ZeroconfSubscription`（参数是 varargs）。

### 3. ConfigFlow 里注册 `ZEROCONF` discovery handler（解析 TXT → 取连接信息 → 入网）

服务命中后由 core 调用 handler，`payload` 不透明透传。handler 职责（真实业务顺序）：
**识别**(TXT 校验/型号) → **取连接信息**(挑 IPv4 + port + 协议参数) → **预填 entryData** → **派生 uniqueId**(去重) → 落 confirm 步。

```java
public class MyDeviceConfigFlow extends AbstractConfigFlow {

    private static final java.util.Set<String> SUPPORTED_MODELS =
        new java.util.HashSet<>(java.util.Arrays.asList("METER-A", "METER-B"));

    public MyDeviceConfigFlow() {
        registerStepDiscovery(SourceType.ZEROCONF, this::onZeroconfDiscovered);
        registerStep("confirm", this::confirmEntry, "确认入网");   // 人工确认步（生产建议）
    }

    private ConfigFlowResult onZeroconfDiscovered(ZeroconfDiscoveryPayload payload, FlowContext ctx) {
        Map<String, String> txt = payload.getProperties();

        // (1) 识别：TXT 必填 + 型号校验（严格模式：不支持就 ABORT，不兜底）
        String model = txt.get("model");
        String sn = txt.get("sn");
        if (model == null || model.isEmpty() || sn == null || sn.isEmpty()) {
            return ConfigFlowResult.abort("TXT 缺 model/sn，非本设备: " + payload);
        }
        if (!SUPPORTED_MODELS.contains(model)) {
            return ConfigFlowResult.abort("不支持的型号: " + model);
        }

        // (2) 取连接信息：从 addresses 挑首个 IPv4（多地址：IPv4/IPv6/多网卡；过滤含 ':' 的 IPv6）
        String ip = pickIpv4(payload.getAddresses());
        if (ip == null) {
            return ConfigFlowResult.abort("未解析到 IPv4 地址（可能 resolve 未完成）: " + payload);
        }
        int port = payload.getPort();

        // (3) 预填 entryData——这些就是后面 createDeviceFromEntry 建连用的连接信息
        ctx.setEntryTitle(payload.getName());
        ctx.setEntryData("ip", ip);                  // ← 连接 IP
        ctx.setEntryData("port", port);              // ← 连接端口
        ctx.setEntryData("model", model);
        ctx.setEntryData("sn", sn);
        ctx.setEntryData("vendor", txt.getOrDefault("vendor", ""));
        // 协议参数也走 TXT（设备广播），例如 Modbus 单元号：
        if (txt.containsKey("unit_id")) {
            ctx.setEntryData("unit_id", Integer.parseInt(txt.get("unit_id")));
        }

        // (4) uniqueId 按 sn 派生（skipValidation=false → 启用 R5 去重，重复设备不再入网）
        ctx.setEntryUniqueId("mydev_" + sn, false);

        // (5) 落 confirm 步：展示发现的设备 + 连接信息供确认
        return ConfigFlowResult.showForm("confirm", buildConfirmSchema(model, sn, ip, port),
                new HashMap<String, Object>(), ctx);
    }

    private ConfigFlowResult confirmEntry(Map<String, Object> userInput) {
        return createEntry();   // entryData/uniqueId 已预填，确认后入库
    }

    /** 挑首个 IPv4（地址可能是 IP 或主机名；含 ':' 视为 IPv6 跳过）。 */
    private static String pickIpv4(List<String> addresses) {
        if (addresses == null) return null;
        for (String a : addresses) {
            if (a != null && !a.contains(":")) return a;   // 简化：非 IPv6 即取；主机名由后续连接层解析
        }
        return null;
    }
}
```

`payload` 提供：`getName()` / `getAddresses()`(`List<String>`) / `getPort()` / `getProperties()`(`Map`)。core 不解析 payload，全由 handler 决定。

#### createDeviceFromEntry 用连接信息建连（后面真正连设备）

入网后 `entry.data` = `{ip, port, model, sn, unit_id, ...}`。设备工厂读这些参数建连：

```java
@Override
protected DeviceBase createDeviceFromEntry(ConfigEntry entry) {
    Map<String, Object> d = entry.getData();
    String ip = (String) d.get("ip");
    int port = ((Number) d.get("port")).intValue();
    int unitId = ((Number) d.getOrDefault("unit_id", 1)).intValue();

    MyDevice device = new MyDevice(entry, ip, port, unitId);   // 设备持有连接信息
    device.load(core);
    device.init();
    return device;   // core 随后 start()——设备在 start() 里用 ip:port 建连、周期轮询
}
// MyDevice.start(): 用 ip+port+unitId 建 Modbus/HTTP/TCP 连接 → 连不上则 deviceStatus=OFFLINE 并重试
```

#### 生产关键：探活验证 + 生命周期（探活 test-discovery 已范例；生命周期生产必做）

1. **探活验证（连接验证）**：mDNS 广播无认证、可能假告警、地址分阶段 resolve——**别只信广播**。
   建议在 confirm 前加一个 **probe 步**（或 discovery 步内联）：用 ip:port 真连一次，确认可达 + 应答 + 型号与 TXT 一致。不可达/不符则 ABORT，不入库流氓或离线设备。
   ```java
   // 可作为 discovery 步的额外校验，或独立 probe 步：
   if (!probeDevice(ip, port, model)) {   // 真连 + 读一个标识寄存器/调一次 /identify
       return ConfigFlowResult.abort("探活失败：不可达或型号不符 " + ip + ":" + port);
   }
   ```
2. **设备消失处理**：设备下线（拔网线/断电/mDNS goodbye）要被感知 → 标 `OFFLINE` 或清理。
   - 短期：设备自身周期轮询失败 → `deviceStatus=OFFLINE`（连接重试）。
   - 长期：订阅 mDNS `serviceResolved` 的对偶——监听设备移除/goodbye，或探活持续超时则 disable entry。
3. **多地址/主机名**：`pickIpv4` 是简化版；若设备给主机名，连接层用 `InetAddress.getByName()` 解析；多网卡时优先同网段。
4. **IP 变更**：设备重新广播新 IP → 触发新 discovery flow → R5 按 uniqueId(sn) 去重 → 此时应在 handler 里识别"已入网设备 IP 变了"并**更新** entry 连接信息（而非拒绝）。

> `integration-test-discovery` 是**机制验证桩 + probe 范例**：覆盖 发现→识别→**真实 HTTP 探活（probe 步：`GET /identify` 校验 model/sn）**→入网→设备运行→数据上报 全闭环。被发现的"设备"是其自带的 `ProbeHttpServer`（integration-httpserver），故 probe 真连可复现。**仍不含**设备消失/Goodbye 处理与 OFFLINE 重试（仿真设备无真实下线），生产集成请按上面第 2 点补生命周期。

### 4. 设备/服务侧要广播什么

被发现的设备（固件）或测试桩需用 mDNS 广播：
- **服务类型** = 你订阅的 `type`（如 `_mydev._tcp.local.`）
- **端口** = 设备监听端口（payload.port）
- **地址** = 设备 IP（jmdns 从 mDNS 解析）
- **TXT 记录** = 你 handler 要读的字段：`model`、`sn`、`vendor`，以及连接/协议参数（如 `unit_id`、协议变体、固件版本）

测试场景用 jmdns 广播的范例见 `integration-test-discovery` 的 `zeroconf/ZeroconfTestLoop`。

---

## 匹配规则（订阅怎么算命中）

一次 mDNS 服务命中某订阅，当且仅当：

| 条件 | 规则 |
|---|---|
| `type` | 与服务类型**精确相等** |
| 每个 `property(k, vGlob)` | 服务 TXT 含该键，且值 fnmatch（**子集语义**：服务有订阅未声明的键不影响命中） |
| `name(glob)`（若声明） | fnmatch 服务实例名 |

**通配坑**：`*` 匹配含字面量。`SMS*` 匹配 `SMS8200`；但 `SMS-*` 不匹配 `SMS8200`（`-` 是字面量）。

---

## 配置（启用 broker）

本集成是纯 broker，**无 ConfigFlow、无运行时配置**。在 core 启用即可：

```yaml
# .ecat-data/core/integrations.yml
  com.ecat:integration-zeroconf:
    groupId: com.ecat
    artifactId: integration-zeroconf
    version: 2.0.0
    enabled: true
```

设备集成侧的 `ecat-config.yml` 声明对本集成的依赖：
```yaml
requires_core: ^2.0.0
dependencies:
  - artifactId: integration-zeroconf
    groupId: com.ecat
    version: ^2.0.0
```

---

## 参考

- **完整闭环范例**：`integration-test-discovery`（IMPORT_FLOW + ZEROCONF 两类发现测试桩，含订阅、2步确认 flow、仿真设备、自验证）。
- **discovery 机制**：`ecat-core/src/main/java/com/ecat/core/ConfigFlow/DISCOVERY.md`（peer 模型 / 订阅式发现路由 / 统一入口 / 去重）。
- **模块目录**：
  ```
  src/main/java/com/ecat/integration/zeroconf/
  ├── ZeroconfDiscoveryIntegration.java   # 入口（jmdns 监听 + 匹配 + 触发 flow）
  ├── ZeroconfSubscription.java           # 订阅声明（Builder：type/property/name）
  ├── ZeroconfSubscriptionRegistry.java   # 订阅表（coordinate → subs）
  ├── ZeroconfMatcher.java                # HA-faithful 匹配
  ├── ZeroconfDiscoveryPayload.java       # 触发 payload（getName/getAddresses/getPort/getProperties）
  └── Fnmatch.java                        # shell 通配匹配
  ```
- **依赖**：`ecat-core`(provided) + `jmdns` 3.5.9(compile)。
