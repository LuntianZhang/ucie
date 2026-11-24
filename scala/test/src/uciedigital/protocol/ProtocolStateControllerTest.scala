package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import edu.berkeley.cs.uciedigital.Utils
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import org.scalatest.funspec.AnyFunSpec

// 单独实例化状态控制器，用测试代码模拟 Adapter、TX、RX 和芯片侧输入。
// poke 给输入赋值，expect 检查输出，clock.step() 推进一个时钟周期。
// 每个 simulate 都创建独立的 DUT（被测模块）。
class ProtocolStateControllerTest extends AnyFunSpec with ChiselSim {
  // 开启 VCD 波形，供查看每个测试的握手和状态变化。
  implicit val sim: HasSimulator = verilator(verilatorSettings = Utils.verilatorSettings)

  // 将请求和握手输入设为初始状态，并推进一拍。协议字段预先提供测试值，
  // 此时 plProtocolVld 为低，因此控制器还不会锁存这些值。
  private def initialize(dut: ProtocolStateController): Unit = {
    val io = dut.io
    io.ctrl.requestActive.poke(false.B)
    io.ctrl.requestRetrain.poke(false.B)
    io.ctrl.requestLinkReset.poke(false.B)
    io.ctrl.requestDisable.poke(false.B)
    io.fdi.plStateSts.poke(FDIState.reset)
    io.fdi.plInbandPres.poke(false.B)
    io.fdi.plRxActiveReq.poke(false.B)
    io.fdi.plProtocol.poke(FDIProtocol.streamingNoManagementTransport)
    io.fdi.plProtocolFlitFmt.poke(FDIFlitFormat.format68B)
    io.fdi.plProtocolVld.poke(false.B)
    io.fdi.plStallReq.poke(false.B)
    io.fdi.plClkReq.poke(false.B)
    io.txIdle.poke(false.B)
    io.rxReadyForActive.poke(false.B)
    dut.clock.step()
  }

  // 验证请求优先级，以及启动窗口和故障恢复状态下的 ACTIVE 请求。
  it("arbitrates link requests and permits ACTIVE during bring-up and recovery") {
    simulate(new ProtocolStateController) { dut =>
        enableWaves()
      initialize(dut)
      val io = dut.io
      io.ctrl.requestActive.poke(true.B)
      io.fdi.lpStateReq.expect(FDIStateReq.nop)
      io.fdi.plInbandPres.poke(true.B)
      io.fdi.lpStateReq.expect(FDIStateReq.active)

      // 同时提出多个请求，逐个撤销较高优先级请求并检查输出。
      io.ctrl.requestRetrain.poke(true.B)
      io.ctrl.requestLinkReset.poke(true.B)
      io.ctrl.requestDisable.poke(true.B)
      io.fdi.lpStateReq.expect(FDIStateReq.disabled)
      io.ctrl.requestDisable.poke(false.B)
      io.fdi.lpStateReq.expect(FDIStateReq.linkReset)
      io.ctrl.requestLinkReset.poke(false.B)
      io.fdi.lpStateReq.expect(FDIStateReq.retrain)
      io.ctrl.requestRetrain.poke(false.B)

      io.fdi.plStateSts.poke(FDIState.active)
      io.fdi.lpStateReq.expect(FDIStateReq.nop)
      io.fdi.plInbandPres.poke(false.B)
      // 对三种恢复状态逐一检查 ACTIVE 请求保持有效，状态输出直接反映输入。
      for (state <- Seq(FDIState.linkError, FDIState.disabled, FDIState.linkReset)) {
        io.fdi.plStateSts.poke(state)
        io.fdi.lpStateReq.expect(FDIStateReq.active)
        io.status.linkState.expect(state)
        dut.clock.step()
        io.fdi.lpStateReq.expect(FDIStateReq.active)
      }
    }
  }

