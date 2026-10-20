package dev.lorenzo.s200.cloud

import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.ScaleMac
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleSearchTest {
    private val key = BindKey.parse("00112233445566778899aabbccddeeff")!!
    private fun scale(region: Region, mac: String) = CloudScale(region, "S200", ScaleMac.parse(mac)!!, key, null)

    @Test fun scalesFromEveryRegionAreMergedWithTheirRegion() {
        val de = scale(Region.DE, "AA:BB:CC:DD:EE:01")
        val sg = scale(Region.SG, "AA:BB:CC:DD:EE:02")
        val results = Region.entries.associateWith { Result.success(emptyList<CloudScale>()) } +
            mapOf(Region.SG to Result.success(listOf(sg)), Region.DE to Result.success(listOf(de)))
        assertEquals(ScaleSearch.Found(listOf(de, sg)), ScaleSearch.of(results))
    }

    @Test fun aFailingRegionDoesNotHideAScaleFoundElsewhere() {
        val de = scale(Region.DE, "AA:BB:CC:DD:EE:01")
        val results = mapOf(Region.CN to Result.failure(XiaomiCloudException("HTTP 500")), Region.DE to Result.success(listOf(de)))
        assertEquals(ScaleSearch.Found(listOf(de)), ScaleSearch.of(results))
    }

    @Test fun sameScaleInTwoRegionsKeepsTheFirstRegion() {
        val results = mapOf(Region.I2 to Result.success(listOf(scale(Region.I2, "AA:BB:CC:DD:EE:01"))),
            Region.DE to Result.success(listOf(scale(Region.DE, "AA:BB:CC:DD:EE:01"))))
        assertEquals(listOf(Region.DE), (ScaleSearch.of(results) as ScaleSearch.Found).scales.map { it.region })
    }

    @Test fun noScaleWithFailuresReportsTheUnreachableRegions() = assertEquals(
        ScaleSearch.Unreachable(mapOf(Region.RU to "timeout")),
        ScaleSearch.of(mapOf(Region.DE to Result.success(emptyList()), Region.RU to Result.failure(XiaomiCloudException("timeout")))),
    )

    @Test fun noScaleAnywhere() = assertEquals(
        ScaleSearch.NoneOnAccount,
        ScaleSearch.of(Region.entries.associateWith { Result.success(emptyList()) }),
    )

    @Test fun regionHostsFollowTheExtractor() = assertEquals(
        listOf("https://api.io.mi.com/app", "https://de.api.io.mi.com/app", "https://i2.api.io.mi.com/app"),
        listOf(Region.CN, Region.DE, Region.I2).map { it.apiBase },
    )
}
