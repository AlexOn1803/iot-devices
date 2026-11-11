package ua.madeisy.iot.esp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class PosixTzTest {
    @Test
    fun kyiv() = assertEquals("STD-2DST-3,M3.5.0/3,M10.5.0/4", PosixTz.of(ZoneId.of("Europe/Kiev")))

    @Test
    fun newYork() = assertEquals("STD5DST4,M3.2.0/2,M11.1.0/2", PosixTz.of(ZoneId.of("America/New_York")))

    @Test
    fun fixedOffsets() {
        assertEquals("STD-5:30", PosixTz.of(ZoneId.of("Asia/Kolkata")))
        assertEquals("STD0", PosixTz.of(ZoneId.of("UTC")))
    }

    @Test
    fun scheduleRoundTrip() {
        val c = EspCompConfig(EspType.THERMO, "Т", sensor = 0, target = 1, sched = SchedPeriod.template() + SchedPeriod(0x7F, 0, off = true))
        assertEquals(c.sched, EspCompConfig.fromJson(JSONObject(c.toJson().toString())).sched)
        assertEquals("Пн–Пт", SchedPeriod.daysText(SchedPeriod.WORKDAYS))
        assertEquals("Сб 08:00", SchedPeriod.weekKeyText(5 * 1440 + 480))
    }
}
