package ua.madeisy.iot.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Еталонні кадри згенеровано бібліотекою tinytuya 1.20 (див. tools/gen_vectors.py). */
class TuyaProtocolTest {
    private val key = "0123456789abcdef".toByteArray()
    private val ctrl = """{"devId":"bf0000000000000000abcd","dps":{"1":true}}""".toByteArray()

    private fun session(v: TuyaVersion, seq: Int = 1): TuyaSession {
        val s = TuyaSession(v, key)
        repeat(seq - 1) { s.encode(TuyaCmd.HEART_BEAT, ByteArray(0)) }
        return s
    }

    @Test fun encode33() = assertEquals(V.enc33, session(TuyaVersion.V33).encode(TuyaCmd.CONTROL, ctrl).toHex())
    @Test fun encode34() = assertEquals(V.enc34, session(TuyaVersion.V34).encode(TuyaCmd.CONTROL_NEW, ctrl).toHex())
    @Test fun encode35() = assertEquals(V.enc35, session(TuyaVersion.V35).encode(TuyaCmd.CONTROL_NEW, ctrl, V.iv.hexToBytes()).toHex())
    @Test fun query33() = assertEquals(V.query33, session(TuyaVersion.V33, 5).encode(TuyaCmd.DP_QUERY, "{}".toByteArray()).toHex())
    @Test fun query34() = assertEquals(V.query34, session(TuyaVersion.V34, 5).encode(TuyaCmd.DP_QUERY_NEW, "{}".toByteArray()).toHex())

    private fun decode(v: TuyaVersion, hex: String): Map<String, Any?> {
        val s = TuyaSession(v, key)
        val msg = s.parse(hex.hexToBytes())
        assertTrue("цілісність кадру", msg.integrityOk)
        assertEquals(TuyaCmd.STATUS, msg.cmd)
        val text = s.decodeText(msg)
        assertNotNull(text)
        return TuyaLocalClient.extractDps(text!!)!!
    }

    @Test fun decode33() {
        val d = decode(TuyaVersion.V33, V.dev33)
        assertEquals(true, d["1"]); assertEquals(1234, d["19"]); assertEquals(2301, d["20"]); assertEquals(567, d["18"])
    }

    @Test fun decode34() {
        val d = decode(TuyaVersion.V34, V.dev34)
        assertEquals(false, d["1"]); assertEquals(55, d["19"])
    }

    @Test fun decode35() = assertEquals(2290, decode(TuyaVersion.V35, V.dev35)["20"])

    @Test fun negotiate34() {
        val s = TuyaSession(TuyaVersion.V34, key)
        assertEquals(V.neg34start, s.negotiationStart("LOCALNONCE123456".toByteArray()).toHex())
        val step3 = s.negotiationStep3(s.parse(V.neg34resp.hexToBytes()))
        assertEquals(V.neg34step3, step3.toHex())
        s.negotiationFinalize()
        assertEquals(V.neg34session, s.key.toHex())
    }

    @Test fun negotiate35() {
        val s = TuyaSession(TuyaVersion.V35, key)
        s.negotiationStart("LOCALNONCE123456".toByteArray())
        val step3 = s.negotiationStep3(s.parse(V.neg35resp.hexToBytes()))
        // перевіряємо вміст кроку 3: розшифровуємо власний кадр
        val m = TuyaFrame.unpack(step3, key)
        assertTrue(m.integrityOk)
        assertEquals(V.neg35step3payload, m.payload.toHex())
        s.negotiationFinalize()
        assertEquals(V.neg35session, s.key.toHex())
    }

    @Test fun udpDiscovery() {
        val t = TuyaDiscovery.decodePacket(V.udp.hexToBytes())!!
        val d = TuyaDiscovery.parse(t, "0.0.0.0")!!
        assertEquals("192.168.1.77", d.ip); assertEquals("bf0000000000000000abcd", d.deviceId); assertEquals("3.3", d.version)
        val d35 = TuyaDiscovery.parse(TuyaDiscovery.decodePacket(V.udp35.hexToBytes())!!, "0.0.0.0")!!
        assertEquals("192.168.1.78", d35.ip); assertEquals("3.5", d35.version)
    }

    @Test fun cloudSign() {
        val body = """{"commands":[{"code":"switch_1","value":true}]}"""
        val sts = TuyaCloud.stringToSign("POST", body, "/v1.0/iot-03/devices/bf1/commands")
        assertEquals(V.sign, TuyaCloud.sign("secretXYZ", "cid123", "tok456", "1700000000000", "", sts))
        val sts2 = TuyaCloud.stringToSign("GET", "", "/v1.0/token?grant_type=1")
        assertEquals(V.signtoken, TuyaCloud.sign("secretXYZ", "cid123", null, "1700000000000", "", sts2))
    }

    @Test fun energyIntegration() {
        val s = listOf(
            ua.madeisy.iot.data.Sample(0, 1000.0, null, null),
            ua.madeisy.iot.data.Sample(60_000, 1000.0, null, null),
            ua.madeisy.iot.data.Sample(10_000_000, 1000.0, null, null), // розрив — не рахується
        )
        assertEquals(1000.0 / 60 / 1000, ua.madeisy.iot.data.HistoryDb.energyKwh(s), 1e-9)
    }

