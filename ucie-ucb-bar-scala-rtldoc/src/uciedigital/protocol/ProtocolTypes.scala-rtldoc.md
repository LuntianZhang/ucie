# ProtocolTypes.scala

[Scala 源码](../../../../scala/src/uciedigital/protocol/ProtocolTypes.scala)

这个文件定义协议层的配置、原始数据拍和顶层端口。配置在模块展开时决定 FIFO 容量和端口宽度，运行时的握手和状态更新由各子模块实现。

## 配置与数据拍

| 类型或字段 | 用途 |
| --- | --- |
| `ProtocolLayerParams.txQueueDepth` | TX FIFO 容量，默认 2 拍。 |
| `ProtocolLayerParams.rxQueueDepth` | RX FIFO 容量，默认 2 拍。 |
| `FdiParams.nBytes` | 决定 FDI 主带及芯片侧数据拍的字节数。 |
| `ProtocolRawBeat(nBytes)` | 仅含 `data` 字段，类型为 `UInt((8 * nBytes).W)`。有效和就绪信号由外层 `Decoupled` 提供。 |

例如，顶层默认的 `nBytes = 64` 生成 512 位的芯片侧 TX 和 RX 数据端口。两条队列的深度仍各自以拍计。

## 顶层接口

| 端口 | 方向和含义 |
| --- | --- |
| `fdi` | 适配器侧的 `Fdi(fdiParams)` 标准接口，包含主带、边带和链路控制信号。 |
| `mainbandTx` | `Flipped(Decoupled(...))`。芯片侧驱动 `valid/bits`，协议层返回 `ready`。 |
| `mainbandRx` | `Decoupled(...)`。协议层驱动 `valid/bits`，芯片侧返回 `ready`。 |
| `ctrl` | 四个布尔请求输入，分别是 `requestActive`、`requestRetrain`、`requestLinkReset` 和 `requestDisable`。状态控制器选出当拍的 `lpStateReq`。 |
| `status` | 输出链路状态 `linkState`、协商有效位 `negotiatedProtocolValid`、协商协议 `negotiatedProtocol`、协商格式 `negotiatedFlitFormat`、stall 应答状态 `stalled` 和 RX 溢出标志 `rxOverflow`。 |

协商协议和格式应结合 `negotiatedProtocolValid` 一起读取。顶层的 `rxOverflow` 直接来自 RX 模块。

## 各组信号如何配合

| 类别 | 信号 | 作用与相互影响 |
| --- | --- | --- |
| TX 握手 | `mainbandTx.valid`、`mainbandTx.bits.data`、`mainbandTx.ready` | 芯片送有效位和数据，TX 返回就绪。有效与就绪同时为高时，数据进入 TX FIFO。 |
| RX 握手 | `mainbandRx.valid`、`mainbandRx.bits.data`、`mainbandRx.ready` | RX FIFO 提供有效位和数据，芯片给出就绪。两者同时为高时，队首数据被消费。 |
| 软件控制 | `requestActive` | 请求链路进入 ACTIVE；控制器结合当前 FDI 状态决定是否输出 `lpStateReq = active`。 |
| 软件控制 | `requestRetrain` | 请求重新训练；仲裁优先级高于激活。 |
| 软件控制 | `requestLinkReset` | 请求链路复位；仲裁优先级高于重新训练。 |
| 软件控制 | `requestDisable` | 请求禁用链路；在四个软件请求中优先级最高。 |
| 状态 | `linkState` | 报告适配器当前状态 `plStateSts`。顶层同时从该状态生成主带 TX/RX 的 `active`。 |
| 状态 | `negotiatedProtocolValid`、`negotiatedProtocol`、`negotiatedFlitFormat` | 有效位表明后两项是当前应采用的协商结果。带内存在信号撤销时清除有效位。 |
| 状态 | `stalled` | 反映控制器登记的 FDI stall 应答。请求期间的 TX 空闲反馈允许应答置位。 |
| 状态 | `rxOverflow` | 反映 RX 锁存的溢出。RX FIFO 无法接收有效数据拍时置位，并通过 RX 内部逻辑压低激活就绪。 |
