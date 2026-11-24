/*
  Description:
    Receives raw mainband beats from FDI and buffers them for the chip-facing interface.
 */

package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.layer.block
import chisel3.layers.Verification
import chisel3.util._

class ProtocolMainbandRxIO(nBytes: Int, depth: Int) extends Bundle {
  // FDI 接收有效位和数据。chip 向芯片侧输出数据及 valid，接收 ready。
  val fdi = new Bundle {
    val plValid = Input(Bool())
    val plData = Input(UInt((8 * nBytes).W))
  }
  val chip = Decoupled(new ProtocolRawBeat(nBytes))
  val active = Input(Bool())
  val rxPathActive = Input(Bool())
  val clear = Input(Bool())
  val rxReadyForActive = Output(Bool())
  val rxOverflow = Output(Bool())
}

class ProtocolMainbandRx(nBytes: Int, depth: Int) extends Module {
  val io = IO(new ProtocolMainbandRxIO(nBytes, depth))

  // FIFO 缓存接收数据。复位或 clear 时清空，满队列可同拍出队和入队。
  val queue = withReset(reset.asBool || io.clear) {
    Module(new Queue(new ProtocolRawBeat(nBytes), depth, pipe = true))
  }
  val rxOverflowReg = RegInit(
    false.B
  ) // Can't overflow; no spec-defined protocol backpressure

  // 链路和 RX 路径都处于激活状态时，才允许接收 FDI 数据。
  val captureEnabled = io.active && io.rxPathActive
  val rxBeat = Wire(new ProtocolRawBeat(nBytes))
  rxBeat.data := io.fdi.plData

  // 有效且允许接收的数据尝试入队，芯片侧通过 Decoupled 握手出队。
  queue.io.enq.valid := io.fdi.plValid && captureEnabled
  queue.io.enq.bits := rxBeat
  io.chip <> queue.io.deq

  // clear 优先清除粘滞溢出标志。有效数据到达而 FIFO 无法入队时，
  // 当前拍丢失并置位标志，标志保持到复位或 clear。
  when(io.clear) {
    rxOverflowReg := false.B
  }.elsewhen(io.fdi.plValid && captureEnabled && !queue.io.enq.ready) {
    rxOverflowReg := true.B
  }

  // FIFO 可入队且没有锁存溢出时，向状态控制器报告 RX 可激活。
  io.rxReadyForActive := queue.io.enq.ready && !rxOverflowReg
  io.rxOverflow := rxOverflowReg

  // 检查 FDI 数据只在接收路径激活时到达，并检查缓冲未发生溢出。
  block(Verification) {
    block(Verification.Assert) {
      when(io.fdi.plValid) {
        assert(
          captureEnabled,
          "FATAL: ProtocolMainbandRx observed payload data while RX path was not ACTIVE"
        )
      }
      assert(!rxOverflowReg, "FATAL: ProtocolMainbandRx overflowed its buffer")
    }
  }
}
