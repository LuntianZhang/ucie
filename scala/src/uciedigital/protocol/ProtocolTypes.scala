/*
  Description:
    Shared protocol-layer types, parameters, and IO bundles.
 */

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.util._
import edu.berkeley.cs.uciedigital.interfaces._

// 主带 TX 和 RX FIFO 的深度，单位为数据拍。
case class ProtocolLayerParams(
    txQueueDepth: Int = 2,
    rxQueueDepth: Int = 2
)

// 一拍原始主带数据，宽度为 nBytes 个字节。
class ProtocolRawBeat(nBytes: Int) extends Bundle {
  val data = UInt((8 * nBytes).W)
}

// 芯片侧提出的链路状态请求，由状态控制器仲裁。
class ProtocolLayerCtrlIO extends Bundle {

  /** Software request. The layer decides when to drive FDIStateReq.active. */
  val requestActive = Input(Bool())
  val requestRetrain = Input(Bool())
  val requestLinkReset = Input(Bool())
  val requestDisable = Input(Bool())
}

// 向芯片侧报告链路、协商、stall 和 RX 溢出状态。
class ProtocolLayerStatusIO extends Bundle {
  val linkState = Output(FDIState())
  val negotiatedProtocolValid = Output(Bool())
  val negotiatedProtocol = Output(FDIProtocol())
  val negotiatedFlitFormat = Output(FDIFlitFormat())
  val stalled = Output(Bool())
  val rxOverflow = Output(Bool())
}

// 汇集 FDI、控制状态和芯片侧主带收发接口。
class ProtocolLayerIO(
    val params: ProtocolLayerParams,
    val fdiParams: FdiParams
) extends Bundle {
  val fdi = new Fdi(fdiParams)
  val ctrl = new ProtocolLayerCtrlIO()
  val status = new ProtocolLayerStatusIO()
  val mainbandTx = Flipped(Decoupled(new ProtocolRawBeat(fdiParams.nBytes)))
  val mainbandRx = Decoupled(new ProtocolRawBeat(fdiParams.nBytes))
}
