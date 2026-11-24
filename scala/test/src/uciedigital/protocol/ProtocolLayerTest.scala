package edu.berkeley.cs.uciedigital.protocol

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.HasSimulator.simulators.verilator
import edu.berkeley.cs.uciedigital.Utils
import chisel3.simulator.scalatest.ChiselSim
import edu.berkeley.cs.uciedigital.interfaces._
import edu.berkeley.cs.uciedigital.sideband.SidebandParams
import org.scalatest.funspec.AnyFunSpec

import scala.collection.mutable
import scala.util.Random

// 集成测试 ProtocolLayer，用测试代码模拟芯片侧与 FDI 适配器。
// poke 驱动输入，expect 检查输出，peek 读取当前值，clock.step() 推进时钟。
// simulate 为每个测试创建新的 DUT（被测模块）。
class ProtocolLayerTest extends AnyFunSpec with ChiselSim {
  // 开启 VCD 波形，供查看每个测试的握手和状态变化。
  implicit val sim: HasSimulator = verilator(verilatorSettings = Utils.verilatorSettings)

  // 固定随机种子，使随机测试失败时可以重现相同激励。
  private val seed = 0x5eedL

  // 初始化测试中使用的控制、FDI 和芯片侧输入，初始时链路处于 RESET。
  private def initDut(dut: ProtocolLayer): Unit = {
    dut.io.ctrl.requestRetrain.poke(false.B)
    dut.io.ctrl.requestLinkReset.poke(false.B)
    dut.io.ctrl.requestDisable.poke(false.B)

    dut.io.fdi.lclk.poke(false.B)
    dut.io.fdi.plTrdy.poke(false.B)
    dut.io.fdi.plValid.poke(false.B)
    dut.io.fdi.plData.poke(0.U)
    dut.io.fdi.plStateSts.poke(FDIState.reset)
    dut.io.fdi.plInbandPres.poke(false.B)
    dut.io.fdi.plRxActiveReq.poke(false.B)
    dut.io.fdi.plError.poke(false.B)
    dut.io.fdi.plCError.poke(false.B)
    dut.io.fdi.plNfError.poke(false.B)
    dut.io.fdi.plTrainError.poke(false.B)
    dut.io.fdi.plPhyInRecenter.poke(false.B)
    dut.io.fdi.plProtocol.poke(FDIProtocol.streamingNoManagementTransport)
    dut.io.fdi.plProtocolFlitFmt.poke(FDIFlitFormat.rawFormat)
    dut.io.fdi.plProtocolVld.poke(false.B)
    dut.io.fdi.plStallReq.poke(false.B)
    dut.io.fdi.plSpeedmode.poke(SpeedMode.speed16)
    dut.io.fdi.plMaxSpeedmode.poke(false.B)
    dut.io.fdi.plLnkCfg.poke(LinkWidth.x16)
    dut.io.fdi.plClkReq.poke(false.B)
    dut.io.fdi.plWakeAck.poke(false.B)
    dut.io.fdi.plCfg.poke(0.U)
    dut.io.fdi.plCfgVld.poke(false.B)
    dut.io.fdi.lpCfgCrd.poke(false.B)

    dut.io.mainbandTx.valid.poke(false.B)
    dut.io.mainbandTx.bits.data.poke(0.U)
    dut.io.mainbandRx.ready.poke(false.B)
  }

