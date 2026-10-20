package dev.lorenzo.s200.bridge

import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.ScaleMac
import dev.lorenzo.s200.ScaleModel
import dev.lorenzo.s200.cloud.CloudScale
import dev.lorenzo.s200.cloud.Region
import dev.lorenzo.s200.cloud.ScaleSearch
import kotlin.test.*

class XiaomiLoginTest {
    private val key = BindKey.parse("00112233445566778899aabbccddeeff")!!
    private val bathroom = CloudScale(Region.DE, ScaleModel.MS113, "Bathroom", ScaleMac.parse("AA:BB:CC:DD:EE:01")!!, key, null)
    private val gym = CloudScale(Region.CN, ScaleModel.MS112, "Gym", ScaleMac.parse("AA:BB:CC:DD:EE:02")!!, key, null)

    @Test fun oneScaleIsPickedWithoutAsking() =
        assertEquals(XiaomiLogin.Picked(bathroom), ScaleSearch.Found(listOf(bathroom)).toLogin())

    @Test fun severalScalesAskWhichOne() =
        assertEquals(XiaomiLogin.Choose(listOf(bathroom, gym)), ScaleSearch.Found(listOf(bathroom, gym)).toLogin())

    @Test fun noScaleFails() { assertIs<XiaomiLogin.Failed>(ScaleSearch.NoneOnAccount.toLogin()) }
}
