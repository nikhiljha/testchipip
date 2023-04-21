package testchipip

import chisel3._
import chisel3.experimental.{IO}
import org.chipsalliance.cde.config.{Config, Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.prci._


case class BootAddrRegParams(
  defaultBootAddress: BigInt = 0x80000000L, // This should be DRAM_BASE
  bootRegAddress: BigInt = 0x4000,
  slaveWhere: TLBusWrapperLocation = PBUS
)
case object BootAddrRegKey extends Field[Option[BootAddrRegParams]](Some(BootAddrRegParams()))

class WithNoBootAddrReg extends Config((site, here, up) => {
  case BootAddrRegKey => None
})

trait CanHavePeripheryBootAddrReg { this: BaseSubsystem =>
  p(BootAddrRegKey).map { params =>
    val tlbus = locateTLBusWrapper(params.slaveWhere)

    val device = new SimpleDevice("boot-address-reg", Nil)

    tlbus {
      val node = TLRegisterNode(Seq(AddressSet(params.bootRegAddress, 4096-1)), device, "reg/control", beatBytes=tlbus.beatBytes)
      tlbus.toVariableWidthSlave(Some("boot-address-reg")) { node }
      InModuleBody {
        val bootAddrReg = RegInit(params.defaultBootAddress.U(p(XLen).W))
        node.regmap(0 -> RegField.bytes(bootAddrReg))
      }
    }
  }
}
