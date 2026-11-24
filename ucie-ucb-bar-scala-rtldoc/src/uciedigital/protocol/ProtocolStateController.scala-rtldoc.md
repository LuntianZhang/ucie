# ProtocolStateController.scala

[Scala 源码](../../../../scala/src/uciedigital/protocol/ProtocolStateController.scala)

`ProtocolStateController` 处理 FDI 链路请求、协商结果、stall、时钟及 RX 激活握手。它从 TX 获取 `txIdle`，从 RX 获取 `rxReadyForActive`，并把控制信号送回 FDI 和 RX。

## ProtocolStateControllerIO

方向以状态控制器为参照。FDI 中的 `pl*` 由适配器驱动，`lp*` 由协议层驱动。

| 端口 | 方向 | Chisel 类型 | 作用 |
| --- | --- | --- | --- |
| `ctrl` | 输入 | `ProtocolLayerCtrlIO` | 接收激活、重训、链路复位和禁用请求，经仲裁产生 `fdi.lpStateReq`。 |
| `fdi` | 双向 | 内联 `Bundle` | FDI 链路控制信号，字段见下表。 |
| `txIdle` | 输入 | `Bool` | TX 空闲反馈。与 `fdi.plStallReq` 同为高时，在时钟沿置位 stall 应答。 |
| `rxReadyForActive` | 输入 | `Bool` | RX FIFO 可入队且无锁存溢出时为高，允许激活状态机进入应答状态。 |
| `clearRuntimeState` | 输出 | `Bool` | `plInbandPres` 为低或链路状态不等于 ACTIVE 时拉高，顶层将它接到 RX 的 `clear`。 |
| `status` | 输出 | `ProtocolLayerStatusIO` | 输出链路状态、协商结果和 stall 状态。控制器内的 `status.rxOverflow` 固定为低，顶层从 RX 模块取得实际溢出标志。 |

### FDI 字段
- lpStateReq（协议层 → D2D Adapter）表示希望链路做什么。
- plStateSts（D2D Adapter → 协议层）表示链路现在处于什么状态。

| 字段 | 方向 | Chisel 类型 | 作用 |
| --- | --- | --- | --- |
| `plStateSts` | 输入 | `FDIState` | 适配器报告的链路状态，影响请求仲裁、协商锁存和 RX 清除。 |
| `plInbandPres` | 输入 | `Bool` | 带内存在标志。与 RESET 状态共同界定启动窗口，为低时清除协商有效位并触发 RX 清除。 |
| `plRxActiveReq` | 输入 | `Bool` | 请求激活 RX。状态机结合 `rxReadyForActive` 生成 `lpRxActiveSts`。 |
| `plProtocol` | 输入 | `FDIProtocol` | 待锁存的协商协议。 |
| `plProtocolFlitFmt` | 输入 | `FDIFlitFormat` | 待锁存的协商 Flit 格式。 |
| `plProtocolVld` | 输入 | `Bool` | 协商值有效。与 RESET 和带内存在条件共同控制协议和格式的锁存。 |
| `plStallReq` | 输入 | `Bool` | 停顿请求。请求保持时等待 `txIdle`，撤销时清除应答寄存器。 |
| `plClkReq` | 输入 | `Bool` | 时钟请求，采样后驱动下一拍的 `lpClkAck`。 |
| `lpStateReq` | 输出 | `FDIStateReq` | 向适配器发送仲裁后的链路状态请求。 |
| `lpLinkError` | 输出 | `Bool` | 协议层链路错误指示，当前固定为低。 |
| `lpStallAck` | 输出 | `Bool` | 寄存后的停顿应答，与 `status.stalled` 同源。 |
| `lpWakeReq` | 输出 | `Bool` | 唤醒请求，当前固定为高。 |
| `lpClkAck` | 输出 | `Bool` | 时钟应答，等于上一拍采样的 `plClkReq`。 |
| `lpRxActiveSts` | 输出 | `Bool` | RX 激活应答。从 `sAsserted` 状态译码，同时反馈给 RX 作为 `rxPathActive`。 |

## 内部变量

### 协商、链路请求与握手

