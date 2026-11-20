# UcieDigitalTop 顶层代码解析

- 这段代码是 UCIe 数字部分的顶层组装代码。它本身主要做三件事：
1. 定义顶层有哪些输入输出接口。
2. 创建协议层、D2D adapter、logical PHY、寄存器桥等模块。
3. 把这些模块连接起来。
先看全貌，再逐段解释。

```text
                          UcieDigitalTop
  ┌──────────────────────────────────────────────────────────────────┐
  │                                                                  │
  │ 芯片侧                                                           │
  │ chipFacingIo                                                     │
  │      │                                                           │
  │      │ mainbandTx / mainbandRx                                   │
  │      ▼                                                           │
  │ ┌─────────────────┐                                              │
  │ │  ProtocolLayer  │◄──────────────┐                              │
  │ └────────┬────────┘               │ ctrl / status                │
  │          │ FDI                    │                              │
  │          ▼                        │                              │
  │ ┌─────────────────┐       ┌───────┴──────────┐                   │
  │ │   D2DAdapter    │◄─────►│  UcieRegBridge   │◄── io.ctrl         │
  │ └────────┬────────┘ regs  └───────┬──────────┘                   │
  │          │ RDI                    │                              │
  │          ▼                        │ phyCtrl / phyStatus          │
  │ ┌─────────────────┐◄──────────────┘                              │
  │ │   LogicalPhy    │                                              │
  │ └────────┬────────┘                                              │
  │          │                                                       │
  │          │ mainband / sideband / ctrl / status                   │
  │          ▼                                                       │
  │     phyFacingIo                                                  │
  │                                                                  │
  │  寄存器部分：                                                    │
  │  UcieRegTop（可选）或外部 regBlockIo ◄──► UcieRegBridge         │
  └──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                           外部物理 PHY
```

注意，图中的箭头只表示模块间有连接。FDI、RDI 等接口内部包含很多方向不同的信号，不能把整个接口当成一根单向线。

---

## 一、先区分：哪些是“模块”，哪些只是“接口定义”

你给出的代码里有几个 class，但它们不全是硬件模块。

| 名称 | 继承自 | 作用 |
| --- | --- | --- |
| UcieDigitalTopChipIO | Bundle | 定义面向芯片内部的接口 |
| UcieDigitalTopPhyIO | Bundle | 定义面向物理 PHY 的接口 |
| UcieDigitalTopIO | Bundle | 把几个顶层接口组合起来 |
| UcieDigitalTop | LazyModule | 定义顶层和 Diplomacy 节点 |
| UcieDigitalTopImpl | LazyModuleImp | 创建硬件模块并连接信号 |

Bundle 类似一组信号的结构体定义，不等于实例化了一个功能模块。

真正由这段代码创建的主要功能模块是：

| 实例名 | 模块类型 | 作用概括 |
| --- | --- | --- |
| protocolLayer | ProtocolLayer | 处理芯片侧主带数据和 FDI 接口 |
| d2dAdapter | D2DAdapter | 连接协议层与 logical PHY，处理 D2D 适配 |
| logicalPhy | LogicalPhy | 链路训练、主带 lane 和旁带等逻辑 |
| regBridge | UcieRegBridge | 在寄存器与三个功能模块之间转换控制和状态 |
| regs | UcieRegTop | 可选的内部寄存器模块 |

其中 regs 是否存在取决于参数；其余四个功能模块会创建。

---

## 三、芯片侧接口：UcieDigitalTopChipIO

```scala
  class UcieDigitalTopChipIO(protocolParams: ProtocolTopParams) extends Bundle {
    val mainbandTx = Flipped(
      Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
    )
    val mainbandRx = Decoupled(new ProtocolRawBeat(protocolParams.fdi.nBytes))
  }
```

这段定义了顶层面向芯片内部的主带数据接口。

```text
  芯片内部的数据源 ── mainbandTx ──► UcieDigitalTop
  芯片内部的数据接收方 ◄── mainbandRx ── UcieDigitalTop
```

### 3.2 ProtocolRawBeat 是什么？

`new ProtocolRawBeat(protocolParams.fdi.nBytes)`它定义每次传输的数据结构。这里通过 nBytes 参数指定其数据宽度相关配置。


---

## 四、PHY 侧接口：UcieDigitalTopPhyIO

