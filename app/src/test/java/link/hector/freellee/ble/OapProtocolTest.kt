package link.hector.freellee.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Offline verification of the OAP framing and codecs against the wire bytes
 * the watch reports for its factory defaults.
 */
class OapProtocolTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ── framing ─────────────────────────────────────────────────────────────

    @Test
    fun frameRoundTrip() {
        val frame = OapProtocol.buildFrame(0x32)
        // len = 6, magic, then `02 32`
        assertEquals(0x00, frame[0].toInt())
        assertEquals(0x06, frame[1].toInt())
        assertEquals(0xAA, frame[2].toInt() and 0xFF)
        assertEquals(0x55, frame[3].toInt() and 0xFF)
        val decoded = OapProtocol.decode(frame)!!
        assertTrue(decoded.crcOk)
        assertEquals(0x32, decoded.cmd)
        assertEquals(OapProtocol.FRAME_TYPE, decoded.type)
        assertFalse(decoded.isError)
        assertArrayEquals(ByteArray(0), decoded.payload)
    }

    @Test
    fun largeFrameLengthIsBigEndian() {
        val frame = OapProtocol.buildFrame(0x3C, ByteArray(220))
        assertEquals(0x00, frame[0].toInt())
        assertEquals(226, frame[1].toInt() and 0xFF)
        assertArrayEquals(ByteArray(220), OapProtocol.decode(frame)!!.payload)
    }

    @Test
    fun crcCorruptionIsRejected() {
        val frame = OapProtocol.buildFrame(0x32, byteArrayOf(1, 2, 3))
        frame[5] = (frame[5] + 1).toByte()
        assertFalse(OapProtocol.decode(frame)!!.crcOk)
        assertTrue(FrameReassembler().feed(frame).isEmpty())
    }

    @Test
    fun errorReplyIsDecoded() {
        val body = hex("01 32 00 00 00 0b")
        val crc = OapProtocol.crc16(body)
        val after = hex("AA 55") +
            byteArrayOf((crc shr 8).toByte(), crc.toByte()) + body
        val frame = byteArrayOf(0x00, after.size.toByte()) + after
        val decoded = OapProtocol.decode(frame)!!
        assertTrue(decoded.isError)
        assertEquals(0x32, decoded.cmd)
        assertEquals(0x0bL, decoded.errorCode)
    }

    @Test
    fun reassemblerJoinsChunkedNotifications() {
        val frame = OapProtocol.buildFrame(0x3C, ByteArray(220))
        val reasm = FrameReassembler()
        val frames = ArrayList<OapProtocol.Frame>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + 20, frame.size)
            frames += reasm.feed(frame.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(1, frames.size)
        assertEquals(0x3C, frames[0].cmd)
        assertEquals(220, frames[0].payload.size)
    }

    @Test
    fun reassemblerResynchronisesAfterGarbage() {
        val garbage = byteArrayOf(0x11, 0x22, 0x33)
        val frame = OapProtocol.buildFrame(0x2A)
        val frames = FrameReassembler().feed(garbage + frame)
        assertEquals(1, frames.size)
        assertEquals(0x2A, frames[0].cmd)
    }

    @Test
    fun chunkingSplitsAtTheMtu() {
        val frame = OapProtocol.buildFrame(0x3B, ByteArray(220))
        val pieces = OapProtocol.chunk(frame, 20)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.size <= 20 })
        assertArrayEquals(frame, pieces.reduce { a, b -> a + b })
    }

    // ── command table / policy ──────────────────────────────────────────────

    @Test
    fun commandTableMatchesDispatcher() {
        assertEquals("oap_get_config_register_req", OapProtocol.COMMANDS_BY_CODE[0x32]!!.op)
        assertEquals("oap_get_alarm_req", OapProtocol.COMMANDS_BY_CODE[0x2B]!!.op)
        assertEquals("oap_get_databank_req", OapProtocol.COMMANDS_BY_CODE[0x3C]!!.op)
        assertEquals(0x52, OapProtocol.COMMANDS_BY_CODE[0x32]!!.replyCode)
        assertEquals(OapProtocol.Kind.SET, OapProtocol.COMMANDS_BY_CODE[0x33]!!.kind)
    }

    @Test
    fun commandPolicyBlocksUnsafeCommands() {
        assertNotNull(OapProtocol.commandProblem(0x21))
        assertNotNull(OapProtocol.commandProblem(0x20, byteArrayOf(0x02)))
        assertNull(OapProtocol.commandProblem(0x20, byteArrayOf(0x06)))
        assertTrue(0x2D in OapProtocol.DANGEROUS_CMDS)
        assertFalse(0x3B in OapProtocol.DANGEROUS_CMDS)
    }

    // ── records ─────────────────────────────────────────────────────────────

    @Test
    fun parseRecordNormalisesLocalTimestamps() {
        val payload = be32(1) + be32(1_700_000_000L) + be32(1_700_003_600L) + be32(2534)
        val utc = parseActivityRecord(payload, ZoneOffset.UTC)!!
        assertEquals(1, utc.type)
        assertEquals(1_700_000_000L, utc.tStart)
        assertEquals(25.34, utc.celsius, 1e-9)

        val plus2 = parseActivityRecord(payload, ZoneId.of("+02:00"))!!
        assertEquals(1_700_000_000L - 7200, plus2.tStart)
    }

    @Test
    fun parseRecordRejectsShortPayload() {
        assertNull(parseActivityRecord(ByteArray(15)))
    }

    // ── version / identity ──────────────────────────────────────────────────

    @Test
    fun versionMatchesFirmwareDefaults() {
        val payload = "DEADBEEF01.05.0000.01.10DEADBEEF".toByteArray() +
            byteArrayOf(0x00, 0x00, 0x0F, 0xA0.toByte())
        val version = VersionInfo.decode(payload)
        assertEquals("DEADBEEF", version.hashId)
        assertEquals("01.05.00", version.hardwareId)
        assertEquals("00.01.10", version.firmwareId)
        assertEquals("DEADBEEF", version.serialNumber)
        assertEquals("DEADBEEF01.05.0000.01.10DEADBEEF", version.firmware)
        assertEquals(4000, version.voltageMv)
    }

    // ── config register ─────────────────────────────────────────────────────

    @Test
    fun configMatchesFirmwareDefaults() {
        val wire = hex("0006542d000000050000ff00030002")
        val config = DeviceConfig.decode(wire)
        assertTrue(config.features["motion"]!!)
        assertTrue(config.features["temperature"]!!)
        assertTrue(config.features["gestures"]!!)
        assertTrue(config.features["step_chime"]!!)
        assertTrue(config.features["heart_rate_tilt"]!!)
        assertTrue(config.features["hold_for_home"]!!)
        assertTrue(config.features["event_counter"]!!)
        assertTrue(config.features["bluetooth_chime"]!!)
        assertTrue(config.features["backlight_colour_correction"]!!)
        assertFalse(config.features["step_accuracy_mode"]!!)
        assertEquals(5, config.autosleepSeconds)
        assertEquals(0x0000FF00, config.ledRgb)
        assertEquals(0, config.ledRed)
        assertEquals(255, config.ledGreen)
        assertEquals(0, config.ledBlue)
        assertEquals(3, config.ledBrightness) // wire 3 = "Level 5"
        assertEquals(LightHold.HOLD, config.lightHold)
        assertEquals(HourFormat.TOGGLE, config.hourFormat)
        assertEquals(1, config.nightSleepHour) // wire 2 -> 01:00
        assertArrayEquals(wire, config.encode())
    }

    @Test
    fun configEncodingRejectsBadValues() {
        val base = DeviceConfig.decode(hex("0006542d000000050000ff00030002"))
        assertThrows { base.copy(autosleepSeconds = 7).encode() }
        assertThrows { base.copy(ledBrightness = 9).encode() }
        assertThrows { base.copy(nightSleepHour = 24).encode() }
    }

    @Test
    fun configBrightnessUsesWireIndex() {
        assertEquals(3, DeviceConfig.brightnessFromLabel("Level 5"))
        assertEquals(8, DeviceConfig.brightnessFromLabel("Off"))
        assertEquals(0, DeviceConfig.brightnessFromLabel("Max"))
        assertEquals("Off", LED_BRIGHTNESS_LABELS[8])
    }

    // ── alarms ──────────────────────────────────────────────────────────────

    @Test
    fun alarmMatchesFirmwareDefaults() {
        val wire = hex(
            "00 00 01 0c 00 00 00 05 ff ff ff ff " +
                "0c 00 00 00 0c 00 00 00 0c 00 00 00 0c 00 00 00 0c 00 00 00",
        )
        val alarm = Alarm.decode(wire)
        assertFalse(alarm.alarmOn)
        assertFalse(alarm.hourlyChimeOn)
        assertTrue(alarm.snoozeOn)
        assertEquals(12, alarm.hours)
        assertEquals("Classic", alarm.chimeName)
        assertEquals((0..23).toList(), alarm.hourlyChimeHours)
        assertTrue(alarm.days.isEmpty())
        assertEquals(Alarm.SLOT_COUNT, alarm.slots.size)
        assertEquals(SlotMode.OFF, alarm.slots[0].mode)

        val encoded = alarm.encode()
        assertEquals(33, encoded.size)
        assertEquals(0, encoded[8].toInt()) // full write
        // 0x25 = 0x2B payload with a write-mode byte inserted at offset 8
        assertArrayEquals(wire, encoded.copyOfRange(0, 8) + encoded.copyOfRange(9, 33))
    }

    @Test
    fun alarmSlotModesAndChimes() {
        val wire = ByteArray(32)
        hex("07 1e 00 00").copyInto(wire, 12)
        hex("08 00 01 05").copyInto(wire, 16)
        hex("09 0f 02 0e").copyInto(wire, 20)
        val alarm = Alarm.decode(wire)
        assertEquals(SlotMode.OFF, alarm.slots[0].mode)
        assertEquals(SlotMode.DAILY, alarm.slots[1].mode)
        assertEquals(SlotMode.ONE_SHOT, alarm.slots[2].mode)
        assertEquals("Sand", alarm.slots[2].chimeName)
        assertArrayEquals(wire, alarm.encode().copyOfRange(0, 8) + alarm.encode().copyOfRange(9, 33))
    }

    @Test
    fun alarmBuildSetsDayAndHourMasks() {
        val alarm = Alarm.build(days = setOf(Weekday.MON, Weekday.SUN), hourlyChimeHours = setOf(0, 23))
        assertEquals((1 shl 1) or (1 shl 7), alarm.dayBits)
        assertEquals(listOf(Weekday.MON, Weekday.SUN), alarm.days)
        assertEquals(listOf(0, 23), alarm.hourlyChimeHours)
    }

    @Test
    fun alarmEncodingRejectsBadValues() {
        assertThrows { Alarm.build(minutes = 60).encode() }
        assertThrows { Alarm.build(chime = 15).encode() }
        assertThrows {
            Alarm.build(slots = listOf(AlarmSlot(0, 0, SlotMode.OFF, 0))).encode()
        }
    }

    // ── health ──────────────────────────────────────────────────────────────

    @Test
    fun healthMatchesFirmwareDefaults() {
        val wire = hex("00001388 14") // 5000 steps, 20 beats
        val health = HealthSettings.decode(wire)
        assertEquals(5000, health.stepGoal)
        assertEquals(20, health.pulsometerTargetBeats)
        assertArrayEquals(wire, health.encode())
    }

    @Test
    fun healthEncodingRejectsBadValues() {
        assertThrows { HealthSettings(5000, 7).encode() }
        assertThrows { HealthSettings(50, 20).encode() }
    }

    // ── nametag ─────────────────────────────────────────────────────────────

    @Test
    fun nametagRoundTrip() {
        assertEquals("Ollee", Nametag.decode(hex("4f6c6c656500")))
        assertArrayEquals(hex("4f6c6c656500"), Nametag.encode("Ollee"))
        assertThrows { Nametag.encode("too long") }
        assertThrows { Nametag.encode("caf\u00e9") }
    }

    // ── world time ──────────────────────────────────────────────────────────

    @Test
    fun worldTimeMatchesFirmwareDefaults() {
        val wire = hex("00005460") + "MOTUWETHFRSASU".toByteArray()
        val worldTime = WorldTime.decode(wire)
        assertEquals(21600, worldTime.offsetSeconds)
        assertEquals(6.0, worldTime.offsetHours, 1e-9)
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), worldTime.weekdayNames)
        assertArrayEquals(wire, worldTime.encode())
    }

    @Test
    fun worldTimeRejectsLongNames() {
        assertThrows { WorldTime(0, listOf("Monday", "Tu", "We", "Th", "Fr", "Sa", "Su")).encode() }
    }

    // ── faces ───────────────────────────────────────────────────────────────

    @Test
    fun facesRoundTripFromDefaultsShape() {
        val wire = ByteArray(FACE_COUNT * 6)
        for (i in 0 until FACE_COUNT) {
            val id = 4 + i
            val clock = id == CLOCK_FACE_ID
            val b = i * 6
            wire[b] = id.toByte()
            wire[b + 1] = (if (clock) 0 else 1).toByte()
            wire[b + 2] = 0
            wire[b + 3] = (if (clock) 0 else 1).toByte()
            wire[b + 4] = 0
            wire[b + 5] = i.toByte()
        }
        val faces = Faces.decode(wire)
        assertEquals(FACE_COUNT, faces.size)
        assertEquals("Clock", faces[0].name)
        assertEquals("Alarm", faces[1].name)
        assertArrayEquals(wire, Faces.encode(faces))
    }

    // ── databank ────────────────────────────────────────────────────────────

    @Test
    fun databankTagMapping() {
        val entries = List(Databank.ENTRY_COUNT) { DatabankEntry("none", "") }
            .toMutableList()
        entries[0] = DatabankEntry("note", "hello")
        entries[1] = DatabankEntry("phone", "0123")
        entries[9] = DatabankEntry("password", "hunter2")
        val wire = Databank.encode(entries)
        assertEquals(220, wire.size)
        assertEquals(3, wire[0].toInt())       // note
        assertEquals(1, wire[22].toInt())      // phone
        assertEquals(5, wire[9 * 22].toInt())  // password
        val back = Databank.decode(wire)
        assertEquals(DatabankEntry("note", "hello"), back[0])
        assertEquals("password", back[9].tag)
        assertEquals("hunter2", back[9].text)
    }

    @Test
    fun databankRejectsBadInput() {
        assertThrows { Databank.encode(List(9) { DatabankEntry("none", "") }) }
        assertThrows {
            val e = List(Databank.ENTRY_COUNT) { DatabankEntry("none", "") }.toMutableList()
            e[0] = DatabankEntry("note", "x".repeat(22))
            Databank.encode(e)
        }
        assertThrows {
            val e = List(Databank.ENTRY_COUNT) { DatabankEntry("none", "") }.toMutableList()
            e[0] = DatabankEntry("N", "x")
            Databank.encode(e)
        }
    }

    // ── weather ─────────────────────────────────────────────────────────────

    @Test
    fun weatherRoundTrip() {
        val days = List(Weather.DAY_COUNT) {
            WeatherDay(15, 10, 21, 12, 5, "Cloudy", 10, 60, 20)
        }
        val wire = Weather(days = days, unit = "fahrenheit").encode()
        assertEquals(41, wire.size)
        assertEquals(1, wire[40].toInt())
        val back = Weather.decode(wire)
        assertEquals("fahrenheit", back.unit)
        assertEquals("Cloudy", back.days[0].condition)
        assertArrayEquals(wire, back.encode())
        assertThrows { back.copy(days = listOf(back.days[0].copy(condition = "Nope")) + days.drop(1)).encode() }
    }

    // ── timeref / pair code / io ────────────────────────────────────────────

    @Test
    fun timerefRoundTrip() {
        val value = TimeReference(
            utcSeconds = 1_759_276_800L,
            timezoneOffsetSeconds = 7200,
            latitude = 41.39,
            longitude = 2.16,
            milliseconds = 250,
            lunitidalMinutes = 330,
        )
        val wire = value.encode()
        assertEquals(20, wire.size)
        assertEquals(1_759_276_800L, wire.leU32ForTest(0))
        assertEquals(7200, wire.leI32ForTest(4))
        assertEquals(41390, wire.leI32ForTest(8))
        assertEquals(2160, wire.leI32ForTest(12))
        assertEquals(250, (wire[16].toInt() and 0xFF) or ((wire[17].toInt() and 0xFF) shl 8))
        assertEquals(330, ((wire[18].toInt() and 0xFF) or ((wire[19].toInt() and 0xFF) shl 8)).toShort().toInt())
        assertThrows { value.copy(lunitidalMinutes = -30).encode() }
    }

    @Test
    fun pairCodeMapsMode() {
        assertArrayEquals(hex("002a02"), PairCode(42, 1).encode())
        assertArrayEquals(hex("000700"), PairCode(7, 0).encode())
    }

    @Test
    fun ioButtonPress() {
        assertArrayEquals(hex("020000"), ButtonInput.press(Button.ALARM, 2).encode())
        assertArrayEquals(hex("000300"), ButtonInput.press(Button.MODE, 3).encode())
        assertThrows { ButtonInput.press(Button.LIGHT, 4) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun be32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: OapCodecException) {
            return
        }
        throw AssertionError("expected OapCodecException")
    }
}

// Test-only little-endian readers (the production helpers are internal).
private fun ByteArray.leI32ForTest(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.leU32ForTest(offset: Int): Long = leI32ForTest(offset).toLong() and 0xFFFFFFFFL
