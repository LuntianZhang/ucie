# UcieDigitalLoopbackHarness 双 die 回环测试平台解析

这段代码创建两套完整的数字链路，将两端的 PHY 接口交叉连接，供测试从复位、训练一直观察到协议层数据收发。它是测试用 Chisel 硬件，不是产品顶层，也没有实例化 `UcieDigitalTop` 或寄存器桥。

```text
                 die 0                                  die 1
芯片 TX/RX ↔ ProtocolLayer                        ProtocolLayer ↔ 芯片 TX/RX
                   ↕ FDI                                  ↕ FDI
               D2DAdapter                            D2DAdapter
                   ↕ RDI                                  ↕ RDI
               LogicalPhy ◄──── 主带 / 旁带交叉连接 ────► LogicalPhy
```

两个方向上的 FDI、RDI 内部分别包含多根方向不同的线。这里使用真实 `D2DAdapter` 驱动 RDI，而不是由测试代码逐根模拟这些握手。

## 一、三个定义各做什么

| 名称 | 作用 |
| --- | --- |
| `DieFlag` | 定义 64 位观测字中各标志的位置 |
| `ProtoCtrl` | 定义协议层控制字的位位置和构造函数 |
| `UcieDigitalLoopbackHarness` | 创建并连接两套协议层、adapter、logical PHY |

`DieFlag` 和 `ProtoCtrl` 是 Scala object，用来约定位编号；它们不是独立硬件模块。

## 二、参数和 IO

默认 FDI、RDI 都是 `nBytes = 64, ncWidth = 32`，因此芯片侧一拍为 512 bit。AFE、旁带和协议层参数也可以由调用方替换。

| 参数 | 默认值 | 用途 |
| --- | --- | --- |
| `exposeDataPath` | false | 是否生成芯片侧数据端口；关闭时发送 valid 和接收 ready 等被固定 |
| `exposeAdapterProbes` | true | 是否通过 BoringUtils 读取 adapter 内部状态 |

`Option.when` 在生成硬件时决定可选端口是否存在，不是运行时开关。不开数据端口可减轻模拟器处理数据路径的负担。

两端各有一份输入：`swStartLinkTraining`、`pwrGood`、四位 `protoCtrl`；输出包括 `ltState`、`ltsmState`、`rdiState`、`fdiState`、`adapterLinkInit` 和 `flags`。打开数据路径时，另外生成 `txValid`、`txData`、`rxReady`、`rxData`；发送 ready 和接收 valid 分别通过 flags 暴露。

### 2.1 ProtoCtrl 编码

| 位 | 名称 | 送往协议层 |
| --- | --- | --- |
| 0 | active | requestActive |
| 1 | retrain | requestRetrain |
| 2 | linkReset | requestLinkReset |
| 3 | disable | requestDisable |

`ProtoCtrl.word(...)` 按传入的布尔值拼出 `BigInt` 控制字。协议层自己决定何时在 FDI 上提出 Active 请求，避免在 adapter bring-up 窗口打开前消耗所需边沿。

## 三、交叉连接怎样建立

每端都创建一个 `ProtocolLayer`、一个 `D2DAdapter` 和一个 `LogicalPhy(sim = true)`。循环中 `i` 是本端、`1 - i` 是对端：

```scala
proto.fdi <> adapter.fdi
adapter.rdi <> phy.rdi
```

主带的本端接收 bits/valid 接对端发送 bits/valid；对端发送 ready 则来自本端接收 ready。旁带把对端半速输出的两相数据交给 `SbDriver.bump`，生成送入本端的串行数据和转发时钟。

这不是完整模拟 PHY：`pllLock`、`clocksUngatedAndStable`、`txSelfCalDone` 固定为 true。测试因此不能证明模拟 PLL、自校准、真实封装或模拟电气特性正确。

## 四、控制配置

测试输入控制每端 `pwrGood` 和软件训练启动；重试次数为 0，运行时修复和软件重训练等控制固定关闭。两端使用相同、有效的本地 PHY 参数，训练参数字段也固定为 0。adapter 的三种协议错误上报使能固定为 true，避免上报被这些门控屏蔽。

这份配置便于构造可重复回环，并不覆盖所有速率、训练参数、错误注入或软件寄存器配置。

## 五、flags 观测字

源码使用一个 64 位字而不是大量独立观测端口，以规避注释中描述的当前 Verilator 工具链作用域问题。每端先把所有位清零，再覆盖已使用的标志：

| 位 | 观测内容 |
| --- | --- |
| 0–11 | RDI 带内存在、Active 请求、stall、就绪/有效、wake，以及 PHY 参数有效、recenter、训练错误、超时、RDI 错误 |
| 12–18 | 旁带奇偶、队列满、解串超时、路由及未处理消息的锁存错误 |
| 19–28 | FDI 带内存在、接收激活、协议有效、stall、Active 请求和主带握手 |
| 29–33 | 协议协商有效、暂停、接收溢出、芯片 TX ready、芯片 RX valid |
| 34–40 | adapter 参数交换、Active 请求/响应以及进入 Active 的探针 |
| 41–63 | 此文件中保持 0 |

`DieFlag.sbFaults` 把 12–18 位和名称配对，便于测试遍历。它们是锁存过的故障指示，而不是所有旁带通信细节。

### 5.1 BoringUtils 探针

启用探针时，代码读取 `linkInitStateReg` 和消息收发等内部寄存器。禁用时，34–40 位保持 0，`adapterLinkInit` 固定为 `INIT_START`，因此不能把这些值当成实际内部状态。

`advCapSent` / `advCapRcvd` 只反映当前参数交换子状态，离开时会清除；它们不是“曾经发生过”的永久记录。源码注释也指出可以关闭探针并从波形查看，以减少额外作用域。

## 六、阅读路径

先按图理解两端交叉连接，再看 `DieFlag` 如何把内部状态提供给测试。随后阅读 `UcieDigitalStagedBringupTest`：它通过控制字和训练输入施加激励，再用状态和 flags 判断每一阶段是否完成。