  // 等待 RX 激活应答，最多等待 maxCycles 拍，超时后 expect 会使测试失败。
  private def waitForRxActive(dut: ProtocolLayer, maxCycles: Int = 8): Unit = {
    var cycles = 0
    while (!dut.io.fdi.lpRxActiveSts.peekBoolean() && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.fdi.lpRxActiveSts.expect(true.B)
  }

  // 模拟 Adapter 在 RESET 阶段报告 Streaming 和 Raw 格式，并检查锁存结果。
  private def negotiateStreamingRaw(dut: ProtocolLayer): Unit = {
    dut.io.fdi.plProtocol.poke(FDIProtocol.streamingNoManagementTransport)
    dut.io.fdi.plProtocolFlitFmt.poke(FDIFlitFormat.rawFormat)
    dut.io.fdi.plInbandPres.poke(true.B)
    dut.io.fdi.plProtocolVld.poke(true.B)
    dut.clock.step()

    dut.io.status.negotiatedProtocolValid.expect(true.B)
    dut.io.status.negotiatedProtocol
      .expect(FDIProtocol.streamingNoManagementTransport)
    dut.io.status.negotiatedFlitFormat.expect(FDIFlitFormat.rawFormat)
    dut.io.fdi.lpStateReq.expect(FDIStateReq.nop)
  }

  // 模拟时钟请求、协议协商和链路进入 ACTIVE，可选择是否请求 RX 激活。
  private def enterStreamingRawActive(
      dut: ProtocolLayer,
      requestRxActive: Boolean = true,
      plTrdy: Boolean = true
  ): Unit = {
    dut.clock.step()

    dut.io.fdi.lpWakeReq.expect(true.B)
    dut.io.fdi.lpClkAck.expect(false.B)

    dut.io.fdi.plClkReq.poke(true.B)
    dut.clock.step()
    dut.io.fdi.lpClkAck.expect(true.B)

    dut.io.fdi.plWakeAck.poke(true.B)
    dut.io.fdi.plTrdy.poke(plTrdy.B)
    negotiateStreamingRaw(dut)

    dut.io.fdi.plStateSts.poke(FDIState.active)
    dut.io.fdi.plRxActiveReq.poke(requestRxActive.B)
    dut.clock.step()

    if (requestRxActive) {
      dut.io.fdi.lpRxActiveSts.expect(false.B)
      waitForRxActive(dut)
    } else {
      dut.io.fdi.lpRxActiveSts.expect(false.B)
    }
  }

  // 为芯片侧 TX 提供一拍数据和有效位，数据宽度与 DUT 端口一致。
  private def pokeChipTx(
      dut: ProtocolLayer,
      data: BigInt,
      valid: Boolean
  ): Unit = {
    dut.io.mainbandTx.valid.poke(valid.B)
    dut.io.mainbandTx.bits.data
      .poke(data.U(dut.io.mainbandTx.bits.data.getWidth.W))
  }

  // 模拟 Adapter 从 FDI 接收方向送入一拍数据和有效位。
  private def pokeFdiRx(
      dut: ProtocolLayer,
      data: BigInt,
      valid: Boolean
  ): Unit = {
    dut.io.fdi.plValid.poke(valid.B)
    dut.io.fdi.plData.poke(data.U(dut.io.fdi.plData.getWidth.W))
  }

  // 启动测试检查协商值锁存、带内连接消失和时钟应答。
  describe("ProtocolLayer bringup behavior") {
    it("negotiates only the supported Streaming Raw mode") {
      simulate(new ProtocolLayer(sbParams = new SidebandParams())) { dut =>
        enableWaves()
        initDut(dut)
        dut.clock.step()

        dut.io.status.negotiatedProtocolValid.expect(false.B)
        dut.io.fdi.lpWakeReq.expect(true.B)
        dut.io.fdi.lpClkAck.expect(false.B)

        dut.io.fdi.plClkReq.poke(true.B)
        dut.io.fdi.lpClkAck.expect(false.B)
        dut.clock.step()
        dut.io.fdi.lpClkAck.expect(true.B)

        dut.io.fdi.plInbandPres.poke(true.B)
        dut.io.fdi.plProtocolVld.poke(true.B)
        // 尽管测试名称提到 Streaming，这里也检查控制器会锁存输入的 PCIe 值。
        dut.io.fdi.plProtocol.poke(FDIProtocol.pcieNoManagementTransport)
        dut.io.fdi.plProtocolFlitFmt.poke(FDIFlitFormat.rawFormat)
        dut.clock.step()

        dut.io.status.negotiatedProtocolValid.expect(true.B)
        dut.io.status.negotiatedProtocol
          .expect(FDIProtocol.pcieNoManagementTransport)
        dut.io.status.negotiatedFlitFormat.expect(FDIFlitFormat.rawFormat)
        dut.io.fdi.lpStateReq.expect(FDIStateReq.nop)
        dut.io.fdi.lpWakeReq.expect(true.B)

        dut.io.fdi.plInbandPres.poke(false.B)
        dut.io.fdi.plProtocolVld.poke(false.B)
        dut.clock.step()
        dut.io.status.negotiatedProtocolValid.expect(false.B)

        dut.io.fdi.plWakeAck.poke(true.B)
        negotiateStreamingRaw(dut)
      }
    }
  }

  describe("ProtocolLayer runtime behavior") {
    // 依次检查非 ACTIVE 禁止发送、FDI 背压保持数据以及 stall 清空队列。
    it("forwards TX only in ACTIVE and respects backpressure and stall") {
      simulate(
        new ProtocolLayer(
          params = ProtocolLayerParams(txQueueDepth = 2, rxQueueDepth = 2),
          sbParams = new SidebandParams()
        )
      ) { dut =>
        enableWaves()
        initDut(dut)
        dut.clock.step()

        pokeChipTx(dut, BigInt("11", 16), valid = true)
        dut.io.mainbandTx.ready.expect(false.B)
        dut.io.fdi.lpValid.expect(false.B)
        pokeChipTx(dut, 0, valid = false)

        enterStreamingRawActive(dut, requestRxActive = false, plTrdy = false)

        val txData = BigInt("deadbeef", 16)
        pokeChipTx(dut, txData, valid = true)
        dut.io.mainbandTx.ready.expect(true.B)
        dut.clock.step()

        pokeChipTx(dut, txData, valid = false)
        dut.io.fdi.lpValid.expect(true.B)
        dut.io.fdi.lpData.expect(txData.U(dut.io.fdi.lpData.getWidth.W))

        dut.clock.step()
        dut.io.fdi.lpValid.expect(true.B)
        dut.io.fdi.lpData.expect(txData.U(dut.io.fdi.lpData.getWidth.W))

        dut.io.fdi.plTrdy.poke(true.B)
        dut.clock.step()
        dut.clock.step()
        dut.io.fdi.lpValid.expect(false.B)

        // 先排队一拍，再发 stall 请求，检查应答和发送接口。
        val stalledBeat = BigInt("aa", 16)
        dut.io.fdi.plTrdy.poke(false.B)
        pokeChipTx(dut, stalledBeat, valid = true)
        dut.io.mainbandTx.ready.expect(true.B)
        dut.clock.step()

        pokeChipTx(dut, stalledBeat, valid = false)
        dut.io.fdi.lpValid.expect(true.B)
        dut.io.fdi.lpStallAck.expect(false.B)

        dut.io.fdi.plStallReq.poke(true.B)
        dut.io.mainbandTx.ready.expect(false.B)
        dut.io.fdi.lpStallAck.expect(false.B)
        dut.clock.step()

        dut.io.fdi.lpStallAck.expect(true.B)
        dut.io.status.stalled.expect(true.B)
        dut.io.fdi.lpValid.expect(false.B)
        dut.io.mainbandTx.ready.expect(false.B)

        dut.io.fdi.plStallReq.poke(false.B)
        dut.clock.step()
        dut.io.fdi.lpStallAck.expect(false.B)
      }
    }

    // 检查 RX 激活后接收、芯片侧背压保存数据，以及带内连接消失时清队。
    it("captures RX only after rx-active and clears runtime state cleanly") {
      simulate(
        new ProtocolLayer(
          params = ProtocolLayerParams(txQueueDepth = 2, rxQueueDepth = 2),
          sbParams = new SidebandParams()
        )
      ) { dut =>
        enableWaves()
        initDut(dut)
        enterStreamingRawActive(dut, requestRxActive = false, plTrdy = true)

        dut.io.mainbandRx.valid.expect(false.B)

        dut.io.fdi.plRxActiveReq.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpRxActiveSts.expect(false.B)
        dut.io.mainbandRx.valid.expect(false.B)
        waitForRxActive(dut)

        val rxData = BigInt("1234", 16)
        dut.io.mainbandRx.ready.poke(false.B)
        pokeFdiRx(dut, rxData, valid = true)
        dut.clock.step()

        pokeFdiRx(dut, 0, valid = false)
        dut.io.mainbandRx.valid.expect(true.B)
        dut.io.mainbandRx.bits.data
          .expect(rxData.U(dut.io.mainbandRx.bits.data.getWidth.W))

        dut.io.mainbandRx.ready.poke(true.B)
        dut.clock.step()

        dut.io.mainbandRx.ready.poke(false.B)
        // 芯片暂停接收，连续写入两拍，填满深度为 2 的 RX FIFO。
        pokeFdiRx(dut, BigInt("1", 16), valid = true)
        dut.clock.step()
        pokeFdiRx(dut, BigInt("2", 16), valid = true)
        dut.clock.step()
        dut.io.status.rxOverflow.expect(false.B)
        dut.io.mainbandRx.valid.expect(true.B)

        pokeFdiRx(dut, 0, valid = false)
        dut.io.fdi.plInbandPres.poke(false.B)
        dut.clock.step()
        dut.io.status.rxOverflow.expect(false.B)
        dut.io.mainbandRx.valid.expect(false.B)
      }
    }

    // 逐个增加控制请求，检查 disable、linkReset、retrain 的优先级。
    it("maps control requests to the expected FDI state requests") {
      simulate(new ProtocolLayer(sbParams = new SidebandParams())) { dut =>
        enableWaves()
        initDut(dut)
        dut.clock.step()

        dut.io.ctrl.requestRetrain.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.retrain)

        dut.io.ctrl.requestLinkReset.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.linkReset)

        dut.io.ctrl.requestDisable.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.disabled)

        dut.io.ctrl.requestDisable.poke(false.B)
        dut.io.ctrl.requestLinkReset.poke(false.B)
        dut.io.ctrl.requestRetrain.poke(false.B)
        dut.io.fdi.plClkReq.poke(true.B)
        dut.io.fdi.plWakeAck.poke(true.B)
        dut.clock.step()
        negotiateStreamingRaw(dut)

        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.nop)

