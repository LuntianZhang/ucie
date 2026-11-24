/*
  Description:
    Handles protocol-layer FDI control handshakes and tracks the negotiated runtime state.
 */

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._
import edu.berkeley.cs.uciedigital.interfaces._

// 控制器接收 FDI 状态及收发模块反馈，输出请求、应答和运行状态。
class ProtocolStateControllerIO() extends Bundle {
  val ctrl = new ProtocolLayerCtrlIO()
  val fdi = new Bundle {
    val plStateSts = Input(FDIState())
    val plInbandPres = Input(Bool())
    val plRxActiveReq = Input(Bool())
    val plProtocol = Input(FDIProtocol())
    val plProtocolFlitFmt = Input(FDIFlitFormat())
    val plProtocolVld = Input(Bool())
    val plStallReq = Input(Bool())
    val plClkReq = Input(Bool())
    val lpStateReq = Output(FDIStateReq())
    val lpLinkError = Output(Bool())
    val lpStallAck = Output(Bool())
    val lpWakeReq = Output(Bool())
    val lpClkAck = Output(Bool())
    val lpRxActiveSts = Output(Bool())
  }
  val txIdle = Input(Bool())
  val rxReadyForActive = Input(Bool())
  val clearRuntimeState = Output(Bool())
  val status = new ProtocolLayerStatusIO()
}

class ProtocolStateController() extends Module {
  val io = IO(new ProtocolStateControllerIO())

  // 协商结果和 stall 应答保存在寄存器中，复位后协商有效位为低。
  // 默认协议和格式只有在 negotiatedValidReg 为高时才作为有效结果读取。
  val negotiatedProtocolReg = RegInit(FDIProtocol.pcieNoManagementTransport)
  val negotiatedFlitFormatReg = RegInit(FDIFlitFormat.rawFormat)
  val negotiatedValidReg = RegInit(false.B)
  val stallAckReg = RegInit(false.B)

  // Adapter 报告带内连接消失时，在时钟沿清除有效位，保留上次的协议和格式。
  // RESET 阶段同时满足带内连接存在和协商有效时，在时钟沿锁存两个协商值
  // 并置位有效位。顶层通过 status 将这些寄存器提供给芯片侧。
  when(!io.fdi.plInbandPres) {
    negotiatedValidReg := false.B
  }.elsewhen(
    (io.fdi.plStateSts === FDIState.reset) &&
      io.fdi.plInbandPres &&
      io.fdi.plProtocolVld
  ) {
    negotiatedProtocolReg := io.fdi.plProtocol
    negotiatedFlitFormatReg := io.fdi.plProtocolFlitFmt
    negotiatedValidReg := true.B
  }

  // The adapter only edge-detects nop->active in FDI_BRINGUP, which this marks.
  val fdiInBringupWindow =
    (io.fdi.plStateSts === FDIState.reset) && io.fdi.plInbandPres
  // Leaving linkError, disabled or linkReset needs the active level, not an edge.
  val fdiNeedsActiveToRecover =
    (io.fdi.plStateSts === FDIState.linkError) ||
      (io.fdi.plStateSts === FDIState.disabled) ||
      (io.fdi.plStateSts === FDIState.linkReset)

  // 芯片侧可以同时提出多个链路请求。这里用组合逻辑按禁用、链路复位、
  // 重训、激活的顺序仲裁，默认输出 nop。
  // ACTIVE 仅在 FDI 启动窗口或 linkError、disabled、linkReset 恢复时发出；
  // Adapter 完成自身握手后才通过 plStateSts 报告状态变化。
  val requestedState = WireDefault(FDIStateReq.nop)
  when(io.ctrl.requestDisable) {
    requestedState := FDIStateReq.disabled
  }.elsewhen(io.ctrl.requestLinkReset) {
    requestedState := FDIStateReq.linkReset
  }.elsewhen(io.ctrl.requestRetrain) {
    requestedState := FDIStateReq.retrain
  }.elsewhen(
    io.ctrl.requestActive && (fdiInBringupWindow || fdiNeedsActiveToRecover)
  ) {
    // Lowest priority so a held request never masks a teardown.
    requestedState := FDIStateReq.active
  }

  // Adapter 用 plStallReq 请求 TX 暂停，顶层同时将请求送到 TX 模块。
  // TX 停止发送、清空 FIFO 后以 txIdle 报告空闲；控制器在请求和空闲
  // 同为高的时钟沿置位 stallAckReg，再通过 lpStallAck 返回应答。
  // 请求持续时应答保持，采样到请求撤销后在时钟沿清零。
  when(!io.fdi.plStallReq) {
    stallAckReg := false.B
  }.elsewhen(io.txIdle) {
    stallAckReg := true.B
  }
  // 时钟应答延迟一拍跟随适配器的时钟请求。
  val clkAckReg = RegInit(false.B)
  clkAckReg := io.fdi.plClkReq