  // 验证协商值只有在 RESET、带内存在和有效位同时满足时才被保存。
  it("latches negotiation in RESET and clears validity when in-band presence drops") {
    simulate(new ProtocolStateController) { dut =>
        enableWaves()
      initialize(dut)
      val io = dut.io
      io.status.negotiatedProtocol.expect(FDIProtocol.pcieNoManagementTransport)
      io.status.negotiatedFlitFormat.expect(FDIFlitFormat.rawFormat)
      io.clearRuntimeState.expect(true.B)

      // 先只拉高有效位，带内存在仍为低，所以有效标志不能置位。
      io.fdi.plProtocolVld.poke(true.B)
      dut.clock.step()
      io.status.negotiatedProtocolValid.expect(false.B)
      io.fdi.plInbandPres.poke(true.B)
      dut.clock.step()
      io.status.negotiatedProtocolValid.expect(true.B)
      io.status.negotiatedProtocol.expect(FDIProtocol.streamingNoManagementTransport)
      io.status.negotiatedFlitFormat.expect(FDIFlitFormat.format68B)

      // 进入 ACTIVE 后修改输入，检查已经保存的协商值保持不变。
      io.fdi.plStateSts.poke(FDIState.active)
      io.clearRuntimeState.expect(false.B)
      io.fdi.plProtocol.poke(FDIProtocol.pcieNoManagementTransport)
      io.fdi.plProtocolFlitFmt.poke(FDIFlitFormat.rawFormat)
      dut.clock.step()
      io.status.negotiatedProtocol.expect(FDIProtocol.streamingNoManagementTransport)
      io.status.negotiatedFlitFormat.expect(FDIFlitFormat.format68B)
      // 带内存在消失时，清除信号立即拉高，有效位在时钟沿清零。
      io.fdi.plInbandPres.poke(false.B)
      io.clearRuntimeState.expect(true.B)
      dut.clock.step()
      io.status.negotiatedProtocolValid.expect(false.B)
      io.status.negotiatedProtocol.expect(FDIProtocol.streamingNoManagementTransport)
      io.status.negotiatedFlitFormat.expect(FDIFlitFormat.format68B)
    }
  }

  // 模拟 RX 激活请求，检查等待就绪、撤销请求和重新请求。
  it("waits for RX readiness and handles withdrawal and reassertion of the request") {
    simulate(new ProtocolStateController) { dut =>
        enableWaves()
      initialize(dut)
      val io = dut.io
      io.fdi.plRxActiveReq.poke(true.B)
      dut.clock.step(2)
      io.fdi.lpRxActiveSts.expect(false.B)
      io.fdi.plRxActiveReq.poke(false.B)
      dut.clock.step()

      // 就绪后重新请求，需要经过等待状态才能拉高应答。
      io.rxReadyForActive.poke(true.B)
      io.fdi.plRxActiveReq.poke(true.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(false.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(true.B)

      // 应答已经拉高后，就绪变低不会立即撤销应答。
      io.rxReadyForActive.poke(false.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(true.B)
      io.fdi.plRxActiveReq.poke(false.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(false.B)

      io.fdi.plRxActiveReq.poke(true.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(false.B)
      io.rxReadyForActive.poke(true.B)
      dut.clock.step()
      io.fdi.lpRxActiveSts.expect(true.B)
    }
  }

  // 检查时钟应答延迟一拍，stall 应答等待 TX 空闲后才登记。
  it("registers stall and clock acknowledgements") {
    simulate(new ProtocolStateController) { dut =>
        enableWaves()
      initialize(dut)
      val io = dut.io
      io.fdi.lpWakeReq.expect(true.B)
      io.fdi.lpLinkError.expect(false.B)
      io.status.rxOverflow.expect(false.B)
      io.fdi.plStallReq.poke(true.B)
      io.fdi.plClkReq.poke(true.B)
      io.fdi.lpClkAck.expect(false.B)
      dut.clock.step()
      io.fdi.lpClkAck.expect(true.B)
      io.fdi.lpStallAck.expect(false.B)
      io.status.stalled.expect(false.B)

      // TX 空闲后再推进一拍，检查应答与 stalled 状态同时置位。
      io.txIdle.poke(true.B)
      io.fdi.lpStallAck.expect(false.B)
      dut.clock.step()
      io.fdi.lpStallAck.expect(true.B)
      io.status.stalled.expect(true.B)
      io.fdi.plStallReq.poke(false.B)
      io.fdi.plClkReq.poke(false.B)
      io.fdi.lpStallAck.expect(true.B)
      io.fdi.lpClkAck.expect(true.B)
      dut.clock.step()
      io.fdi.lpStallAck.expect(false.B)
      io.status.stalled.expect(false.B)
      io.fdi.lpClkAck.expect(false.B)
    }
  }
}
