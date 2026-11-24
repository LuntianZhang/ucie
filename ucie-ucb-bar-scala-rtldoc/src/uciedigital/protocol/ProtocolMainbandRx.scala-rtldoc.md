# ProtocolMainbandRx.scala

[Scala 源码](../../../../scala/src/uciedigital/protocol/ProtocolMainbandRx.scala)

`ProtocolMainbandRx` 是协议层的主带接收缓冲。它从 FDI 接收原始数据拍，将每拍的 `plData` 原样放入 `ProtocolRawBeat.data`，再通过 FIFO 按顺序交给芯片侧。每拍宽度为 `8 * nBytes` 位，FIFO 最多保存 `depth` 拍。`ProtocolLayer` 分别从 `fdiParams.nBytes` 和 `params.rxQueueDepth` 传入这两个参数，默认是 64 字节和 2 拍。

## RX 信号

| 类别 | 信号 | 作用与相互影响 |
| --- | --- | --- |
| FDI 输入 | `fdi.plValid` | 表示适配器送来一拍。接收使能为高时请求入队，为低时触发验证断言。 |
| FDI 输入 | `fdi.plData` | 原始数据，直接送到 FIFO 的 `enq.bits.data`，在入队握手时保存。 |
| 接收门控输入 | `active`、`rxPathActive` | 分别表示 FDI 处于 ACTIVE、控制器已发出 RX 激活应答。两者同时为高才打开 `captureEnabled`。 |
| FIFO 内部 | `enq.valid`、`enq.ready` | 前者由 `plValid` 和 `captureEnabled` 共同产生。两者同时为高时入队。FIFO 满时，芯片侧当拍的出队可使 `enq.ready` 拉高。 |
| 芯片侧输出 | `chip.valid`、`chip.bits.data` | 对应 FIFO 的 `deq.valid` 和队首数据。 |
| 芯片侧输入 | `chip.ready` | 表示芯片可以接收。与 `chip.valid` 同为高时出队，也可为满队列释放同拍入队机会。 |
| 清除输入 | `clear` | 复位 FIFO 并清零 `rxOverflowReg`。对溢出寄存器的清除优先于当拍的置位条件。 |
| 状态输出 | `rxOverflow` | 暴露 `rxOverflowReg`。接收有效拍时若 FIFO 无法入队，寄存器置位，进而拉低 `rxReadyForActive`。 |
| 激活反馈 | `rxReadyForActive` | 由 FIFO 入队就绪与未溢出共同决定。控制器用它完成 RX 激活握手，握手返回的 `lpRxActiveSts` 再驱动 `rxPathActive`。 |

## 数据如何进入和离开 FIFO

- **FDI 输入。** `captureEnabled = active && rxPathActive`。在顶层，`active` 表示 `plStateSts == FDIState.active`，`rxPathActive` 来自状态控制器的 `lpRxActiveSts`。只有 `plValid && captureEnabled` 为真时，模块才向 FIFO 提交一拍。实际入队还要求 `queue.io.enq.ready` 为真。
- **芯片侧输出。** `chip` 直接连接 FIFO 的 `deq`。在 `chip.valid && chip.ready` 的时钟沿消费队首一拍。芯片侧拉低 `ready` 时，队首数据留在 FIFO 中。接收使能只控制入队，出队由 FIFO 状态和芯片侧握手决定。
- **满队列时同时读写。** `Queue(..., pipe = true)` 允许 FIFO 已满时在同一拍出队和入队。默认的 `flow = false` 使空队列中的新输入先入队，下一拍才对芯片侧可见。

## FDI 无逐拍背压

FDI 接收端只有 `plValid` 和 `plData`。芯片侧的 `ready` 可以暂停 FIFO 出队，FDI 仍可能继续送数据。如果接收已使能、FDI 送来有效拍且 FIFO 当拍无法接收，该拍会丢失，`rxOverflowReg` 在时钟沿置位。它在队列腾出空间后也保持置位，直到复位或 `clear`。置位后，入队仍由接收使能和 FIFO 容量决定。

| 输出 | 含义 |
| --- | --- |
| `rxReadyForActive` | 等于 `queue.io.enq.ready && !rxOverflowReg`。状态控制器在处理 `plRxActiveReq` 时用它决定何时置位 `lpRxActiveSts`。这是接收路径激活握手的条件。 |
| `rxOverflow` | 反映粘滞溢出寄存器，顶层将它接到 `io.status.rxOverflow`。 |

以默认两拍深度为例，若 FIFO 已存有 A、B，FDI 此时送来 C：

| 当拍芯片侧行为 | 时钟沿后的结果 |
| --- | --- |
| 接收 A | A 出队，C 同拍入队，FIFO 留下 B、C。 |
| 暂停接收 | FIFO 留下 A、B，C 丢失，溢出标志置位。 |

## 清除和断言

FIFO 的复位条件是 `reset.asBool || io.clear`。顶层在 `plInbandPres` 为低或 FDI 状态离开 `active` 时拉高 `clearRuntimeState`，由此清空积压的接收数据并清除溢出标志。对溢出寄存器而言，`clear` 优先于当拍的溢出检测。

`Verification.Assert` 层有两条断言：

1. 一旦 `plValid` 为真，`captureEnabled` 也必须为真。否则该拍不会入队，并会触发断言。
2. `rxOverflowReg` 必须一直为假。这条断言检查已锁存的溢出状态。源码中关于不能溢出的注释表达了设计约束，RTL 仍会检测并记录违例。