  // Adapter 用 plRxActiveReq 请求打开 RX 接收路径。
  // RX 的 rxReadyForActive 表示 FIFO 当前可入队且没有锁存溢出。
  // 状态机等待这个反馈后，通过 lpRxActiveSts 应答 Adapter；
  // 顶层也把该应答送到 RX，作为接收数据的使能条件之一。
  object RxActiveState extends ChiselEnum {
    val sIdle, sWaitAssert, sAsserted, sWaitDeassert = Value
  }

  val rxActiveStateReg = RegInit(RxActiveState.sIdle)
  val nextRxActiveState = WireDefault(rxActiveStateReg)

  io.fdi.lpRxActiveSts := false.B

  // 状态在时钟沿更新，lpRxActiveSts 根据当前状态组合产生。
  // 已进入 sAsserted 后，即使 rxReadyForActive 变低也保持应答，
  // 直到 Adapter 撤销请求。
  switch(rxActiveStateReg) {
    // 空闲：检测到请求后，下一拍进入等待就绪状态。
    is(RxActiveState.sIdle) {
      when(io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sWaitAssert
      }
    }
    // 等待就绪：请求撤销则回到空闲，否则 RX 就绪时进入应答状态。
    is(RxActiveState.sWaitAssert) {
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sIdle
      }.elsewhen(io.rxReadyForActive) {
        nextRxActiveState := RxActiveState.sAsserted
      }
    }
    // 应答期间拉高 lpRxActiveSts，采样到请求撤销后进入等待撤销状态。
    is(RxActiveState.sAsserted) {
      io.fdi.lpRxActiveSts := true.B
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sWaitDeassert
      }
    }
    // 应答已拉低：请求保持低则回空闲，重新请求时等待 RX 就绪后应答。
    is(RxActiveState.sWaitDeassert) {
      when(!io.fdi.plRxActiveReq) {
        nextRxActiveState := RxActiveState.sIdle
      }.elsewhen(io.rxReadyForActive) {
        nextRxActiveState := RxActiveState.sAsserted
      }
    }
  }

  rxActiveStateReg := nextRxActiveState

  // 将仲裁结果和握手应答送回 Adapter。当前唤醒请求固定为高，
  // 协议层链路错误指示固定为低。
  io.fdi.lpStateReq := requestedState
  io.fdi.lpLinkError := false.B
  io.fdi.lpStallAck := stallAckReg
  io.fdi.lpWakeReq := true.B
  io.fdi.lpClkAck := clkAckReg
  // 带内连接消失或 FDI 离开 ACTIVE 时请求清除。顶层将此信号接到
  // ProtocolMainbandRx.clear，用于清空 RX FIFO 并清除其溢出标志。
  io.clearRuntimeState := !io.fdi.plInbandPres || (io.fdi.plStateSts =/= FDIState.active)

  // 向顶层提供链路、协商和 stall 状态。顶层另接 RX 的实际溢出标志。
  io.status.linkState := io.fdi.plStateSts
  io.status.negotiatedProtocolValid := negotiatedValidReg
  io.status.negotiatedProtocol := negotiatedProtocolReg
  io.status.negotiatedFlitFormat := negotiatedFlitFormatReg
  io.status.stalled := stallAckReg
  io.status.rxOverflow := false.B

  // 保存上一拍信号，供断言检查请求上升沿和协商结果稳定性。
  val prevClkReqReg = RegNext(io.fdi.plClkReq, false.B)
  val prevRxActiveReqReg = RegNext(io.fdi.plRxActiveReq, false.B)
  val prevNegotiatedValidReg = RegNext(negotiatedValidReg, false.B)
  val prevNegotiatedProtocolReg = RegNext(negotiatedProtocolReg)
  val prevNegotiatedFlitFormatReg = RegNext(negotiatedFlitFormatReg)

  // 检查请求与应答时序、stall 空闲条件及协商值是否稳定。
  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.plClkReq && !prevClkReqReg) {
        assert(
          !io.fdi.lpClkAck,
          "FATAL: lp_clk_ack must not assert in the same cycle as pl_clk_req"
        )
      }
      when(io.fdi.plRxActiveReq && !prevRxActiveReqReg) {
        assert(
          !io.fdi.lpRxActiveSts,
          "FATAL: lp_rx_active_sts must not assert in the same cycle as pl_rx_active_req"
        )
      }
      when(io.fdi.lpStallAck) {
        assert(
          io.txIdle,
          "FATAL: lp_stall_ack must only assert after TX goes idle"
        )
      }
      when(prevNegotiatedValidReg && io.fdi.plInbandPres) {
        assert(
          negotiatedProtocolReg === prevNegotiatedProtocolReg,
          "FATAL: negotiated protocol changed while pl_inband_pres remained asserted"
        )
        assert(
          negotiatedFlitFormatReg === prevNegotiatedFlitFormatReg,
          "FATAL: negotiated flit format changed while pl_inband_pres remained asserted"
        )
      }
    }
  }
}
