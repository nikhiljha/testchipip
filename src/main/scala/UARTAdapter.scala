package testchipip

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam}

import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.diplomacy._

import sifive.blocks.devices.uart._
import testchipip.{SerialIO}

object UARTAdapterConsts {
  val DATA_WIDTH = 8
}
import UARTAdapterConsts._

/**
 * Module to connect with a DUT UART and converts the UART signal to/from DATA_WIDTH
 * packets.
 *
 * @param uartno the uart number
 * @param div the divisor (equal to the clock frequency divided by the baud rate)
 */
class UARTAdapter(uartno: Int, div: Int) extends Module
{
  val io = IO(new Bundle {
    val uart = Flipped(new UARTPortIO(UARTParams(address = 0))) // We do not support the four wire variant
  })

  val sim = Module(new SimUART(uartno))

  val uartParams = UARTParams(0)
  val txm = Module(new UARTRx(uartParams))
  val txq = Module(new Queue(UInt(uartParams.dataBits.W), uartParams.nTxEntries))
  val rxm = Module(new UARTTx(uartParams))
  val rxq = Module(new Queue(UInt(uartParams.dataBits.W), uartParams.nRxEntries))

  sim.io.clock := clock
  sim.io.reset := reset.asBool

  txm.io.en := true.B
  txm.io.in := io.uart.txd
  txm.io.div := div.U
  txq.io.enq.valid := txm.io.out.valid
  txq.io.enq.bits := txm.io.out.bits
  when (txq.io.enq.valid) { assert(txq.io.enq.ready) }

  rxm.io.en := true.B
  rxm.io.in <> rxq.io.deq
  rxm.io.div := div.U
  rxm.io.nstop := 0.U
  io.uart.rxd := rxm.io.out

  sim.io.serial.out.bits := txq.io.deq.bits
  sim.io.serial.out.valid := txq.io.deq.valid
  txq.io.deq.ready := sim.io.serial.out.ready

  rxq.io.enq.bits := sim.io.serial.in.bits
  rxq.io.enq.valid := sim.io.serial.in.valid
  sim.io.serial.in.ready := rxq.io.enq.ready && rxq.io.count < (uartParams.nRxEntries - 1).U
}

object UARTAdapter {
  def connect(uart: Seq[UARTPortIO], baudrate: BigInt = 115200)(implicit p: Parameters) {
    UARTAdapter.connect(uart, baudrate, p(PeripheryBusKey).dtsFrequency.get)
  }
  def connect(uart: Seq[UARTPortIO], baudrate: BigInt, clockFrequency: BigInt) {
    val div = (clockFrequency / baudrate).toInt
    UARTAdapter.connect(uart, div)
  }
  def connect(uart: Seq[UARTPortIO], div: Int) {
    uart.zipWithIndex.foreach { case (dut_io, i) =>
      val uart_sim = Module(new UARTAdapter(i, div))
      uart_sim.suggestName(s"uart_sim_${i}")
      uart_sim.io.uart.txd := dut_io.txd
      dut_io.rxd := uart_sim.io.uart.rxd
    }
  }
}

/**
 * Module to connect to a *.v blackbox that uses DPI calls to interact with the DUT UART.
 *
 * @param uartno the uart number
 */
class SimUART(uartno: Int) extends BlackBox(Map("UARTNO" -> IntParam(uartno))) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val serial = Flipped(new SerialIO(DATA_WIDTH))
  })

  addResource("/testchipip/vsrc/SimUART.v")
  addResource("/testchipip/csrc/SimUART.cc")
  addResource("/testchipip/csrc/uart.cc")
  addResource("/testchipip/csrc/uart.h")
}

class UARTToSerial(freq: BigInt, uartParams: UARTParams) extends Module {
  val io = IO(new Bundle {
    val uart = new UARTPortIO(uartParams)
    val serial = new SerialIO(8)
    val dropped = Output(Bool()) // No flow control, so dropping a beat means we're screwed
  })

  val rxm = Module(new UARTRx(uartParams))
  val rxq = Module(new Queue(UInt(uartParams.dataBits.W), uartParams.nRxEntries))
  val txm = Module(new UARTTx(uartParams))
  val txq = Module(new Queue(UInt(uartParams.dataBits.W), uartParams.nTxEntries))

  val div = (freq / uartParams.initBaudRate).toInt

  val dropped = RegInit(false.B)
  io.dropped := dropped
  rxm.io.en := true.B
  rxm.io.in := io.uart.rxd
  rxm.io.div := div.U
  rxq.io.enq.valid := rxm.io.out.valid
  rxq.io.enq.bits := rxm.io.out.bits
  when (rxq.io.enq.valid) { assert(rxq.io.enq.ready) }
  when (rxq.io.enq.valid && !rxq.io.enq.ready) { dropped := true.B } // no flow control
  dontTouch(rxm.io)

  txm.io.en := true.B
  txm.io.in <> txq.io.deq
  txm.io.div := div.U
  txm.io.nstop := 0.U
  io.uart.txd := txm.io.out
  dontTouch(txm.io)

  io.serial.out <> rxq.io.deq
  txq.io.enq <> io.serial.in
}