| 变量 | 类型与初值或默认值 | 更新条件与作用 |
| --- | --- | --- |
| `negotiatedProtocolReg` | `FDIProtocol` 寄存器，复位为 `pcieNoManagementTransport` | 当 FDI 为 RESET 且 `plInbandPres`、`plProtocolVld` 均为高时锁存 `plProtocol`，驱动 `status.negotiatedProtocol`。 |
| `negotiatedFlitFormatReg` | `FDIFlitFormat` 寄存器，复位为 `rawFormat` | 与协议寄存器在同一时钟沿锁存 `plProtocolFlitFmt`，驱动 `status.negotiatedFlitFormat`。 |
| `negotiatedValidReg` | `Bool` 寄存器，复位为低 | 锁存协商值时置位。采样到 `plInbandPres = 0` 时清零，协议和格式寄存器保留原值。 |
| `fdiInBringupWindow` | `Bool` 组合信号 | FDI 为 RESET 且 `plInbandPres = 1` 时为高，允许发出 ACTIVE 请求。适配器在 FDI bring-up 阶段检测 `nop → active` 跳变。 |
| `fdiNeedsActiveToRecover` | `Bool` 组合信号 | FDI 为 `linkError`、`disabled` 或 `linkReset` 时为高，允许通过保持 ACTIVE 请求电平恢复链路。 |
| `requestedState` | `FDIStateReq` 组合信号，默认 `nop` | 依次仲裁 disable、link reset、retrain 和 active。active 还要求上述两个条件之一成立。结果驱动 `lpStateReq`。 |
| `stallAckReg` | `Bool` 寄存器，复位为低 | 当 `plStallReq && txIdle` 成立时置位，请求为低时清零，其余情况保持。驱动 `lpStallAck` 和 `status.stalled`。 |
| `clkAckReg` | `Bool` 寄存器，复位为低 | 每拍采样 `plClkReq`，驱动一拍延迟的 `lpClkAck`。 |
| `rxActiveStateReg` | `RxActiveState` 寄存器，复位为 `sIdle` | 每拍锁存 `nextRxActiveState`。当前状态为 `sAsserted` 时，组合输出 `lpRxActiveSts` 为高。 |
| `nextRxActiveState` | `RxActiveState` 组合信号，默认等于当前状态 | 根据当前状态、`plRxActiveReq` 和 `rxReadyForActive` 计算下一状态。 |

### 断言使用的上一拍值

| 变量 | 采样对象与复位值 | 用途 |
| --- | --- | --- |
| `prevClkReqReg` | `plClkReq`，复位为低 | 与当前请求比较检测上升沿，检查 `lpClkAck` 在请求刚拉高时仍为低。 |
| `prevRxActiveReqReg` | `plRxActiveReq`，复位为低 | 与当前请求比较检测上升沿，检查 `lpRxActiveSts` 在请求刚拉高时仍为低。 |
| `prevNegotiatedValidReg` | `negotiatedValidReg`，复位为低 | 上一拍协商有效且当前 `plInbandPres` 为高时，启用协商值稳定性检查。 |
| `prevNegotiatedProtocolReg` | `negotiatedProtocolReg`，无显式复位初值 | 稳定性检查启用时，与当前协议寄存器比较。 |
| `prevNegotiatedFlitFormatReg` | `negotiatedFlitFormatReg`，无显式复位初值 | 稳定性检查启用时，与当前 Flit 格式寄存器比较。 |

## 链路、协商与清除信号

| 信号 | 来源或去向 | 作用与相互影响 |
| --- | --- | --- |
| `requestDisable`、`requestLinkReset`、`requestRetrain`、`requestActive` | 芯片侧输入 | 按所列顺序仲裁。前三项直接选出请求，`requestActive` 还受当前链路状态和 bring-up 条件约束。 |
| `plStateSts` | FDI 输入 | 决定能否请求 ACTIVE、能否锁存协商值以及是否清除 RX，直接反馈为 `status.linkState`。 |
| `plInbandPres` | FDI 输入 | 参与判定 bring-up 窗口。为低时清除协商有效位，并要求清除 RX。 |
| `plProtocol`、`plProtocolFlitFmt`、`plProtocolVld` | FDI 输入 | 前两项是协商值，有效位与 RESET、带内存在条件一起控制寄存器更新。 |
| `lpStateReq` | FDI 输出 | 输出仲裁后的链路请求，无请求时输出 `nop`。 |
| `status.negotiatedProtocol`、`status.negotiatedFlitFormat`、`status.negotiatedProtocolValid` | 芯片侧输出 | 暴露已锁存的协商结果及其有效性。 |
| `clearRuntimeState` | RX 输出路径 | 带内存在为低或 FDI 离开 ACTIVE 时拉高，顶层用它清除 RX FIFO 和溢出标志。 |