```scala
  class UcieDigitalTopPhyIO(afeParams: AfeParams, sbParams: SidebandParams)
      extends Bundle {
    val mainbandLink = new MainbandLaneIO(afeParams)
    val sidebandLink = new SidebandPhyLinkIO(sbParams.sbLinkWidth)
    val ctrl = Output(new PhyControlToPhyIO(afeParams))
    val status = Input(new PhyStatusFromPhyIO)
  }
```

| UcieDigitalTop | 物理 PHY |  |
| --- | --- | --- |
| mainbandLink | ◄──────────────────────► | 主带收发接口 |
|  sidebandLink | ◄──────────────────────► | 旁带收发接口|
|  ctrl         | ───────────────────────► | PHY 控制|
|  status       | ◄─────────────────────── | PHY 状态|


---

## 五、顶层 IO：把几个接口放在一起

```scala
  class UcieDigitalTopIO(params: UcieDigitalTopParams) extends Bundle {
    val chipFacingIo = new UcieDigitalTopChipIO(params.protocol)
    val phyFacingIo =
      new UcieDigitalTopPhyIO(params.logPhy.afe, params.logPhy.sideband)
    val regBlockIo =
      if (!params.regs.includeRegNode)
        Some(Flipped(new UcieRegBlockIO(params.regs)))
      else None
    val ctrl = new UcieRegBridgeCtrlIO(params.logPhy.retryW)
  }
```

可以把它看成顶层端口清单：

UcieDigitalTopIO

```text
  ├── chipFacingIo   芯片侧数据接口
  ├── phyFacingIo    物理 PHY 侧接口
  ├── regBlockIo     可选的外部寄存器连接
  └── ctrl           额外控制输入
```

### 5.1 为什么 regBlockIo 是 Option？

```scala
  if (!params.regs.includeRegNode)
    Some(Flipped(new UcieRegBlockIO(params.regs)))
  else None
```

这是 Scala 在生成硬件时做出的选择：

- 如果不在顶层内部包含寄存器节点，就生成 regBlockIo，让外部寄存器模块连接进来。
- 如果内部包含寄存器节点，就不生成这个外部接口。
它不是芯片运行时由一个信号控制的多路选择器。

生成配置 A：内部寄存器模块

```text
      UcieRegTop ──► UcieRegBridge
```

生成配置 B：外部寄存器模块

```text
      外部 regBlockIo ──► UcieRegBridge
```

同一个生成实例选择其中一种连接方式。

### 5.2 ctrl 有哪些信号？

在 UcieRegBridge.scala (scala/src/uciedigital/top/UcieRegBridge.scala) 中：

```scala
  class UcieRegBridgeCtrlIO(retryW: Int) extends Bundle {
    val linkReset = Input(Bool())
    val pwrGood = Input(Bool())
    val retryTrainingAmt = Input(UInt(retryW.W))
  }
```

所以这个顶层 ctrl 包含：

| 信号 | 从顶层看 | 含义 |
| --- | --- | --- |
| linkReset | 输入 | 链路复位相关控制 |
| pwrGood | 输入 | 电源就绪指示 |
| retryTrainingAmt | 输入 | 训练重试次数相关配置 |

---

## 六、顶层模块定义

```scala
  class UcieDigitalTop(
      params: UcieDigitalTopParams = UcieDigitalTopParams.default()


)(implicit p: Parameters)
      extends LazyModule {
```

这个类接受两组参数。

### 6.1 params

params: UcieDigitalTopParams = UcieDigitalTopParams.default()

这是项目自己定义的顶层参数，包括：

- protocol 参数；
- adapter 参数；
- logical PHY 参数；
- 寄存器参数。
如果实例化时没有传入，就使用默认参数。

### 6.2 implicit p: Parameters

这是隐式传入的 Diplomacy/系统配置参数。

你可以先把它理解成：外层系统提供的一份配置上下文。它不是单独的一根硬件输入线。

### 6.3 为什么是 LazyModule？

extends LazyModule

主要因为顶层需要与 Diplomacy 的节点机制配合，包括：

- 可选寄存器节点；
- 可选中断节点；
- 寄存器时钟节点。
而真正的硬件实例和大部分信号连接，在后面的 LazyModuleImp 中创建。

---

## 七、参数检查

```scala
  private val validatedParams = params.validate()
```

这里调用参数的 validate()，检查相邻模块的接口配置是否匹配。

例如项目中会检查：

```text
ProtocolLayer 的 FDI 参数必须等于D2DAdapter 的 FDI 参数
```

