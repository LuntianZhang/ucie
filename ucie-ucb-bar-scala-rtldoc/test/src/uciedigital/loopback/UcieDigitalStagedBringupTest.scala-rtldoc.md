# UcieDigitalStagedBringupTest 分阶段启动测试解析

这份 ScalaTest / ChiselSim 测试使用 `UcieDigitalLoopbackHarness`，检查两套数字链路从复位、PHY 训练、FDI 建链到双向数据传输的过程。

```text
RDI wake → PHY 训练 → ADV_CAP → 协议信息 → Active 请求
    → 接收激活 → FDI Active → 芯片接口 → 单拍 → 突发 → 旁带故障检查
```

这是一组分阶段测试，不是一个连续运行的测试：每个 `it` 都重新 `simulate` 一个 harness，从冷启动开始。测试代码描述期望行为；是否通过需实际运行测试后确认。

## 一、等待参数

| 常量 | 值 | 用途 |
| --- | --- | --- |
| resetWait | 3,200,000 | 冷启动等待基数 |
| trainCycles | 3,000,000 | 训练或 bring-up 里程碑的最长等待 |
| handshakeCycles | 200,000 | Active 等握手的最长等待 |
| flagCycles | 4,096 | 短握手或 ready 的最长等待 |
| dataCycles | 200,000 | 数据交换循环的最长时间 |
| burstLength | 4 | 每端突发 beat 数 |

这些是测试的时钟周期上限，不是协议规定的固定延迟。`firtoolOpts` 为空数组，源码说明保留 LTSM 经过各子状态时的断言。

## 二、基础辅助方法

`flag(h, die, bit)` 从观测字中提取一位；`bothDies` 要求两端满足条件。`states` 将每端的 `ltState`、`fdiState` 和 `adapterLinkInit` 组成诊断字符串。

`stepUntil` 每次推进一个时钟，直到条件成立或达到上限；失败时断言并打印状态。它只检查条件到达，不自动证明此前所有中间状态都正确。

### 2.1 coldStart

1. 两端 `swStartLinkTraining = false`、`pwrGood = true`，协议控制字清零。
2. 推进 `resetWait + 128` 周期。
3. 仅 die 0 的训练启动拉高 4 周期，然后拉低。

对端如何响应由交叉连接的链路逻辑决定；测试没有直接给 die 1 发启动脉冲。

### 2.2 bringUpToActive

执行 `coldStart`，等待两端 RDI 都为 Active；再对两端设置 `reqActive = true`，等待两端 FDI 都为 Active。PHY 状态机进入 Active 与 RDI Active 并非同一事件，Stage 2 特别分别等待。

## 三、11 个测试阶段

| 阶段 | 检查内容 | 关键观察或断言 |
| --- | --- | --- |
| 1 | RDI wake 应答 | 两端在 `flagCycles` 内应答；LTSM、RDI 仍为 reset，RDI inband 未置位 |
| 2 | 使用真实 adapter 完成训练 | 两端 RDI Active，再等待 LT/LTSM Active；检查无训练错误、超时、recenter 和 RDI stall，且 inband 存在 |
| 3 | ADV_CAP 交换阶段 | 等待两端 FDI inband；检查 adapter link-init 已离开 `INIT_START` |
| 4 | 锁存协议信息 | 等待两端 `negotiatedProto`，并检查 `fdiProtocolVld` |
| 5 | 请求 FDI Active | 两端 RDI Active 后设置请求；等待双方收到 Active 请求，检查请求保持及发送标志，再等待 transitionToActive |
| 6 | 接收端激活 | bring-up 后检查两端 `fdiRxActiveReq`、`fdiRxActiveSts`，以及没有接收溢出 |
| 7 | FDI Active | 检查两端 FDI/RDI Active、inband、协议有效、RDI Active 请求；检查 FDI Active 请求已撤销、没有协议暂停或接收溢出 |
| 8 | 打开芯片接口 | 开启数据端口，等两端 TX ready，继续运行 20,000 周期后检查链路仍 Active，无训练错误、FDI stall 或无故接收 valid |
| 9 | 双向各一拍 | 两端同时发送带标签数据，逐方向比较接收序列，检查传递后 FDI plValid 清除 |
| 10 | 双向突发 | 每端发送 4 拍，检查对端逐拍内容和顺序、链路保持 Active、无溢出及训练错误 |
| 11 | adapter 旁带 | bring-up 后检查两端所有 `sbFaults` 锁存标志均未置位 |

Stage 3 的能力载荷是硬编码的；测试验证交换推进到相应里程碑，不代表已验证任意能力的完整协商。Stage 11 也没有额外注入一笔自定义旁带消息，它检查启动过程中锁存的旁带故障。

## 四、数据如何生成和检查

`payload(bits, die, seq)` 逐个 16 bit 半字生成标签：包含 die、序号和随位置变化的低字节，最后拼成一个 `BigInt`。默认宽度为 512 bit。不同 die 和 beat 的标签有助于发现方向错误、旧数据、顺序错误或数据被破坏。

`exchange` 同时驱动两端 TX，并把两端 RX ready 拉高。每拍先观察 TX ready 和 RX valid，记录将在该边沿完成的握手，然后再 `clock.step(1)`，最后移除已接收的待发送数据并保存收到的值。

待发送队列都清空后，循环还会继续计数 64 个周期；总循环受 `dataCycles` 限制。最后断言所有 TX 都已被接收。`checkDelivery` 再要求对端收到的序列与发送序列完全相同，包括数量、顺序和内容。

注意这 64 周期从发送队列清空开始计数，并不会因收到数据而重新开始；它不是对“最后一次接收之后静默 64 周期”的严格检查。测试也始终把 RX ready 拉高，因此没有覆盖芯片接收端反压。

## 五、失败时从哪里读

- Stage 1–2：先看 RDI wake、训练错误与 LTSM；更细的 PHY 训练阶段可读 `LogPhyStagedBringupTest`。
- Stage 3–5：看 adapter link-init、FDI inband、协议有效和 Active 请求探针。
- Stage 6–8：看接收激活握手、FDI 状态和芯片 TX ready。
- Stage 9–10：比较发送/接收数据及数量，继续追踪 protocol、FDI、adapter、RDI 和 PHY 主带。
- Stage 11：用 `DieFlag.sbFaults` 的名称定位对应旁带故障。

这些测试使用理想化 PHY 状态和固定配置，不替代真实模拟 PHY、错误注入、所有寄存器配置或完整协议兼容性验证。
