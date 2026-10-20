package dev.lorenzo.s200.cloud

import dev.lorenzo.s200.BindKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CloudJsonTest {
    @Test fun qrLoginReadsUrlsBehindThePrefix() = assertEquals(
        QrLogin("https://account.xiaomi.com/longPolling/login?ticket=t", "https://lp.account.xiaomi.com/lp/s?k=t", 300),
        CloudJson.qrLogin("""&&&START&&&{"loginUrl":"https://account.xiaomi.com/longPolling/login?ticket=t","lp":"https://lp.account.xiaomi.com/lp/s?k=t","timeout":300,"code":0}"""),
    )

    @Test fun loginTicketNeedsSsecurityAndLocation() {
        assertEquals(
            LoginTicket("12345", "c2VjcmV0c2VjcmV0", "https://sts.api.io.mi.com/sts?x=1"),
            CloudJson.loginTicket("""&&&START&&&{"userId":12345,"ssecurity":"c2VjcmV0c2VjcmV0","location":"https://sts.api.io.mi.com/sts?x=1","code":0}"""),
        )
        assertFailsWith<XiaomiCloudException> { CloudJson.loginTicket("""{"code":70016,"desc":"expired"}""") }
    }

    @Test fun homesMergeOwnAndShared() = assertEquals(
        listOf(Home("111", "9"), Home("222", "77")),
        CloudJson.homes(
            """{"result":{"homelist":[{"id":"111"}]}}""",
            """{"result":{"share":{"share_family":[{"home_id":222,"home_owner":77}]}}}""",
            "9",
        ),
    )

    @Test fun homesToleratesMissingLists() = assertEquals(emptyList(), CloudJson.homes("""{"code":0}""", """{"result":{}}""", "9"))

    @Test fun scaleDevicesKeepOnlyScales() = assertEquals(
        listOf(listOf("blt.4.abc", "Bathroom scale", "AA:BB:CC:DD:EE:01", "0123456789abcdef01234567")),
        CloudJson.scaleDevices("""{"result":{"device_info":[
            {"did":"blt.4.abc","name":"Bathroom scale","mac":"aa:bb:cc:dd:ee:01","token":"0123456789abcdef01234567","model":"xiaomi.scales.ms113"},
            {"did":"blt.4.def","name":"Lamp","mac":"AA:BB:CC:DD:EE:02","token":"0123456789abcdef01234567","model":"yeelink.light.x"}
        ]}}""").map { listOf(it.did, it.name, it.mac.address, it.token?.hex) },
    )

    @Test fun scaleDevicesAcceptEveryS200VariantAndNoOtherScale() {
        val models = listOf("yunmai.scales.ms106", "xiaomi.scales.ms111", "xiaomi.scales.ms112", "xiaomi.scales.ms113", "xiaomi.scales.ms114",
            "yunmai.scales.ms104", "xiaomi.scales.ms115", "xiaomi.scales.ms116", "yunmai.scales.ms113")
        val json = models.mapIndexed { i, m -> """{"did":"d$i","mac":"AA:BB:CC:DD:EE:0$i","model":"$m"}""" }.joinToString(",")
        assertEquals(models.take(5), CloudJson.scaleDevices("""{"result":{"device_info":[$json]}}""").map { it.model.model })
    }

    @Test fun scaleWithoutTokenStillCounts() =
        assertNull(CloudJson.scaleDevices("""{"result":{"device_info":[{"did":"d","mac":"AA:BB:CC:DD:EE:01","model":"xiaomi.scales.ms113"}]}}""").single().token)

    @Test fun beaconKeyParsesOrNull() {
        assertEquals(BindKey.parse("00112233445566778899aabbccddeeff")?.hex, CloudJson.beaconKey("""{"result":{"beaconkey":"00112233445566778899aabbccddeeff"}}""")?.hex)
        assertNull(CloudJson.beaconKey("""{"code":-1}"""))
    }
}