以及：

```text
D2DAdapter 的 RDI 参数必须等于LogicalPhy 的 RDI 参数
```


这样可以在生成硬件时尽早发现接口宽度或参数不匹配。

接着：

```scala
  require(
    validatedParams.regs.includeRegNode ||
      !validatedParams.regs.includeInterruptNode,
    "includeInterruptNode without includeRegNode builds a register map that no bus can reach"
  )
```

这表示：

> 如果启用了内部中断节点，就必须同时启用内部寄存器节点。
用表格表示：

| includeRegNode | includeInterruptNode | 是否允许 |
| --- | --- | --- |
| false | false | 允许 |
| false | true | 不允许 |
| true | false | 允许 |
| true | true | 允许 |

require 是生成阶段检查；它不是运行时的硬件报警电路。

```scala
  override lazy val desiredName = "UcieDigitalTop"
```

这是指定生成模块期望使用的名字。

---

## 八、可选的内部寄存器模块

```scala
  val ucieRegAllocation = validatedParams.regs.allocation
```

这暴露寄存器地址空间的分配信息。

代码注释说明：当内部不创建寄存器节点、而是把寄存器映射接到外部 TLRegisterNode 时，外层需要知道分配大小。

接着：

```scala
  val regs: Option[UcieRegTop] =
    if (
      validatedParams.regs.includeRegNode ||
        validatedParams.regs.includeInterruptNode
    )
      Some(LazyModule(new UcieRegTop(validatedParams.regs)))
    else None
```

意思是根据配置决定是否创建 UcieRegTop：

需要内部寄存器节点

```text
      → Some(UcieRegTop)
```

不需要内部寄存器节点

```text
      → None
```

由于前面的 require 禁止“只有中断节点、没有寄存器节点”，所以合法配置下：

- includeRegNode = true：创建内部寄存器模块；
- includeRegNode = false：没有内部寄存器模块，使用外部 regBlockIo。
注意：这里的 regs 是 Scala 的 Option，不是一个硬件寄存器名。

### 8.1 暴露寄存器节点和中断节点

```scala
  val regNode = regs.flatMap(_.node)
  val intNode = regs.flatMap(_.intNode)
```

如果内部 UcieRegTop 存在，就取出它的节点；不存在则得到 None。

regNode 和 intNode 是 Diplomacy 节点，不应简单当成普通的单根 RTL 信号。

### 8.2 寄存器时钟节点

```scala
  private val regClockSource = regs.map { r =>
    val src = ClockSourceNode(Seq(ClockSourceParameters()))
    r.clockNode := src
    src
  }
```

如果存在内部寄存器模块：

1. 创建一个 ClockSourceNode。
2. 把寄存器模块的时钟节点接到这个时钟源。
3. 保存这个时钟源，后面再给它具体的时钟和复位。
如果没有内部寄存器模块，map 不会创建这个节点。

---

## 九、进入硬件实现部分

```scala
  override lazy val module = new UcieDigitalTopImpl
  class UcieDigitalTopImpl extends LazyModuleImp(this) {
```

前面定义的 UcieDigitalTop 管理节点和模块结构；这里的 UcieDigitalTopImpl 创建具体的 Chisel 硬件及连接。

```scala
  val io = IO(new UcieDigitalTopIO(validatedParams))
```

这行才是根据前面定义的 Bundle，给当前模块创建实际 IO 端口。

### 9.1 给内部寄存器模块提供时钟和复位

```scala
  regClockSource.foreach { src =>
    val (regClk, _) = src.out(0)
    regClk.clock := clock
    regClk.reset := reset
  }
```

如果前面创建了寄存器时钟节点，就把它连接到当前 UcieDigitalTopImpl 的时钟和复位：

```text
  UcieDigitalTopImpl.clock ──► 内部寄存器时钟
  UcieDigitalTopImpl.reset ──► 内部寄存器复位
```

foreach 是 Scala 对 Option 的处理：

- Some(src)：执行里面的连接。
- None：不执行。

---

## 十、实例化三个主要数据通路模块

### 10.1 ProtocolLayer
```text
  ├── ProtocolStateController
  ├── ProtocolMainbandTx
  ├── ProtocolMainbandRx
  └── ProtocolSidebandChannel
```

也就是说，顶层的一个 protocolLayer，内部还可以有多个更小的模块。