    private object V {
        const val enc33 = "000055aa000000010000000700000057332e33000000000000000000000000692cbbef37f7c782afdf7c5fe9c2479ce97a3e7f33a3f3fdb5a59c2493c8ebef2125e1846bd4f824d1b2e468c239926631836d84bd28cb4b20619eb84882ca2a13d1bb220000aa55"
        const val query33 = "000055aa000000050000000a00000018cb70ddc25a2a2045b4c13084418a9abbc81389b10000aa55"
        const val enc34 = "000055aa000000010000000d000000744490b05d74be9368c24a038cbaeded8e6573166be30cce5c00921030a70fe1ba045bbade745941d316d05f2cf849dfb5f09a264f41ed2ed2962f8ff11444d4dd64215f365b0dad6b2ac8fab15b1d40bdc69f50c9b37af0fa74fe4aee1659b3c9d6e2fd856b9d682980c20aeac45058740000aa55"
        const val query34 = "000055aa000000050000001000000034cb70ddc25a2a2045b4c13084418a9abb97ee39a632848084fff13e95aa6246f38bc7f8b4d3f93383e6a4d3eb053f691b0000aa55"
        const val enc35 = "000066990000000000010000000d0000005e000102030405060708090a0bce11f11d1e743b36e826071ac09bb05622b3328c4ed76871550b8cc3df9b9b5c6e27afdec0dea8f75a1797ff1c0e39034f406d5e4753790d2098aa5e3300aa2fcd334f07e07f62039541d94f0ee7e169260b00009966"
        const val iv = "000102030405060708090a0b"
        const val dev33 = "000055aa00000007000000080000004b00000000332e33000000000000000000000000c27ee03f8be481e63320de7dc6eb4296aaaba1ebc64f5ff2f41a0467f52e726ba0cb9c08ecc72a7ef4404116370475e807a294160000aa55"
        const val dev34 = "000055aa000000080000000800000078000000004490b05d74be9368c24a038cbaeded8e98f10abffdce4e88bec061ae703238c2f75d083e1a57e0625aa130b2793cec5da15a4cb209064dac4144f8fc2e0f4997f8db0e5ba97e29b394bb5c294cf5137a74128356bd4f32a60667fda0bd75991665b75f9921daef036475cfb2ddb41dbc0000aa55"
        const val dev35 = "000066990000000000090000000800000058000102030405060708090a0bfd3fc41d2d5a0e36e826071ac09bb02d00d7578125c338240306899c838991587235fb8f848fbafd1105c3ee0d4f675a4150390c0e43714f32d4f519adf7a8da22e24ffa4ce08fcc0bae860900009966"
        const val neg34start = "000055aa0000000100000003000000445ffa34921c170d6ca7e747614568af2c377222e061a924c591cd9c27ea163ed4047642d567bbda426b5ad0fae5372eafc1e1f68ae26e1ddf70a2da34be9673040000aa55"
        const val neg34resp = "000055aa00000001000000040000006800000000b9398b93292d8194b9258c6b0930575112aeee59efec9757a3bae61e826c268b558848f9a6b2d495993c806c135cc07d377222e061a924c591cd9c27ea163ed492853935ba288942d9ec6bda59bac2d291d69f648163ec452d95c9532bfb9d720000aa55"
        const val neg34step3 = "000055aa0000000200000005000000540c522dbc1257edb94ae3e9a3f247cc6b7c66501d2a16d1ea2c5ff7bc9c9dba8b377222e061a924c591cd9c27ea163ed493f02d220fcd574b81d210796a78dc066aac7a45eedaf717e64fe9be365b2abd0000aa55"
        const val neg34session = "528c7bb6b586719bb2568b8513b647d7"
        const val neg34step3payload = "09087e40fe4c9999b7162ecd0b767f6d194b5244b42df1e1ecb8a5840d0e3144"
        const val neg35resp = "000066990000000000010000000400000050000102030405060708090a0bfd3fc41d4c317679bc6349558ed8f51b35e364c80e1858d781b9f94ad3cccc383d9dfdefb4c6053e152c4e966b551982516b672b238bc73c5b582be7f8a2a9eaf423ac2400009966"
        const val neg35session = "85956d70631467760ae20d81fc6a20f6"
        const val neg35step3payload = "09087e40fe4c9999b7162ecd0b767f6d194b5244b42df1e1ecb8a5840d0e3144"
        const val udp = "000055aa00000000000000130000009c00000000d09766676f3369eb10b5e9f132fd802a905434823b8cc27fa9192eefa16eda3ef763191a6ec2423b2b968af463a76e6d322500bd4608fdce301f893bbea64e845c907565b323a346d0de78c22f1fdaba1ae7c613c2cae004e54a474561367714379e7d68a02456269f4616388e2aa04ea5293c3042f2fea651a0076d6c53d48f49d7c04d4bd0e855432b819cbf4452f6644737e90000aa55"
        const val udp35 = "00006699000000000000000000130000004e000102030405060708090a0bc73d7f42f5a2dbe0b0212ed1e0cd0fc6ff4c1eaaa5419ad5509fd44108227a221ae4798cf111ad8f5b4d1d88c07b644313c5ae467163017c5d0760474d3550be6d2c00009966"
        const val sign = "BEBF2313C7429049FC00F009C77ED4A9BBE442A01E92D0D28FB860DF078285C4"
        const val signtoken = "3767A3C24AC094C9F4F9B541FCD2F9440A287250EC781E451BCD804C78F8611B"
    }
}