## 握手信号

| 信号 | 来源或去向 | 作用与相互影响 |
| --- | --- | --- |
| `plRxActiveReq`、`rxReadyForActive` | FDI 请求、RX 反馈 | 请求启动状态机，就绪反馈允许它进入应答状态。 |
| `lpRxActiveSts` | FDI 和 RX | 当前状态为 `sAsserted` 时拉高，顶层同时用它打开 RX 的路径门控。 |
| `plStallReq`、`txIdle` | FDI 请求、TX 反馈 | 请求也直连 TX。控制器在请求有效且 TX 报告空闲时置位应答寄存器。 |
| `lpStallAck`、`status.stalled` | FDI、芯片侧 | 共用 stall 应答寄存器，采样到请求撤销时清零。 |
| `plClkReq`、`lpClkAck` | FDI 输入、输出 | 输出是输入的一拍寄存值。 |
| `lpWakeReq`、`lpLinkError` | FDI 输出 | 当前分别固定为高、低。 |

## 链路请求与协商结果

`lpStateReq` 是组合输出，按从高到低的优先级选择 `disabled`、`linkReset`、`retrain` 和 `active`。前三项分别由 `requestDisable`、`requestLinkReset`、`requestRetrain` 直接请求。`requestActive` 还要求以下任一条件成立：

- FDI 状态为 `reset` 且 `plInbandPres` 为高，对应适配器的 FDI bring-up 窗口。
- FDI 状态为 `linkError`、`disabled` 或 `linkReset`，此时可持续输出 `active` 请求以恢复链路。

无符合条件的请求时输出 `nop`。`status.linkState` 直接反映适配器报告的 `plStateSts`。

在 `reset` 状态、`plInbandPres` 和 `plProtocolVld` 同时为高时，时钟沿锁存 `plProtocol` 和 `plProtocolFlitFmt` 并置位协商有效位。带内存在标志消失时清除有效位，协议和格式寄存器保留上次写入值。复位初值为 `pcieNoManagementTransport`、`rawFormat` 和无效。

## RX 激活状态机

状态转移在时钟沿生效。`lpRxActiveSts` 取决于当前状态。

| 当前状态 | `lpRxActiveSts` | 转移条件 |
| --- | --- | --- |
| `sIdle` | 0 | `plRxActiveReq` 为高时进入 `sWaitAssert`。 |
| `sWaitAssert` | 0 | 请求撤销时回到 `sIdle`。请求保持且 `rxReadyForActive` 为高时进入 `sAsserted`。 |
| `sAsserted` | 1 | 请求撤销时进入 `sWaitDeassert`。 |
| `sWaitDeassert` | 0 | 请求为低时回到 `sIdle`。请求重新为高时，等待 `rxReadyForActive` 为高再回到 `sAsserted`。 |

例如，从 `sIdle` 收到持续的激活请求且 RX 已就绪时，先经过一个 `sWaitAssert` 周期，再拉高 `lpRxActiveSts`。

## Stall、时钟与运行状态清除

- `plStallReq` 和 `txIdle` 同为高时，置位寄存的 `lpStallAck`。请求保持期间，应答保持，直到采样到请求为低时清除。`status.stalled` 使用同一个应答寄存器。
- `lpClkAck` 是 `plClkReq` 的一拍寄存输出。`lpWakeReq` 固定为高，`lpLinkError` 固定为低。
- `clearRuntimeState = !plInbandPres || (plStateSts =/= FDIState.active)`。顶层将它接到 RX 的 `clear`，用于清空 RX FIFO 和溢出标志。控制器自身的 `status.rxOverflow` 固定为低，顶层从 RX 模块取得实际状态。

`Verification.Assert` 层检查时钟请求和 RX 激活请求的上升沿当拍对应应答为低、stall 应答为高期间 TX 为空闲，以及上一拍协商有效且当前带内存在标志仍高时协议与格式保持稳定。