### 10.2 D2DAdapter
```text
  ├── AdapterSM
  ├── FDIStallHandler
  ├── RDIStallHandler
  ├── D2DSidebandModule
  └── D2DMainbandModule
```

它不只是把 FDI 数据“接线”到 RDI，还包含状态管理、旁带处理以及暂停握手等逻辑。

### 10.3 LogicalPhy
```text
  ├── rdi             与 D2DAdapter 连接
  ├── ctrl            接收控制
  ├── status          输出状态
  └── analog
      ├── mainband    与物理 PHY 主带连接
      ├── sidebandLink
      ├── ctrl        送往物理 PHY 的控制
      └── status      来自物理 PHY 的状态
```

它内部有训练状态机、主带 lane 控制、训练图样生成/检查、旁带通道等模块，具体可从 LogicalPhy.scala (scala/src/

uciedigital/logphy/LogicalPhy.scala:68) 往下读。

这里的 analog 是代码里的接口命名；它所暴露的仍是 Chisel 定义的数字信号接口，不能据此认为 LogicalPhy 内部就是模拟

电路。

---

## 十三、PHY 侧主带和旁带连接

```scala
  // PHY-facing connection
  io.phyFacingIo.mainbandLink <> logicalPhy.io.analog.mainband
  io.phyFacingIo.sidebandLink <> logicalPhy.io.analog.sidebandLink
```

---

## 十四、寄存器桥：UcieRegBridge

```scala
  val regBridge = Module(
    new UcieRegBridge(
      params = validatedParams.regs,
      afeParams = validatedParams.logPhy.afe,
      retryW = validatedParams.logPhy.retryW
    )
  )
```

创建寄存器桥。

它的作用不是把用户数据从主带发出去，而是负责把寄存器配置、控制输入和模块状态接起来。

可以理解成：

```text
               软件 / 寄存器
                    │
                    ▼
             UcieRegBridge
               │    │    │
               ▼    ▼    ▼
            协议层 adapter logical PHY
               │    │    │
               └─状态返回─┘
```

这里“桥”不只是简单接线。其源码里还包含编码转换、边沿检测、状态记录和错误状态处理等逻辑。

---

## 十五、寄存器桥逐条怎么连接？

### 15.1 顶层额外控制

regBridge.io.ctrl <> io.ctrl

把顶层的：

linkReset

pwrGood

retryTrainingAmt

连接给寄存器桥。

例如桥内部把：

```scala
  io.phyCtrl.pwrGood := io.ctrl.pwrGood
  io.phyCtrl.retryTrainingAmt := io.ctrl.retryTrainingAmt
```

送往 logical PHY 控制接口。

---

### 15.2 Logical PHY 控制

regBridge.io.phyCtrl <> logicalPhy.io.ctrl

连接：

```text
  UcieRegBridge ──控制信息──► LogicalPhy
```

桥向 logical PHY 提供的内容包括：

- 电源就绪；
- 训练重试配置；
- 软件启动训练请求；
- 重训练相关请求；
- 一些训练参数和本地 PHY 参数。
不是说这条接口里的所有字段都一定来自同一个寄存器：有些来自寄存器，有些来自顶层 io.ctrl，也有些由桥内部逻辑产生。

### 15.3 Logical PHY 状态

regBridge.io.phyStatus <> logicalPhy.io.status

连接：

```text
  LogicalPhy ──状态信息──► UcieRegBridge
```

例如：

- 当前训练状态；
- 训练超时；
- 严重训练错误；
- 协商得到的速率和链路宽度；
- lane 状态；
- 旁带错误状态。
寄存器桥会把其中一些状态整理后送到寄存器模块，供外部读取。

---

### 15.4 协议层控制

regBridge.io.protoCtrl <> protocolLayer.io.ctrl

连接协议层的控制接口。

例如桥内部会设置：

```scala
  io.protoCtrl.requestActive := true.B
  io.protoCtrl.requestRetrain := regsToLink.retrainPending
```

也就是说，一部分协议层控制来自固定值，一部分来自寄存器状态。

### 15.5 协议层状态

regBridge.io.protoStatus <> protocolLayer.io.status

协议层将状态返回给寄存器桥，例如：

- 链路状态；
- 协商出的协议和 flit 格式；
- 是否暂停；
- 接收溢出状态。
桥再把相关信息整理成寄存器状态。

---

### 15.6 Adapter 寄存器接口

regBridge.io.adapter <> d2dAdapter.io.regs

把寄存器桥与 D2D adapter 的寄存器相关接口连接起来。

