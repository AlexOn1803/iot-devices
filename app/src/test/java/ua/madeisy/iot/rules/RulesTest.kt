package ua.madeisy.iot.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test
    fun numbers() {
        assertTrue(RuleValues.test(6.2, Op.GE, "6"))
        assertFalse(RuleValues.test(5.9, Op.GE, "6"))
        assertTrue(RuleValues.test(0.4, Op.LE, "0,5"))
        assertFalse(RuleValues.test(null, Op.LE, "0.5"))
        assertFalse(RuleValues.test(1.0, Op.GT, "abc"))
    }

    @Test
    fun boolsAndText() {
        assertTrue(RuleValues.test(false, Op.EQ, "ні"))
        assertTrue(RuleValues.test(true, Op.EQ, "так"))
        assertTrue(RuleValues.test(true, Op.NE, "ні"))
        assertTrue(RuleValues.test("Від батареї", Op.EQ, "від батареї"))
        assertTrue(RuleValues.test("Від мережі", Op.NE, "Від батареї"))
    }

    @Test
    fun roundTrip() {
        val b = Rule(name = "Крок 2", conds = listOf(Cond("k1", "current", Op.LE, "0.5")), all = false, holdSec = 30,
            needsArm = true, actions = listOf(Action(ActType.OFF, "k1"), Action(ActType.ON, "k2", comp = 1)), fromMin = 480, toMin = 1320)
        val a = Rule(name = "Крок 1", actions = listOf(Action(ActType.ARM, ruleId = b.id)))
        val back = Rule.listFromJson(Rule.listToJson(listOf(a, b)))
        assertEquals(listOf(a, b), back)
        assertEquals(setOf("k1", "k2"), back[1].devices)
        assertEquals(b, Rule.fromJson(JSONObject(b.toJson().toString())))
    }
}
