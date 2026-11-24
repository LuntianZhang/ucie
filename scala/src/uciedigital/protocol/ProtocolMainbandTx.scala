/*
  Description:
    Sends raw mainband beats from the chip-facing interface onto FDI when the link is active.
 */

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class ProtocolMainbandTxIO(nBytes: Int, depth: Int) extends Bundle {
  // 芯片侧输入数据和 valid，TX 返回 ready。
  val chip = Flipped(Decoupled(new ProtocolRawBeat(nBytes)))
  val fdi = new Bundle {
    val plTrdy = Input(Bool())
    val lpIrdy = Output(Bool())
    val lpValid = Output(Bool())
    val lpData = Output(UInt((8 * nBytes).W))
  }
  val active = Input(Bool())
  val stallRequested = Input(Bool())
  val txIdle = Output(Bool())
}

class ProtocolMainbandTx(nBytes: Int, depth: Int) extends Module {
  val io = IO(new ProtocolMainbandTxIO(nBytes, depth))

  // stall 时复位发送 FIFO，丢弃尚未发出的数据。
  val flushQueue = WireDefault(false.B)
  val queue = withReset(reset.asBool || flushQueue) {
    Module(new Queue(new ProtocolRawBeat(nBytes), depth, pipe = true))
  }

  // 链路为 ACTIVE 且无 stall 时，芯片侧才能向 FIFO 入队。
  val ingressBlocked = io.stallRequested || !io.active
  io.chip.ready := queue.io.enq.ready && !ingressBlocked
  queue.io.enq.valid := io.chip.valid && !ingressBlocked
  queue.io.enq.bits := io.chip.bits

  // 只有发送允许、队首有效且 FDI 就绪时，FIFO 才出队。
  val allowTransmit = io.active && !io.stallRequested

  io.fdi.lpIrdy := queue.io.deq.valid && allowTransmit
  io.fdi.lpValid := queue.io.deq.valid && allowTransmit
  io.fdi.lpData := queue.io.deq.bits.data
  queue.io.deq.ready := allowTransmit && io.fdi.plTrdy

  // stall 当拍关闭发送并触发 FIFO 清空。
  when(io.stallRequested) {
    flushQueue := true.B
  }

  // 队列为空或正在清空时向状态控制器报告空闲。
  io.txIdle := !queue.io.deq.valid || flushQueue

  // 检查出现有效发送数据时链路处于 ACTIVE。
  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.lpValid) {
        assert(
          io.active,
          "FATAL: ProtocolMainbandTx presented TX data while FDI was not ACTIVE"
        )
      }
    }
  }
}
