# ProtocolMainbandTx.scala

[Scala 源码](../../../../scala/src/uciedigital/protocol/ProtocolMainbandTx.scala)

`ProtocolMainbandTx` 将芯片侧送入的原始数据拍暂存在 FIFO 中，再按顺序发往 FDI。每拍宽度为 `8 * nBytes` 位，FIFO 深度为 `depth` 拍。

## TX 信号

| 类别 | 信号 | 作用与相互影响 |
| --- | --- | --- |
| 芯片侧输入 | `chip.valid`、`chip.bits.data` | 芯片提供有效拍及数据。有效位经发送门控送给 FIFO，数据接到 `enq.bits`。 |
| 芯片侧输出 | `chip.ready` | 发送允许且 FIFO 可以入队时拉高，与 `chip.valid` 同为高时接收一拍。 |
| FDI 输出 | `fdi.lpIrdy`、`fdi.lpValid` | 由同一条件驱动，即队首有效、链路 ACTIVE 且无 stall。 |
| FDI 输出 | `fdi.lpData` | 直接取队首数据。有效发送由 `lpIrdy/lpValid` 指示。 |
| FDI 输入 | `fdi.plTrdy` | 适配器可接收时拉高。发送允许时驱动 `deq.ready`，与 `deq.valid` 同为高时出队。满队列可借同拍出队接收新数据。 |
| 发送门控输入 | `active` | 为低时关闭芯片侧接收和 FDI 发送。 |
| 停顿输入 | `stallRequested` | 为高时关闭两侧握手并拉高内部的 `flushQueue`，清空待发送数据。 |
| 控制器反馈 | `txIdle` | 当队列为空或 `flushQueue` 为高时置高，供控制器登记 `lpStallAck`。 |

## 入队和发送

- **芯片侧入队。** 仅当 FDI 处于 `active` 且 `stallRequested` 为低时，FIFO 的入队就绪才传给 `chip.ready`，`chip.valid` 才传给 FIFO。芯片侧在 `chip.valid && chip.ready` 的时钟沿交付一拍。
- **FDI 出队。** 当发送允许且 FIFO 有数据时，`lpIrdy` 和 `lpValid` 同时为高，`lpData` 取队首数据。此时 `plTrdy` 为高才会出队。若 FDI 暂时拉低 `plTrdy`，FIFO 保留队首，后续芯片数据仍可入队，直到容量耗尽。
- **满队列并行读写。** `pipe = true` 让满队列在 FDI 接收队首的同一拍接受一笔新的芯片侧数据。空队列经由寄存缓冲，采用默认的 `flow = false`。

## Stall 与链路退出 ACTIVE

`stallRequested` 一旦拉高，当拍停止入队和发送，同时拉高 `flushQueue`。FIFO 经复位丢弃待发送数据，`txIdle = !queue.io.deq.valid || flushQueue` 也在该拍为高，供状态控制器在时钟沿登记 `lpStallAck`。

仅有 `active` 变低时，入队和出队同样暂停。队列由模块复位或 `stallRequested` 清除，因此单独退出 ACTIVE 会保留已排队的数据。

例如，FIFO 已满并存有 A、B 时，若 FDI 接收 A，同时芯片提交 C，下一拍队列为 B、C。若当拍收到 stall，则 A 不会从 FDI 发出，队列中的 A、B 随清除丢弃。

`Verification.Assert` 层检查 `lpValid` 为高时 FDI 必须处于 ACTIVE。
