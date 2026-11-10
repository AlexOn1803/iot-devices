package ua.madeisy.iot.esp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EspModelsTest {
    @Test
    fun tuyaComponentRoundTrip() {
        val c = EspCompConfig(
            EspType.TUYA, "Бойлер", tuyaKey = "k1", tuyaId = "bf0000000000000000abcd",
            tuyaLocalKey = "0123456789abcdef", tuyaIp = "192.168.1.50", tuyaVer = "3.4", tuyaDp = 1,
        )
        val j = c.toJson()
        // поля, які читає прошивка (main.cpp, case C_TUYA)
        assertEquals("tuya", j.getString("t"))
        assertEquals("bf0000000000000000abcd", j.getString("id"))
        assertEquals("0123456789abcdef", j.getString("key"))
        assertEquals("192.168.1.50", j.getString("ip"))
        assertEquals("3.4", j.getString("ver"))
        assertEquals(1, j.getInt("dp"))
        assertEquals(c, EspCompConfig.fromJson(JSONObject(j.toString())))
        assertTrue(c.pins.isEmpty())
    }

    @Test
    fun tuyaStateParsed() {
        val s = EspComp.fromJson(JSONObject("""{"i":2,"t":"tuya","name":"Бойлер","on":null,"online":false,"ip":"","err":"no connection (busy or offline)","pending":true}"""))
        assertEquals(EspType.TUYA, s.type)
        assertEquals(null, s.on)
        assertEquals(false, s.online)
        assertEquals(true, s.pending)
        assertTrue(s.isSwitchable)
        assertEquals(false, EspType.supportsTuya("esp8266"))
        assertEquals(true, EspType.supportsTuya("esp32c3"))
    }
}