这条连接不是单向的：

```text
  UcieRegBridge ──配置 / 控制──► D2DAdapter
  UcieRegBridge ◄──状态 / 错误── D2DAdapter
```

例如 adapter 可以提供链路状态、参数交换结果和旁带错误；桥也会提供部分错误上报控制。

---

## 十六、内部寄存器与外部寄存器：二选一

```scala
  regs match {
    case Some(r) => UcieRegBridge.attach(r.module.io, regBridge.io.regs)
    case None =>
      io.regBlockIo.foreach(UcieRegBridge.attach(_, regBridge.io.regs))
  }
```

这段是 Scala 的模式匹配。

### 情况一：有内部 UcieRegTop

```scala
  case Some(r) =>
    UcieRegBridge.attach(r.module.io, regBridge.io.regs)
```

连接方式：

内部 UcieRegTop

```text
        ↕
```

```scala
  UcieRegBridge.attach(...)
        ↕
```

UcieRegBridge

### 情况二：没有内部 UcieRegTop

```scala
  case None =>
    io.regBlockIo.foreach(UcieRegBridge.attach(_, regBridge.io.regs))
```

连接方式：

外部寄存器模块

```text
        ↕
```

顶层 io.regBlockIo

```text
        ↕
```

```scala
  UcieRegBridge.attach(...)
        ↕
```

UcieRegBridge

### attach 做了什么？

它定义在 UcieRegBridge.scala (scala/src/uciedigital/top/UcieRegBridge.scala) 的 object UcieRegBridge 中。

它不是一个额外的硬件模块，而是一个 Scala 方法，用于集中写这些字段的连接，例如：

寄存器模块 → 桥：regsToPhy、regsToLink、regsToAdapter

桥 → 寄存器模块：phyToRegs、linkToRegs、adapterToRegs

还包括 mailbox 和可选 vendor 接口的连接。

---

## 十七、dontTouch 中断信号

