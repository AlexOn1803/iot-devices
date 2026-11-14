package ua.madeisy.iot.inverter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartEssTest {
    // еталон порахований Python (hashlib + urllib.parse.quote_plus), як у відомих клієнтах DessMonitor
    @Test
    fun authSignature() {
        val act = EssSign.actionString(
            "authSource",
            listOf(
                "usr" to "demo user", "company-key" to "bnrl_frRFjEz8Mkn", "source" to "1",
                "_app_client_" to "web", "_app_id_" to "ua.madeisy.iot", "_app_version_" to "1.0",
            ),
        )
        assertEquals(
            "&action=authSource&usr=demo+user&company-key=bnrl_frRFjEz8Mkn&source=1&_app_client_=web&_app_id_=ua.madeisy.iot&_app_version_=1.0",
            act,
        )
        assertEquals("1a155d934b9fc2b7c3d8ea699419ae35898b9589", EssSign.sign("1700000000000", act, EssSign.sha1("secret pass"), null))
    }

    @Test
    fun tokenSignatureAndUrl() {
        val act = EssSign.actionString(
            "queryDeviceLastData",
            listOf("pn" to "W0012345678901", "devcode" to 2477, "devaddr" to 1, "sn" to "W0012345678901094801", "i18n" to "en"),
        )
        val t = EssToken("TOKEN", "SECRET", Long.MAX_VALUE)
        val sign = EssSign.sign("1700000000000", act, null, t)
        assertEquals("4f70359db5e855e855dcbfce76bae3b9a1f71e32", sign)
        assertEquals(
            "https://api.dessmonitor.com/public/?sign=$sign&salt=1700000000000&token=TOKEN$act",
            EssSign.url(EssServer.DESS.base, "1700000000000", sign, t, act),
        )
    }

    @Test
    fun summaryHybridInverter() {
        val pts = SmartEssClient.parsePoints(JSONArray("""[
            {"title":"Working State","unit":"","val":"Battery Mode"},
            {"title":"AC Input Voltage","unit":"V","val":"0.0"},
            {"title":"PV1 Input Power","unit":"W","val":"850"},
            {"title":"PV2 input power","unit":"W","val":"400"},
            {"title":"Battery Voltage","unit":"V","val":"52.0"},
            {"title":"Battery Capacity","unit":"%","val":"76"},
            {"title":"Battery Charging Current","unit":"A","val":"10"},
            {"title":"Battery Discharge Current","unit":"A","val":"0"},
            {"title":"Output Active Power","unit":"W","val":"710"},
            {"title":"AC Output Load","unit":"%","val":"14"}
        ]"""))
        val s = EssSummary.of(pts)
        assertEquals(1250.0, s.pvW!!, 0.01)
        assertEquals(76.0, s.socPct!!, 0.01)
        assertEquals(520.0, s.batteryW!!, 0.01)
        assertEquals(710.0, s.loadW!!, 0.01)
        assertEquals(14.0, s.loadPct!!, 0.01)
        assertEquals(false, s.gridPresent)
        assertEquals("Від батареї", EssSummary.modeUa(s.mode!!))
        assertEquals("Заряд батареї", EssSummary.titleUa("Battery  Capacity"))
    }

    @Test
    fun summaryPvTotalAndKw() {
        val s = EssSummary.of(SmartEssClient.parsePoints(JSONArray("""[
            {"title":"PV total Power","unit":"kW","val":"1.5"},
            {"title":"PV1 Input Power","unit":"W","val":"900"},
            {"title":"Grid Voltage","unit":"V","val":"229.1"}
        ]""")))
        assertEquals(1500.0, s.pvW!!, 0.01)
        assertEquals(true, s.gridPresent)
        assertNull(s.socPct)
    }
}
