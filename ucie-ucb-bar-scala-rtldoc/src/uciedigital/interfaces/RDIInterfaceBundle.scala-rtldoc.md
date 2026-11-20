# RDIInterfaceBundle 接口解析

RDI（Raw Die-to-Die Interface）连接 D2D adapter 与 logical PHY。`Rdi` 是 Bundle，负责定义信号和方向；状态机、训练和数据处理在使用它的模块里实现。

```text
D2DAdapter ─────── RDI ─────── LogicalPhy
              lp* ───────────►
              pl* ◄───────────
```

源码注释中 `lp*` 表示 adapter 到物理层，`pl*` 表示物理层到 adapter；字段声明的方向以 logical PHY 为基准。`LogicalPhy` 使用 `new Rdi(...)`，adapter 使用 `Flipped(new Rdi(...))`。没有前缀的 `lclk` 以及 credit 信号应特别以实际声明为准。

## 一、参数和宽度

```scala
case class RdiParams(nBytes: Int, ncWidth: Int)
class Rdi(params: RdiParams) extends Bundle { ... }
```

主带 `lpData`、`plData` 的宽度各为 `nBytes * 8` bit；配置数据 `lpCfg`、`plCfg` 各为 `ncWidth` bit。顶层默认分别为 512 bit 和 32 bit。`RdiParams` 与 `FdiParams` 是不同类型，尽管字段名字相同。

下表中的输入和输出都以 logical PHY 为观察点。

## 二、时钟与主带数据

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| lclk | 输入 | Bool | 接口时钟相关信号；类型是 Bool |
| lpIrdy | 输入 | Bool | adapter 发送端就绪指示 |
| lpValid | 输入 | Bool | adapter 发送数据有效 |
| lpData | 输入 | UInt(nBytes × 8) | adapter 发往 PHY 的数据 |
| plTrdy | 输出 | Bool | PHY 接收就绪指示 |
| plValid | 输出 | Bool | PHY 发往 adapter 的数据有效 |
| plData | 输出 | UInt(nBytes × 8) | PHY 发往 adapter 的数据 |

主带发送路径为 adapter → logical PHY，接收路径方向相反。信号怎样配合传输、何时需要保持，需查看两端实现；Bundle 本身没有实现握手。

## 三、链路状态和错误

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| lpStateReq | 输入 | RDIStateReq | adapter 请求链路状态 |
| lpLinkError | 输入 | Bool | adapter 侧链路错误指示 |
| plStateSts | 输出 | RDIState | PHY 返回链路状态 |
| plInbandPres | 输出 | Bool | 带内存在指示 |
| plError | 输出 | Bool | 错误指示 |
| plCError | 输出 | Bool | 可纠正错误指示 |
| plNfError | 输出 | Bool | 非致命错误指示 |
| plTrainError | 输出 | Bool | 训练错误指示 |
| plPhyInRecenter | 输出 | Bool | PHY 处于 recenter 相关状态 |

`RDIStateReq` 包含 `nop`、`active`、`l1`、`l2`、`linkReset`、`retrain`、`disabled`；`RDIState` 还包含 `reset`、`activePmNak`、`linkError`，不包含 `nop`。这些枚举与 FDI 对应枚举分别定义，详见 `interfaces/Types.scala`。

## 四、暂停、速率、时钟和唤醒

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| plStallReq | 输出 | Bool | PHY 请求暂停 |
| lpStallAck | 输入 | Bool | adapter 暂停应答 |
| plSpeedmode | 输出 | SpeedMode | 速率模式 |
| plMaxSpeedmode | 输出 | Bool | 最大速率模式相关指示 |
| plLnkCfg | 输出 | LinkWidth | 链路宽度配置 |
| plClkReq | 输出 | Bool | 时钟请求 |
| lpClkAck | 输入 | Bool | 时钟应答 |
| lpWakeReq | 输入 | Bool | 唤醒请求 |
| plWakeAck | 输出 | Bool | 唤醒应答 |

这些请求和应答分别属于不同方向。状态机如何处理暂停、何时切换速率和响应唤醒，需要继续读 logical PHY 和 adapter 的实现。

## 五、配置通道

| 信号 | 方向 | 类型 | 作用 |
| --- | --- | --- | --- |
| plCfg | 输出 | UInt(ncWidth) | PHY 到 adapter 的配置数据 |
| plCfgVld | 输出 | Bool | 上述配置数据有效 |
| plCfgCrd | 输出 | Bool | 源码声明的 credit 信号 |
| lpCfg | 输入 | UInt(ncWidth) | adapter 到 PHY 的配置数据 |
| lpCfgVld | 输入 | Bool | 上述配置数据有效 |
| lpCfgCrd | 输入 | Bool | 源码声明的 credit 信号 |

注意：此处 `plCfgCrd` 与 `plCfg` 同为输出，`lpCfgCrd` 与 `lpCfg` 同为输入；这与 FDI 中同名 credit 字段的相对方向不同。表格忠实记录接口声明，具体 credit 的含义和使用方式应结合两端代码确认，不能仅凭前缀推断。

## 六、与 FDI 对照

| 对照项 | FDI | RDI |
| --- | --- | --- |
| 连接位置 | 协议层 ↔ adapter | adapter ↔ logical PHY |
| Bundle 方向基准 | 协议层 | logical PHY |
| adapter 一侧 | Flipped | Flipped |
| 特有字段 | 接收激活、协议和 flit 格式 | 本 Bundle 没有这些字段 |
| credit 声明 | `plCfgCrd` 输出、`lpCfgCrd` 输入（协议层视角） | `plCfgCrd` 输出、`lpCfgCrd` 输入（PHY 视角） |

顶层通过 `d2dAdapter.io.rdi <> logicalPhy.io.rdi` 连接。沿发送方向继续读，可以从 adapter 的 `lpData` 出发，进入 logical PHY 的 RDI 处理，再追踪到主带 lane；链路状态和训练条件决定数据何时能通过。


## 交互式运行演示

打开 [UCIe 信号现场 HTML](../../../visualizations/ucie-runtime.html)，可逐步观察启动、主带收发、FDI/RDI 暂停和错误上报。页面列出完整 FDI/RDI 信号，并支持当前值、变化高亮和历史波形查看。演示为基于源码的教学事件快照，不是 RTL 仿真结果；未确定的值会明确标为“未建模”。

页面还包含整个 uciedigital 的源码声明、内部寄存器与连接索引，以及寄存器字段读写/复位实验。要查看展开后全部信号与寄存器的实际时间变化，可在页面中载入完整层级 VCD；只有波形中记录的信号才有真实运行值。