regs.foreach { r =>

```scala
    r.module.io.linkEventIrq.foreach(dontTouch(_))
    r.module.io.linkErrorIrq.foreach(dontTouch(_))
  }
```

如果内部寄存器模块存在，并且对应中断信号存在，就对它们使用 dontTouch。

dontTouch 的作用是告诉生成/优化流程保留这些信号，方便观察或后续使用。

需要注意：

> 这里没有因为调用 dontTouch 就自动把信号接到某个外部中断控制器。
它只说明这里要求保留这些信号；中断节点具体如何接入外层系统，要继续看外层的 Diplomacy 连接。

---

## 十八、PHY 控制和状态的单向连接

最后两行：

```scala
  logicalPhy.io.analog.status := io.phyFacingIo.status
  io.phyFacingIo.ctrl := logicalPhy.io.analog.ctrl
```

这里使用 :=，方向更容易看：

接收方 := 驱动方

### 18.1 物理 PHY 状态进入 logical PHY

```scala
  logicalPhy.io.analog.status := io.phyFacingIo.status
```

方向：

外部物理 PHY

```text
      │ status
      ▼
```

UcieDigitalTop.io.phyFacingIo.status

```text
      │
      ▼
```

LogicalPhy.io.analog.status

例如 PHY 的时钟就绪、PLL 锁定状态送入 logical PHY。

### 18.2 Logical PHY 控制输出到物理 PHY

```scala
  io.phyFacingIo.ctrl := logicalPhy.io.analog.ctrl
```

方向：

LogicalPhy.io.analog.ctrl

```text
      │
      ▼
```

UcieDigitalTop.io.phyFacingIo.ctrl

```text
      │
      ▼
```

外部物理 PHY

例如速率选择 freqSel 从 logical PHY 输出，传到 PHY 侧。

再次注意前面的代码注释：当前并非这个控制 Bundle 里的所有字段都已经被后续 PHY 控制逻辑使用。

---

## 十九、完整走一遍：发送数据

假设芯片内部要向另一颗 die 发送一拍数据。

### 第一步：进入顶层

芯片内部模块

```text
      │
      ▼
```

```scala
  io.chipFacingIo.mainbandTx
```

外部通过 bits 提供数据，valid 表示数据有效；顶层通过 ready 表示是否接收。

### 第二步：进入协议层

对应代码：

```scala
  protocolLayer.io.mainbandTx <> io.chipFacingIo.mainbandTx
```

路径：

chipFacingIo.mainbandTx

```text
      ↓
```

ProtocolLayer.mainbandTx

### 第三步：经过 FDI

对应代码：

```scala
  protocolLayer.io.fdi <> d2dAdapter.io.fdi
```

路径：

ProtocolLayer

```text
      ↓ FDI
```

D2DAdapter

### 第四步：经过 RDI

对应代码：

```scala
  d2dAdapter.io.rdi <> logicalPhy.io.rdi
```

路径：

D2DAdapter

```text
      ↓ RDI
```

LogicalPhy

### 第五步：送到物理 PHY

对应代码：

```scala
  io.phyFacingIo.mainbandLink <> logicalPhy.io.analog.mainband
```

路径：

LogicalPhy

```text
      ↓
```

phyFacingIo.mainbandLink

```text
      ↓
```

外部物理 PHY

```text
      ↓
```

芯片间物理连接

实际能否发送还取决于链路是否完成训练、状态是否允许、各层握手是否满足等。数据路径图只展示主要方向，不表示数据无条

件直通。

---

## 二十、完整走一遍：状态和控制

再看一个与主带数据不同的路径：PHY 状态和训练控制。

物理 PHY 的 PLL/时钟状态

```text
      ↓
```

```scala
  io.phyFacingIo.status
      ↓
  logicalPhy.io.analog.status
      ↓
```

LogicalPhy 内部训练逻辑

```text
      ↓
```

```scala
  logicalPhy.io.status
      ↓
```

regBridge.io.phyStatus

```text
      ↓
```

寄存器状态

反方向的控制可以是：

寄存器配置

```text
      ↓
```

UcieRegBridge

```text
      ↓
```

```scala
  logicalPhy.io.ctrl
      ↓
```

LogicalPhy 训练和控制逻辑

```text
      ↓
```

```scala
  logicalPhy.io.analog.ctrl
      ↓
  io.phyFacingIo.ctrl
      ↓
```

物理 PHY 或后续控制逻辑

这两条路径说明：寄存器桥不是主带数据路径中的一个串行站点。它侧接到各功能模块，负责控制和状态。

---

## 二十一、你可以这样做阅读笔记

对于这个文件，我建议先整理成这张表：

| 模块或接口 | 上游/外部连接 | 下游/外部连接 | 本文件里的作用 |
| --- | --- | --- | --- |
| chipFacingIo | 芯片内部逻辑 | ProtocolLayer | 发送和接收主带数据 |
| protocolLayer | chipFacingIo | FDI、寄存器桥 | 协议层 |
| d2dAdapter | FDI | RDI、寄存器桥 | D2D 适配和链路管理 |
| logicalPhy | RDI、寄存器桥 | PHY 侧接口 | 训练、lane 和 PHY 控制 |
| phyFacingIo | LogicalPhy | 外部物理 PHY | 主带、旁带、控制和状态 |
| regBridge | 顶层控制、寄存器 | 三个功能模块 | 控制转换和状态汇总 |
| regs / regBlockIo | 内部或外部寄存器路径 | regBridge | 配置和状态访问 |

然后在笔记里标出两个待深入问题：

1. FDI 和 RDI 里每个字段具体由谁驱动？

```text
     下一步读两个接口的 Bundle 定义，并在相邻模块中搜索字段名。
```

2. 一笔 mainbandTx 数据在协议层内部怎么走？
下一步从 ProtocolLayer.scala (scala/src/uciedigital/protocol/ProtocolLayer.scala) 搜索 mainbandTx，进入

```text
     ProtocolMainbandTx，再看它怎样产生 FDI 信号。
```

读完这一层，你最需要掌握的是：三个数据通路模块的先后关系、FDI/RDI 的连接位置，以及寄存器桥如何从旁边控制和观察它

们。


## 交互式运行演示

打开 [UCIe 信号现场 HTML](../../../visualizations/ucie-runtime.html)，可逐步观察启动、主带收发、FDI/RDI 暂停和错误上报。页面列出完整 FDI/RDI 信号，并支持当前值、变化高亮和历史波形查看。演示为基于源码的教学事件快照，不是 RTL 仿真结果；未确定的值会明确标为“未建模”。

页面还包含整个 uciedigital 的源码声明、内部寄存器与连接索引，以及寄存器字段读写/复位实验。要查看展开后全部信号与寄存器的实际时间变化，可在页面中载入完整层级 VCD；只有波形中记录的信号才有真实运行值。