        dut.io.ctrl.requestRetrain.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.retrain)

        dut.io.ctrl.requestLinkReset.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.linkReset)

        dut.io.ctrl.requestDisable.poke(true.B)
        dut.clock.step()
        dut.io.fdi.lpStateReq.expect(FDIStateReq.disabled)
      }
    }

    it(
      // 固定种子的随机场景混合 TX、RX、背压、stall 和时钟请求。
      s"runs a seeded constrained-random protocol layer test (seed = 0x${seed.toHexString})"
    ) {
      simulate(
        new ProtocolLayer(
          params = ProtocolLayerParams(txQueueDepth = 2, rxQueueDepth = 2),
          sbParams = new SidebandParams()
        )
      ) { dut =>
        enableWaves()
        val rand = new Random(seed)
        // 软件队列记录预期数据，后续按 FIFO 顺序与 DUT 输出比较。
        val expectedTx = mutable.Queue[BigInt]()
        val expectedRx = mutable.Queue[BigInt]()
        val txWidth = dut.io.mainbandTx.bits.data.getWidth
        val rxWidth = dut.io.fdi.plData.getWidth

        initDut(dut)
        enterStreamingRawActive(dut, requestRxActive = true, plTrdy = true)

        dut.io.fdi.plClkReq.poke(false.B)
        dut.clock.step()
        dut.io.fdi.lpClkAck.expect(false.B)

        var prevNegotiatedValid =
          dut.io.status.negotiatedProtocolValid.peekBoolean()

        // 每拍随机生成接口状态，并在推进时钟前按握手条件更新预期队列。
        for (_ <- 0 until 80) {
          val stall = rand.nextInt(8) == 0
          val plTrdy = rand.nextBoolean()
          val rxReady = rand.nextBoolean()
          val plClkReq = rand.nextBoolean()

          val offerTx = !stall && rand.nextBoolean()
          val txData = BigInt(txWidth, rand)

          // 限制 RX 同时只保留少量待接收数据，避免该随机场景产生溢出。
          val sendRx = expectedRx.isEmpty &&
            !dut.io.mainbandRx.valid.peekBoolean() &&
            (rand.nextInt(3) == 0)
          val rxData = BigInt(rxWidth, rand)

          dut.io.fdi.plStallReq.poke(stall.B)
          dut.io.fdi.plTrdy.poke(plTrdy.B)
          dut.io.fdi.plClkReq.poke(plClkReq.B)
          dut.io.mainbandRx.ready.poke(rxReady.B)
          pokeChipTx(dut, txData, offerTx)
          pokeFdiRx(dut, rxData, sendRx)

          dut.io.status.negotiatedProtocolValid.expect(true.B)
          dut.io.status.negotiatedProtocol
            .expect(FDIProtocol.streamingNoManagementTransport)
          dut.io.status.negotiatedFlitFormat.expect(FDIFlitFormat.rawFormat)
          dut.io.status.rxOverflow.expect(false.B)
          dut.io.fdi.lpWakeReq.expect(true.B)

          if (dut.io.fdi.lpStallAck.peekBoolean()) {
            dut.io.status.stalled.expect(true.B)
          }
          if (prevNegotiatedValid && dut.io.fdi.plInbandPres.peekBoolean()) {
            dut.io.status.negotiatedProtocol
              .expect(FDIProtocol.streamingNoManagementTransport)
            dut.io.status.negotiatedFlitFormat.expect(FDIFlitFormat.rawFormat)
          }
          prevNegotiatedValid =
            dut.io.status.negotiatedProtocolValid.peekBoolean()

          // TX 在 stall 时清空 FIFO，测试模型同步丢弃尚未发出的预期数据。
          if (stall) {
            expectedTx.clear()
          }

          // 芯片侧 valid 和 ready 同时为高时，这拍数据才被 TX 接收。
          if (offerTx && dut.io.mainbandTx.ready.peekBoolean()) {
            expectedTx.enqueue(txData)
          }

          // FDI valid 且 ready 时取出预期队首，检查发送顺序和内容。
          if (dut.io.fdi.lpValid.peekBoolean()) {
            dut.io.fdi.plStateSts.expect(FDIState.active)
            if (plTrdy) {
              assert(
                expectedTx.nonEmpty,
                "Observed FDI TX beat without a matching chip TX beat"
              )
              val observedTx = dut.io.fdi.lpData.peek().litValue
              assert(
                observedTx == expectedTx.dequeue(),
                s"FDI TX data mismatch: saw 0x${observedTx.toString(16)}"
              )
            }
          }

          // FDI 输入拍加入 RX 预期队列，芯片握手成功时再移除队首。
          if (sendRx) {
            expectedRx.enqueue(rxData)
          }

          if (dut.io.mainbandRx.valid.peekBoolean()) {
            assert(
              expectedRx.nonEmpty,
              "Observed chip RX beat without a matching FDI RX beat"
            )
            val observedRx = dut.io.mainbandRx.bits.data.peek().litValue
            assert(
              observedRx == expectedRx.front,
              s"Chip RX data mismatch: saw 0x${observedRx.toString(16)}"
            )
            if (rxReady) {
              expectedRx.dequeue()
            }
          }

          dut.clock.step()
        }

        dut.io.fdi.plStallReq.poke(false.B)
        dut.io.fdi.plTrdy.poke(true.B)
        dut.io.fdi.plClkReq.poke(false.B)
        dut.io.mainbandRx.ready.poke(true.B)
        pokeChipTx(dut, 0, valid = false)
        pokeFdiRx(dut, 0, valid = false)

        // 停止注入数据并打开接收端，将剩余数据取完，限时 20 拍。
        var drainCycles = 0
        while (
          (expectedTx.nonEmpty || expectedRx.nonEmpty) && drainCycles < 20
        ) {
          if (
            dut.io.fdi.lpValid.peekBoolean() && dut.io.fdi.plTrdy.peekBoolean()
          ) {
            assert(expectedTx.nonEmpty, "Unexpected extra TX beat during drain")
            val observedTx = dut.io.fdi.lpData.peek().litValue
            assert(
              observedTx == expectedTx.dequeue(),
              s"FDI TX drain mismatch: saw 0x${observedTx.toString(16)}"
            )
          }
          if (
            dut.io.mainbandRx.valid.peekBoolean() && dut.io.mainbandRx.ready
              .peekBoolean()
          ) {
            assert(expectedRx.nonEmpty, "Unexpected extra RX beat during drain")
            val observedRx = dut.io.mainbandRx.bits.data.peek().litValue
            assert(
              observedRx == expectedRx.front,
              s"Chip RX drain mismatch: saw 0x${observedRx.toString(16)}"
            )
            expectedRx.dequeue()
          }
          dut.clock.step()
          drainCycles += 1
        }

        assert(expectedTx.isEmpty, "Timed out draining expected TX beats")
        assert(expectedRx.isEmpty, "Timed out draining expected RX beats")
      }
    }
  }
}
