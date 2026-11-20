# UcieDigitalTopParams 顶层参数解析

这段代码把顶层各模块的生成参数组织在一起，并检查相邻接口是否匹配。它定义的是 Scala 配置，不是硬件模块，也不是运行时可修改的寄存器。

```text
UcieDigitalTopParams
├── protocol ── FDI ──┐
├── adapter  ── FDI ──┘
│            ── RDI ──┐
├── logPhy   ── RDI ──┘
│            ── sideband 与 adapter 的 sideband 相同
└── regs     寄存器模块配置
```

## 参数结构树

下面展示默认顶层配置的组成；`FdiParams`、`RdiParams` 中的数值由 `default()` 提供，其余标注的值来自各参数类或顶层的默认值。

```text
UcieDigitalTopParams.default()
├── protocol: ProtocolTopParams
│   ├── fdi: FdiParams
│   │   ├── nBytes = 64
│   │   └── ncWidth = 32
│   └── layer: ProtocolLayerParams()
├── adapter: AdapterTopParams
│   ├── fdi: FdiParams(nBytes = 64, ncWidth = 32)
│   ├── rdi: RdiParams(nBytes = 64, ncWidth = 32)
│   └── sideband: SidebandParams()
│       ├── sbNodeMsgWidth = 128
│       ├── maxCrd = 32
│       └── sbLinkWidth = 1
├── logPhy: LogicalPhyTopParams
│   ├── afe: AfeParams()
│   ├── sideband: SidebandParams()  （与 adapter.sideband 相等）
│   ├── rdi: RdiParams(nBytes = 64, ncWidth = 32)
│   ├── retryW = 10
│   ├── desTimeoutCycles = 512
│   ├── queueDepths: SidebandPriorityQueueDepths()
│   │   ├── messageRequestOrResponse = 32
│   │   ├── regAccessCompletion = 32
│   │   ├── regAccessRequest = 32
│   │   └── other = 32
│   └── sim = false
└── regs: UcieRegParams
    ├── phyCapability: PhyCapabilityRegParams
    │   ├── terminatedLink = false
    │   ├── txEqSupport = false
    │   ├── txVswingCode = 0x1
    │   ├── rxClockModeSupport = 0x0
    │   ├── rxClockPhaseSupport = 0x0
    │   ├── packageType = Standard
    │   ├── tcmSupport = false
    │   └── tarrSupport = false
    ├── numModules = 1
    ├── includeRegNode = true
    ├── includeInterruptNode = true
    └── 其他字段使用 UcieRegParams 默认值
```

树中相同的接口参数需要保持一致；后面的 `validate()` 会检查这三处跨模块关系。`afe`、`layer` 和其余寄存器字段的具体配置可继续查看各自的参数定义。

---

## 一、三个功能模块的参数

### 1.1 ProtocolTopParams

```scala
case class ProtocolTopParams(
  fdi: FdiParams,
  layer: ProtocolLayerParams = ProtocolLayerParams()
)
```

`fdi` 决定协议层 FDI 接口的主带字节数和配置通道位宽；`layer` 是协议层自身的配置，未指定时使用 `ProtocolLayerParams()`。`fdi` 没有默认值，必须由调用方提供。

### 1.2 AdapterTopParams

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| fdi | FdiParams | 与协议层相连的 FDI 参数 |
| rdi | RdiParams | 与 logical PHY 相连的 RDI 参数 |
| sideband | SidebandParams | adapter 的旁带配置，默认 `new SidebandParams()` |

### 1.3 LogicalPhyTopParams

| 字段 | 默认值 | 作用 |
| --- | --- | --- |
| afe | `AfeParams()` | 模拟前端相关接口配置 |
| sideband | `new SidebandParams()` | 旁带配置 |
| rdi | 无 | 与 adapter 相连的 RDI 参数 |
| retryW | 10 | 训练重试相关计数/配置的位宽 |
| desTimeoutCycles | 512 | 解串相关超时周期配置 |
| queueDepths | `SidebandPriorityQueueDepths()` | 旁带优先级队列深度 |
| sim | false | logical PHY 的仿真配置开关 |

旁带默认参数包括 `sbNodeMsgWidth = 128`、`maxCrd = 32`、`sbLinkWidth = 1`；默认四类优先级队列深度均为 32。具体如何使用这些值，应继续查看对应模块。

## 二、顶层参数和寄存器默认值

`UcieDigitalTopParams` 汇总 `protocol`、`adapter`、`logPhy` 和 `regs`。前三项必须提供；`regs` 默认创建 `UcieRegParams`，其中明确指定：

- `terminatedLink = false`、`txEqSupport = false`；
- `txVswingCode = 0x1`、`rxClockModeSupport = 0x0`、`rxClockPhaseSupport = 0x0`；
- `numModules = 1`、`includeInterruptNode = true`。

其余寄存器参数沿用 `UcieRegParams` 的默认值，例如 `includeRegNode = true`。注意这里顶层覆盖了 `UcieRegParams` 自身的 `includeInterruptNode = false` 默认值。能力字段描述寄存器配置，不能单凭这些值推断整个 PHY 的实际功能。

## 三、validate：检查接口一致性

```scala
require(protocol.fdi == adapter.fdi, ...)
require(adapter.rdi == logPhy.rdi, ...)
require(adapter.sideband == logPhy.sideband, ...)
this
```

三个 `require` 分别检查协议层和 adapter 的 FDI、adapter 和 PHY 的 RDI，以及两者的旁带参数。它们比较 case class 的整个值，而不只是某一个宽度。不匹配时生成过程抛出异常；通过后返回原参数对象，便于链式调用。

这三个检查没有要求 FDI 与 RDI 互相相等，也没有涵盖所有可能的跨模块约束。顶层还另行检查中断节点不能脱离内部寄存器节点存在；`UcieRegParams` 本身也有合法性检查。

## 四、两种构造方式

### 4.1 default()

```scala
val explicitFdi = FdiParams(nBytes = 64, ncWidth = 32)
val explicitRdi = RdiParams(nBytes = 64, ncWidth = 32)
```

默认构造把同一组 FDI 参数分别交给 protocol 和 adapter，把同一组 RDI 参数交给 adapter 和 logPhy，最后调用 `validate()`。因此默认主带数据宽度是 `64 × 8 = 512` bit，配置通道宽度是 32 bit。两种接口虽数值相同，仍是不同类型。

### 4.2 withSharedInterfaces(...)

这个辅助方法接收调用方提供的 `fdi`、`rdi`，并将共享的 `sideband` 同时传给 adapter 和 logPhy；还可设置协议层、AFE、重试位宽、超时和队列深度。构造完成后同样调用 `validate()`。

该方法没有 `regs` 或 `sim` 参数，因此使用顶层默认寄存器配置和 `sim = false`。需要自定义时可以直接构造参数，或对返回的 case class 使用 `copy`，随后重新调用 `validate()`。

## 五、阅读时抓住的关系

```text
protocol.fdi == adapter.fdi
adapter.rdi  == logPhy.rdi
adapter.sideband == logPhy.sideband
```

接下来可读 FDI、RDI Bundle，了解 `nBytes`、`ncWidth` 如何变成实际端口宽度，再看顶层如何实例化并连接各模块。
