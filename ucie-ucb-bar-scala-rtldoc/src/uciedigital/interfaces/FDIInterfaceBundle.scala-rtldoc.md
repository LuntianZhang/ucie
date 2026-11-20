# FDIInterfaceBundle 接口解析

FDI（Flit-Aware Die-to-Die Interface）连接协议层与 D2D adapter。这里的 `Fdi` 是信号集合的定义，不是处理数据的硬件模块。

```text
ProtocolLayer ─────── FDI ─────── D2DAdapter
               lp* ───────────►
               pl* ◄───────────
```

图只概括前缀的通常来源；`lclk` 没有前缀，具体方向应以字段声明为准。源码中的方向以协议层为基准：`Input` 进入协议层，`Output` 从协议层发出。adapter 使用 `Flipped(new Fdi(...))`，因此看到的方向相反。

## 一、参数和宽度

```scala
case class FdiParams(nBytes: Int, ncWidth: Int)
class Fdi(params: FdiParams) extends Bundle { ... }
```

`lpData`、`plData` 各为 `nBytes * 8` bit；`lpCfg`、`plCfg` 各为 `ncWidth` bit。例如顶层默认参数为 64 字节和 32 bit，所以主带为 512 bit，配置数据为 32 bit。参数在生成硬件时确定，不是运行时信号。

下表方向均从协议层看；类型未标出宽度的 `Bool` 为 1 bit。

## 二、时钟与主带数据

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| lclk | 输入 | Bool | 接口时钟相关信号；声明为 Bool，而非 Chisel Clock |
| lpIrdy | 输出 | Bool | 协议层发送端就绪指示 |
| lpValid | 输出 | Bool | 协议层发送数据有效指示 |
| lpData | 输出 | UInt(nBytes × 8) | 协议层发往 adapter 的数据 |
| plTrdy | 输入 | Bool | adapter 接收就绪指示 |
| plValid | 输入 | Bool | adapter 返回数据有效指示 |
| plData | 输入 | UInt(nBytes × 8) | adapter 发往协议层的数据 |

发送和接收是两条方向相反的路径。Bundle 只声明信号；准确的传输条件、保持规则和时序需结合协议层及 adapter 的实现阅读，不能直接把整组信号等同于 Chisel `Decoupled`。

## 三、链路状态、错误与接收激活

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| lpStateReq | 输出 | FDIStateReq | 协议层请求链路状态 |
| lpLinkError | 输出 | Bool | 协议层侧链路错误指示 |
| plStateSts | 输入 | FDIState | adapter 返回链路状态 |
| plInbandPres | 输入 | Bool | 带内存在指示 |
| plRxActiveReq | 输入 | Bool | 接收激活请求 |
| lpRxActiveSts | 输出 | Bool | 接收激活状态响应 |
| plError | 输入 | Bool | 错误指示 |
| plCError | 输入 | Bool | 可纠正错误指示 |
| plNfError | 输入 | Bool | 非致命错误指示 |
| plTrainError | 输入 | Bool | 训练错误指示 |
| plPhyInRecenter | 输入 | Bool | PHY 处于 recenter 相关状态 |

`FDIStateReq` 包含 `nop`、`active`、`l1`、`l2`、`linkReset`、`retrain`、`disabled`；`FDIState` 还包含 `reset`、`activePmNak`、`linkError`，不包含 `nop`。枚举定义见 `interfaces/Types.scala`。

## 四、协商结果、暂停、时钟和唤醒

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| plProtocol | 输入 | FDIProtocol | 协议类型 |
| plProtocolFlitFmt | 输入 | FDIFlitFormat | flit 格式 |
| plProtocolVld | 输入 | Bool | 协议/格式信息有效指示 |
| plStallReq | 输入 | Bool | adapter 请求暂停 |
| lpStallAck | 输出 | Bool | 协议层暂停应答 |
| plSpeedmode | 输入 | SpeedMode | 链路速率模式 |
| plMaxSpeedmode | 输入 | Bool | 最大速率模式相关指示 |
| plLnkCfg | 输入 | LinkWidth | 链路宽度配置 |
| plClkReq | 输入 | Bool | 时钟请求 |
| lpClkAck | 输出 | Bool | 时钟请求应答 |
| lpWakeReq | 输出 | Bool | 唤醒请求 |
| plWakeAck | 输入 | Bool | 唤醒应答 |

`SpeedMode` 和 `LinkWidth` 等是 `Types.scala` 中的 Chisel 枚举。请求与应答的具体时序要结合对应状态机看，单看名称不能确定何时拉高或撤销。

## 五、配置通道

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| plCfg | 输入 | UInt(ncWidth) | adapter 到协议层的配置数据 |
| plCfgVld | 输入 | Bool | 上述配置数据有效 |
| plCfgCrd | 输出 | Bool | 该方向的 credit 指示 |
| lpCfg | 输出 | UInt(ncWidth) | 协议层到 adapter 的配置数据 |
| lpCfgVld | 输出 | Bool | 上述配置数据有效 |
| lpCfgCrd | 输入 | Bool | 该方向的 credit 指示 |

方向是从协议层视角写的。credit 的具体计数、消耗和返回时机需要查看配置通道实现，不能从 Bundle 推断。

## 六、结合顶层阅读

顶层用 `protocolLayer.io.fdi <> d2dAdapter.io.fdi` 连接两个互补方向的 Bundle。发送数据从协议层经 `lpData` 进入 adapter；反向数据经 `plData` 返回。状态、错误、暂停和配置字段同时侧接到各自逻辑，并不意味着主带数据无条件直通。


## 交互式运行演示

打开 [UCIe 信号现场 HTML](../../../visualizations/ucie-runtime.html)，可逐步观察启动、主带收发、FDI/RDI 暂停和错误上报。页面列出完整 FDI/RDI 信号，并支持当前值、变化高亮和历史波形查看。演示为基于源码的教学事件快照，不是 RTL 仿真结果；未确定的值会明确标为“未建模”。

页面还包含整个 uciedigital 的源码声明、内部寄存器与连接索引，以及寄存器字段读写/复位实验。要查看展开后全部信号与寄存器的实际时间变化，可在页面中载入完整层级 VCD；只有波形中记录的信号才有真实运行值。
