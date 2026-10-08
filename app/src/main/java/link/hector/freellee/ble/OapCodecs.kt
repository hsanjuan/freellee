package link.hector.freellee.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * Payload codecs for the OAP commands: raw payload bytes (the part after
 * `cmd`) <-> typed Kotlin values.
 *
 * Every layout and range here was verified against observed device traffic and
 * the companion app. Multi-byte integers are **big-endian** unless a field is
 * explicitly documented as little-endian (`0x23` time reference, the alarm's
 * hourly-chime mask, the timer presets).
 */
class OapCodecException(message: String) : IllegalArgumentException(message)

private fun fail(message: String): Nothing = throw OapCodecException(message)

// ── version (0x2A) ──────────────────────────────────────────────────────────

/** Reply payload of `0x2A`: four 8-byte ASCII fields + a trailing u32 BE. */
data class VersionInfo(
    val hashId: String,
    val hardwareId: String,
    val firmwareId: String,
    val serialNumber: String,
    val voltageMv: Int?,
) {
    /** The watch's 32-byte version blob, as the UI has always shown it. */
    val firmware: String get() = hashId + hardwareId + firmwareId + serialNumber

    companion object {
        fun decode(payload: ByteArray): VersionInfo {
            if (payload.size < 32) fail("version: expected >= 32 bytes, got ${payload.size}")
            fun chunk(offset: Int): String = payload.copyOfRange(offset, offset + 8).asciiTrimmed()
            val voltage = if (payload.size >= 36) payload.beU32(32).toInt() else null
            return VersionInfo(chunk(0), chunk(8), chunk(16), chunk(24), voltage)
        }
    }
}

// ── config register (0x32 / 0x33) ───────────────────────────────────────────

/** 3-bit field at mask bits 7..9; the wire value is the enum ordinal. */
enum class LightHold(val label: String) {
    HOLD("Hold only"), S1("1 second"), S2("2 seconds"), S3("3 seconds"),
    S5("5 seconds"), S10("10 seconds"), TOGGLE("Toggle"),
}

enum class HourFormat(val label: String) {
    TOGGLE("Toggle"), AMPM("AM/PM"), H24("24H"),
}

/** Backlight attenuation index; 0 = brightest, 8 = off (the firmware shifts by this). */
val LED_BRIGHTNESS_LABELS = listOf(
    "Max", "Level 7", "Level 6", "Level 5", "Level 4", "Level 3", "Level 2", "Level 1", "Off",
)

val AUTOSLEEP_OPTIONS_SECONDS = setOf(5, 10, 30, 60, 120)

/** feature key -> app UI name, indexed by mask bit. */
val CONFIG_FEATURE_BITS: Map<Int, Pair<String, String>> = linkedMapOf(
    0 to ("motion" to "Motion"),
    1 to ("face_timeout_disable" to "Face timeout disable"),
    2 to ("temperature" to "Temperature"),
    3 to ("gestures" to "Gestures"),
    4 to ("audio_mute" to "Audio mute"),
    5 to ("step_chime" to "Step count chime"),
    6 to ("autosleep_on" to "Auto sleep"),
    10 to ("heart_rate_tilt" to "Heart rate tilt"),
    11 to ("animations" to "Animations"),
    12 to ("hold_for_home" to "Hold for home"),
    13 to ("bluetooth_always_on" to "Bluetooth always on"),
    14 to ("event_counter" to "Events - Counter enable"),
    15 to ("event_stopwatch" to "Events - Stopwatch enable"),
    16 to ("backlight_tilt" to "Backlight tilt"),
    17 to ("bluetooth_chime" to "Bluetooth chime"),
    18 to ("backlight_colour_correction" to "Backlight colour correction"),
    19 to ("step_accuracy_mode" to "Step count accuracy mode"),
    20 to ("alarm_timer_backlight" to "Alarm/Timer backlight"),
    21 to ("bluetooth_triple_tap" to "Bluetooth triple tap"),
    22 to ("favorite_glance" to "Favorite glance"),
    23 to ("alarm_flip_snooze" to "Alarm flip snooze"),
    24 to ("night_mode_dnd" to "Night mode DND"),
    26 to ("backlight_disco" to "Backlight disco mode"),
    27 to ("screensaver" to "Screen saver"),
)
private const val LIGHT_HOLD_SHIFT = 7
private const val LIGHT_HOLD_MASK = 0x7
private val KNOWN_FEATURE_MASK: Int =
    CONFIG_FEATURE_BITS.keys.fold(0) { acc, bit -> acc or (1 shl bit) } or (LIGHT_HOLD_MASK shl LIGHT_HOLD_SHIFT)

/** Device settings ("config register"), 15 request/reply bytes. */
data class DeviceConfig(
    /** Every entry of [CONFIG_FEATURE_BITS], keyed by its `key`. */
    val features: Map<String, Boolean>,
    val lightHold: LightHold,
    /** Seconds; one of [AUTOSLEEP_OPTIONS_SECONDS]. */
    val autosleepSeconds: Int,
    /** 32-bit backlight colour as stored by the watch. */
    val ledRgb: Int,
    /** Wire index 0..8; 0 = [LED_BRIGHTNESS_LABELS]`[0]`. */
    val ledBrightness: Int,
    val hourFormat: HourFormat,
    /** Hour 0..23 the watch sleeps until, or null when disabled. */
    val nightSleepHour: Int?,
    /** Bits we do not understand, preserved across a read/modify/write. */
    val unknownBits: Int = 0,
) {
    val ledRed: Int get() = (ledRgb ushr 16) and 0xFF
    val ledGreen: Int get() = (ledRgb ushr 8) and 0xFF
    val ledBlue: Int get() = ledRgb and 0xFF
    val ledAlpha: Int get() = (ledRgb ushr 24) and 0xFF
    val ledBrightnessLabel: String get() = LED_BRIGHTNESS_LABELS.getOrElse(ledBrightness) { ledBrightness.toString() }

    companion object {
        fun decode(payload: ByteArray): DeviceConfig {
            if (payload.size != 15) fail("config: expected 15 bytes, got ${payload.size}")
            val mask = payload.beU32(0).toInt()
            val features = LinkedHashMap<String, Boolean>(CONFIG_FEATURE_BITS.size)
            for ((bit, spec) in CONFIG_FEATURE_BITS) features[spec.first] = (mask and (1 shl bit)) != 0
            val lightHold = LightHold.entries.getOrElse((mask ushr LIGHT_HOLD_SHIFT) and LIGHT_HOLD_MASK) { LightHold.HOLD }
            val nightWire = payload[14].toInt() and 0xFF
            return DeviceConfig(
                features = features,
                lightHold = lightHold,
                autosleepSeconds = payload.beU32(4).toInt(),
                ledRgb = payload.beU32(8).toInt(),
                ledBrightness = payload[12].toInt() and 0xFF,
                hourFormat = HourFormat.entries.getOrElse(payload[13].toInt() and 0xFF) { HourFormat.TOGGLE },
                nightSleepHour = when {
                    nightWire == 0 -> null
                    nightWire in 1..24 -> nightWire - 1
                    else -> null
                },
                unknownBits = mask and KNOWN_FEATURE_MASK.inv(),
            )
        }

        /** Encode backlight brightness label/index; returns the 0..8 wire index. */
        fun brightnessFromLabel(label: String): Int {
            val index = LED_BRIGHTNESS_LABELS.indexOfFirst { it.equals(label.trim(), ignoreCase = true) }
            if (index < 0) fail("config.led_brightness: '$label' is not one of $LED_BRIGHTNESS_LABELS")
            return index
        }
    }

    fun encode(): ByteArray {
        var mask = unknownBits and KNOWN_FEATURE_MASK.inv()
        for ((key, enabled) in features) {
            val spec = CONFIG_FEATURE_BITS.entries.firstOrNull { it.value.first == key }
                ?: fail("config.features.$key: unknown feature name")
            if (enabled) mask = mask or (1 shl spec.key)
        }
        mask = mask or ((lightHold.ordinal and LIGHT_HOLD_MASK) shl LIGHT_HOLD_SHIFT)
        if (autosleepSeconds !in AUTOSLEEP_OPTIONS_SECONDS) {
            fail("config.autosleep_period: $autosleepSeconds is not one of ${AUTOSLEEP_OPTIONS_SECONDS.sorted()}")
        }
        if (ledBrightness !in 0..8) fail("config.led_brightness: $ledBrightness is out of range 0..8")
        val nightWire = nightSleepHour?.let {
            if (it !in 0..23) fail("config.night_sleep: hour $it is out of range 0..23")
            it + 1
        } ?: 0
        return ByteBuffer.allocate(15).order(ByteOrder.BIG_ENDIAN)
            .putInt(mask)
            .putInt(autosleepSeconds)
            .putInt(ledRgb)
            .put(ledBrightness.toByte())
            .put(hourFormat.ordinal.toByte())
            .put(nightWire.toByte())
            .array()
    }
}

// ── nametag (0x2E / 0x2F) ───────────────────────────────────────────────────

object Nametag {
    const val LENGTH = 6

    fun decode(payload: ByteArray): String {
        if (payload.size != LENGTH) fail("nametag: expected $LENGTH bytes, got ${payload.size}")
        return payload.asciiTrimmed()
    }

    fun encode(value: String): ByteArray {
        val raw = asciiBytes(value, "nametag")
        if (raw.size > LENGTH) fail("nametag: '$value' is ${raw.size} bytes, maximum is $LENGTH")
        return raw.copyOf(LENGTH)
    }
}

// ── alarms (0x2B / 0x25) ────────────────────────────────────────────────────

enum class Weekday(val label: String) { MON("Mon"), TUE("Tue"), WED("Wed"), THU("Thu"), FRI("Fri"), SAT("Sat"), SUN("Sun") }

/** Daily-slot byte +2: `0` off, `1` repeats daily, `2` one-shot. */
enum class SlotMode { OFF, DAILY, ONE_SHOT }

val ALARM_CHIMES = listOf(
    "Classic", "Breeze", "Westminster", "Retro", "Wire", "Plumber", "Indy", "Galactic",
    "Dinosaur", "Superman", "Tequila", "Beethoven", "Blocks", "Ghosts", "Sand",
)

data class AlarmSlot(val hours: Int, val minutes: Int, val mode: SlotMode, val chime: Int) {
    val chimeName: String get() = ALARM_CHIMES.getOrElse(chime) { chime.toString() }
}

/** Complete alarm configuration: main alarm + hourly chime + five daily slots. */
data class Alarm(
    val alarmOn: Boolean,
    val hourlyChimeOn: Boolean,
    val snoozeOn: Boolean,
    val hours: Int,
    val minutes: Int,
    /** Weekday mask, `1 shl (weekday.ordinal + 1)`; bit 0 is unused. */
    val dayBits: Int,
    /** [ALARM_CHIMES] index 0..14. */
    val chime: Int,
    /** Snooze length in minutes. */
    val snoozeMinutes: Int,
    /** Bit per hour, `1 shl hour`. */
    val hourlyChimeMask: Int,
    val slots: List<AlarmSlot>,
) {
    val days: List<Weekday> get() = Weekday.entries.filter { (dayBits and (1 shl (it.ordinal + 1))) != 0 }
    val hourlyChimeHours: List<Int> get() = (0..23).filter { (hourlyChimeMask and (1 shl it)) != 0 }
    val chimeName: String get() = ALARM_CHIMES.getOrElse(chime) { chime.toString() }

    companion object {
        const val SLOT_COUNT = 5

        fun decode(payload: ByteArray): Alarm {
            if (payload.size != 32) fail("alarm: expected 32 bytes, got ${payload.size}")
            val slots = (0 until SLOT_COUNT).map { i ->
                val b = 12 + 4 * i
                AlarmSlot(
                    hours = payload[b].toInt() and 0xFF,
                    minutes = payload[b + 1].toInt() and 0xFF,
                    mode = SlotMode.entries.getOrElse(payload[b + 2].toInt() and 0xFF) { SlotMode.OFF },
                    chime = payload[b + 3].toInt() and 0xFF,
                )
            }
            return Alarm(
                alarmOn = payload[0].toInt() != 0,
                hourlyChimeOn = payload[1].toInt() != 0,
                snoozeOn = payload[2].toInt() != 0,
                hours = payload[3].toInt() and 0xFF,
                minutes = payload[4].toInt() and 0xFF,
                dayBits = payload[5].toInt() and 0xFF,
                chime = payload[6].toInt() and 0xFF,
                snoozeMinutes = payload[7].toInt() and 0xFF,
                hourlyChimeMask = payload.leI32(8),
                slots = slots,
            )
        }

        /** Build an alarm from weekday/hour sets instead of the raw masks. */
        fun build(
            alarmOn: Boolean = false,
            hourlyChimeOn: Boolean = false,
            snoozeOn: Boolean = false,
            hours: Int = 0,
            minutes: Int = 0,
            days: Set<Weekday> = emptySet(),
            chime: Int = 0,
            snoozeMinutes: Int = 5,
            hourlyChimeHours: Set<Int> = emptySet(),
            slots: List<AlarmSlot> = List(SLOT_COUNT) { AlarmSlot(0, 0, SlotMode.OFF, 0) },
        ): Alarm = Alarm(
            alarmOn = alarmOn,
            hourlyChimeOn = hourlyChimeOn,
            snoozeOn = snoozeOn,
            hours = hours,
            minutes = minutes,
            dayBits = days.fold(0) { acc, d -> acc or (1 shl (d.ordinal + 1)) },
            chime = chime,
            snoozeMinutes = snoozeMinutes,
            hourlyChimeMask = hourlyChimeHours.fold(0) { acc, h -> acc or (1 shl h) },
            slots = slots,
        )
    }

    /** Encode the 33-byte `0x25` request (full write, mode byte 0). */
    fun encode(): ByteArray {
        if (hours !in 0..23) fail("alarms.hours: $hours is out of range 0..23")
        if (minutes !in 0..59) fail("alarms.minutes: $minutes is out of range 0..59")
        if (chime !in ALARM_CHIMES.indices) fail("alarms.chime: $chime is not a valid chime index")
        if (snoozeMinutes !in 0..255) fail("alarms.snooze_period: $snoozeMinutes is out of range 0..255")
        if (slots.size != SLOT_COUNT) fail("alarms.slots: expected $SLOT_COUNT entries, got ${slots.size}")
        val out = ByteArray(33)
        out[0] = if (alarmOn) 1 else 0
        out[1] = if (hourlyChimeOn) 1 else 0
        out[2] = if (snoozeOn) 1 else 0
        out[3] = hours.toByte()
        out[4] = minutes.toByte()
        out[5] = (dayBits and 0xFF).toByte()
        out[6] = chime.toByte()
        out[7] = snoozeMinutes.toByte()
        out[8] = 0 // write mode: full write
        putLeI32(out, 9, hourlyChimeMask)
        slots.forEachIndexed { i, slot ->
            if (slot.hours !in 0..23) fail("alarms.slots[$i].hours: ${slot.hours} is out of range 0..23")
            if (slot.minutes !in 0..59) fail("alarms.slots[$i].minutes: ${slot.minutes} is out of range 0..59")
            if (slot.chime !in ALARM_CHIMES.indices) fail("alarms.slots[$i].chime: ${slot.chime} is invalid")
            val b = 13 + 4 * i
            out[b] = slot.hours.toByte()
            out[b + 1] = slot.minutes.toByte()
            out[b + 2] = slot.mode.ordinal.toByte()
            out[b + 3] = slot.chime.toByte()
        }
        return out
    }
}

// ── timer (0x2C / 0x26) ─────────────────────────────────────────────────────

/** `0x2C` reply payload: remaining h/m/s + a run-state byte. */
data class TimerState(val hours: Int, val minutes: Int, val seconds: Int, val state: Int) {
    companion object {
        fun decode(payload: ByteArray): TimerState {
            if (payload.size != 4) fail("timer: expected 4 bytes, got ${payload.size}")
            return TimerState(
                payload[0].toInt() and 0xFF,
                payload[1].toInt() and 0xFF,
                payload[2].toInt() and 0xFF,
                payload[3].toInt() and 0xFF,
            )
        }
    }
}

/** `0x26` request action byte: set duration only, start, or start in interval mode. */
enum class TimerAction { SET_DURATION, START, START_INTERVAL }

object TimerSettings {
    const val PRESET_COUNT = 10
    val DEFAULT_PRESETS_SECONDS = listOf(180, 30, 180, 30, 0, 60, 120, 600, 900, 1800)

    /**
     * Encode the 44-byte `0x26` request. The presets are always sent in full
     * (the watch cannot report them back), so [presetsSeconds] must be complete.
     */
    fun encode(
        presetsSeconds: List<Int>,
        hours: Int = 0,
        minutes: Int = 0,
        seconds: Int = 0,
        action: TimerAction = TimerAction.SET_DURATION,
    ): ByteArray {
        if (presetsSeconds.size != PRESET_COUNT) {
            fail("timer.presets_seconds: expected $PRESET_COUNT values, got ${presetsSeconds.size}")
        }
        if (hours !in 0..23) fail("timer.hours: $hours is out of range 0..23")
        if (minutes !in 0..59) fail("timer.minutes: $minutes is out of range 0..59")
        if (seconds !in 0..59) fail("timer.seconds: $seconds is out of range 0..59")
        val out = ByteArray(44)
        out[0] = hours.toByte()
        out[1] = minutes.toByte()
        out[2] = seconds.toByte()
        out[3] = action.ordinal.toByte()
        presetsSeconds.forEachIndexed { i, preset ->
            if (preset < 0) fail("timer.presets_seconds[$i]: $preset is negative")
            putLeI32(out, 4 + 4 * i, preset)
        }
        return out
    }
}

// ── faces (0x36 / 0x37) ─────────────────────────────────────────────────────

val FACE_NAMES: Map<Int, String> = mapOf(
    0x04 to "Clock", 0x05 to "Alarm", 0x06 to "World Time", 0x07 to "Stopwatch",
    0x08 to "Set Clock", 0x09 to "Timer", 0x0A to "Step Counter", 0x0B to "Temperature",
    0x0C to "Heart Rate", 0x0D to "Counter", 0x0E to "Flashlight", 0x0F to "Game A (PING)",
    0x10 to "Game B (Blackjack)", 0x11 to "Sun, Moon & Tide", 0x12 to "Game C (Poker)",
    0x13 to "Daily Alarm", 0x14 to "Weather", 0x15 to "Databank",
)
const val FACE_COUNT = 18
const val CLOCK_FACE_ID = 0x04

data class Face(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    val canBeDisabled: Boolean,
    val canBeOrdered: Boolean,
    val favorite: Boolean,
    val order: Int,
)

object Faces {
    /** Decode the 108-byte `0x37` reply, one entry per 6 bytes, in id order. */
    fun decode(payload: ByteArray): List<Face> {
        if (payload.size != FACE_COUNT * 6) {
            fail("faces: expected ${FACE_COUNT * 6} bytes, got ${payload.size}")
        }
        return (0 until FACE_COUNT).map { i ->
            val b = i * 6
            val id = payload[b].toInt() and 0xFF
            Face(
                id = id,
                name = FACE_NAMES[id] ?: "unnamed $id",
                enabled = (payload[b + 2].toInt() and 0xFF) == 0,
                canBeDisabled = (payload[b + 1].toInt() and 0xFF) != 0,
                canBeOrdered = (payload[b + 3].toInt() and 0xFF) != 0,
                favorite = (payload[b + 4].toInt() and 0xFF) != 0,
                order = payload[b + 5].toInt() and 0xFF,
            )
        }
    }

    /** Encode the 108-byte `0x36` request. Ids outside `0..21` are ignored by the watch. */
    fun encode(faces: List<Face>): ByteArray {
        if (faces.size != FACE_COUNT) fail("faces: expected $FACE_COUNT entries, got ${faces.size}")
        val out = ByteArray(FACE_COUNT * 6)
        faces.forEachIndexed { i, face ->
            if (face.id !in 0..21) fail("faces[$i].id: ${face.id} is not a valid face id")
            val b = i * 6
            out[b] = face.id.toByte()
            out[b + 1] = if (face.canBeDisabled) 1 else 0
            out[b + 2] = if (face.enabled) 0 else 1
            out[b + 3] = if (face.canBeOrdered) 1 else 0
            out[b + 4] = if (face.favorite) 1 else 0
            out[b + 5] = face.order.toByte()
        }
        return out
    }
}

// ── world time (0x34 / 0x35) ────────────────────────────────────────────────

data class WorldTime(val offsetSeconds: Int, val weekdayNames: List<String>) {
    val offsetHours: Double get() = offsetSeconds / 3600.0

    companion object {
        const val WEEKDAY_COUNT = 7
        private const val NAME_LEN = 2

        fun decode(payload: ByteArray): WorldTime {
            val expected = 4 + WEEKDAY_COUNT * NAME_LEN
            if (payload.size < expected) fail("worldtime: expected >= $expected bytes, got ${payload.size}")
            val names = (0 until WEEKDAY_COUNT).map { i ->
                val b = 4 + i * NAME_LEN
                payload.copyOfRange(b, b + NAME_LEN).asciiTrimmed()
            }
            return WorldTime(payload.beI32(0), names)
        }

        fun ofHours(offsetHours: Double, weekdayNames: List<String>): WorldTime =
            WorldTime(Math.round(offsetHours * 3600).toInt(), weekdayNames)
    }

    fun encode(): ByteArray {
        if (weekdayNames.size != WEEKDAY_COUNT) {
            fail("worldtime.weekdays: expected $WEEKDAY_COUNT names, got ${weekdayNames.size}")
        }
        val out = ByteArray(4 + WEEKDAY_COUNT * 2)
        out[0] = (offsetSeconds ushr 24).toByte()
        out[1] = (offsetSeconds ushr 16).toByte()
        out[2] = (offsetSeconds ushr 8).toByte()
        out[3] = offsetSeconds.toByte()
        weekdayNames.forEachIndexed { i, name ->
            val raw = asciiBytes(name, "worldtime.weekdays[$i]")
            if (raw.size > 2) fail("worldtime.weekdays[$i]: '$name' is longer than 2 characters")
            val b = 4 + i * 2
            out[b] = raw.getOrElse(0) { 0 }
            out[b + 1] = raw.getOrElse(1) { 0 }
        }
        return out
    }
}

// ── health / pedometer (0x30 / 0x31) ────────────────────────────────────────

val PULSOMETER_BEATS = setOf(5, 10, 20, 30)

data class HealthSettings(val stepGoal: Int, val pulsometerTargetBeats: Int) {
    companion object {
        fun decode(payload: ByteArray): HealthSettings {
            if (payload.size != 5) fail("health: expected 5 bytes, got ${payload.size}")
            return HealthSettings(payload.beU32(0).toInt(), payload[4].toInt() and 0xFF)
        }
    }

    fun encode(): ByteArray {
        if (stepGoal !in 100..500000) fail("health.step_goal: $stepGoal is out of range 100..500000")
        if (pulsometerTargetBeats !in PULSOMETER_BEATS) {
            fail("health.pulsometer_target_beats: $pulsometerTargetBeats is not one of ${PULSOMETER_BEATS.sorted()}")
        }
        val out = ByteArray(5)
        putBeI32(out, 0, stepGoal)
        out[4] = pulsometerTargetBeats.toByte()
        return out
    }
}

// ── time reference (0x23) ───────────────────────────────────────────────────

/** Write-only time/location sync; all multi-byte fields are little-endian. */
data class TimeReference(
    val utcSeconds: Long,
    val timezoneOffsetSeconds: Int,
    val latitude: Double,
    val longitude: Double,
    val milliseconds: Int,
    /** Lunitidal interval in minutes, or -1 for "no data". */
    val lunitidalMinutes: Int,
) {
    companion object {
        const val NO_LUNITIDAL = -1

        fun now(
            zoneOffsetSeconds: Int,
            latitude: Double = 0.0,
            longitude: Double = 0.0,
            lunitidalMinutes: Int = NO_LUNITIDAL,
        ): TimeReference {
            val now = Instant.now()
            return TimeReference(
                utcSeconds = now.epochSecond,
                timezoneOffsetSeconds = zoneOffsetSeconds,
                latitude = latitude,
                longitude = longitude,
                milliseconds = (now.nano / 1_000_000),
                lunitidalMinutes = lunitidalMinutes,
            )
        }
    }

    fun encode(): ByteArray {
        if (utcSeconds !in 0..0xFFFFFFFFL) fail("timeref.utc_seconds: $utcSeconds is out of range")
        if (milliseconds !in 0..999) fail("timeref.milliseconds: $milliseconds is out of range 0..999")
        if (timezoneOffsetSeconds !in -86400..86400) fail("timeref.timezone_offset_seconds: out of range")
        if (latitude !in -90.0..90.0) fail("timeref.latitude: $latitude is out of range")
        if (longitude !in -180.0..180.0) fail("timeref.longitude: $longitude is out of range")
        if (lunitidalMinutes !in -1..1440) fail("timeref.lunitidal_interval_minutes: $lunitidalMinutes is out of range")

        val out = ByteArray(20)
        putLeI32(out, 0, utcSeconds.toInt())
        putLeI32(out, 4, timezoneOffsetSeconds)
        putLeI32(out, 8, Math.round(latitude * 1000).toInt())
        putLeI32(out, 12, Math.round(longitude * 1000).toInt())
        out[16] = milliseconds.toByte()
        out[17] = (milliseconds ushr 8).toByte()
        out[18] = lunitidalMinutes.toByte()
        out[19] = (lunitidalMinutes shr 8).toByte()
        return out
    }
}

// ── weather (0x3A, write-only) ──────────────────────────────────────────────

val WEATHER_CONDITIONS = listOf(
    "Sunny", "Partly Cloudy", "Cloudy", "Rainy", "Snowy", "Stormy", "Foggy",
)

data class WeatherDay(
    val dayOfMonth: Int,
    val month: Int,
    val highC: Int,
    val lowC: Int,
    val uvIndex: Int,
    val condition: String,
    val precipitationPct: Int,
    val humidityPct: Int,
    val feelsLikeC: Int,
)

data class Weather(val unit: String, val days: List<WeatherDay>) {
    companion object {
        const val DAY_COUNT = 5

        fun decode(payload: ByteArray): Weather {
            if (payload.size != 41) fail("weather: expected 41 bytes, got ${payload.size}")
            val days = (0 until DAY_COUNT).map { i ->
                val b = i * 8
                val cond = payload[b + 4].toInt() and 0x0F
                WeatherDay(
                    dayOfMonth = payload[b].toInt() and 0xFF,
                    month = payload[b + 1].toInt() and 0xFF,
                    highC = payload[b + 2].toInt(),
                    lowC = payload[b + 3].toInt(),
                    uvIndex = (payload[b + 4].toInt() and 0xFF) ushr 4,
                    condition = WEATHER_CONDITIONS.getOrElse(cond) { cond.toString() },
                    precipitationPct = payload[b + 5].toInt() and 0xFF,
                    humidityPct = payload[b + 6].toInt() and 0xFF,
                    feelsLikeC = payload[b + 7].toInt(),
                )
            }
            return Weather(if ((payload[40].toInt() and 0xFF) == 1) "fahrenheit" else "celsius", days)
        }

        fun stub(): Weather = Weather(
            unit = "celsius",
            days = List(DAY_COUNT) { WeatherDay(0, 0, 0, 0, 0, "Sunny", 0, 0, 0) },
        )
    }

    fun encode(): ByteArray {
        if (days.size != DAY_COUNT) fail("weather.days: expected $DAY_COUNT records, got ${days.size}")
        val unitByte = when {
            unit.startsWith("f", ignoreCase = true) -> 1
            unit.startsWith("c", ignoreCase = true) -> 0
            else -> fail("weather.unit: expected 'celsius' or 'fahrenheit'")
        }
        val out = ByteArray(41)
        days.forEachIndexed { i, day ->
            val condition = WEATHER_CONDITIONS.indexOfFirst { it.equals(day.condition.trim(), ignoreCase = true) }
            if (condition < 0) fail("weather.days[$i].condition: '${day.condition}' is not one of $WEATHER_CONDITIONS")
            if (day.uvIndex !in 0..15) fail("weather.days[$i].uv_index: ${day.uvIndex} is out of range 0..15")
            if (day.highC !in -128..127) fail("weather.days[$i].high: ${day.highC} is out of range")
            if (day.lowC !in -128..127) fail("weather.days[$i].low: ${day.lowC} is out of range")
            if (day.feelsLikeC !in -128..127) fail("weather.days[$i].feels_like: ${day.feelsLikeC} is out of range")
            if (day.precipitationPct !in 0..100) fail("weather.days[$i].precipitation_pct: out of range 0..100")
            if (day.humidityPct !in 0..100) fail("weather.days[$i].humidity_pct: out of range 0..100")
            val b = i * 8
            out[b] = day.dayOfMonth.toByte()
            out[b + 1] = day.month.toByte()
            out[b + 2] = day.highC.toByte()
            out[b + 3] = day.lowC.toByte()
            out[b + 4] = ((day.uvIndex shl 4) or (condition and 0x0F)).toByte()
            out[b + 5] = day.precipitationPct.toByte()
            out[b + 6] = day.humidityPct.toByte()
            out[b + 7] = day.feelsLikeC.toByte()
        }
        out[40] = unitByte.toByte()
        return out
    }
}

// ── databank (0x3B / 0x3C) ──────────────────────────────────────────────────

val DATABANK_TAGS = listOf("none", "phone", "list", "note", "reminder", "password")

data class DatabankEntry(val tag: String, val text: String)

object Databank {
    const val ENTRY_COUNT = 10
    const val ENTRY_LEN = 22 // 1 tag byte + 21 text bytes

    fun decode(payload: ByteArray): List<DatabankEntry> {
        if (payload.size != ENTRY_COUNT * ENTRY_LEN) {
            fail("databank: expected ${ENTRY_COUNT * ENTRY_LEN} bytes, got ${payload.size}")
        }
        return (0 until ENTRY_COUNT).map { i ->
            val b = i * ENTRY_LEN
            val tag = payload[b].toInt() and 0xFF
            DatabankEntry(
                tag = DATABANK_TAGS.getOrElse(tag) { tag.toString() },
                text = payload.copyOfRange(b + 1, b + ENTRY_LEN).asciiTrimmed(),
            )
        }
    }

    fun encode(entries: List<DatabankEntry>): ByteArray {
        if (entries.size != ENTRY_COUNT) fail("databank: expected $ENTRY_COUNT entries, got ${entries.size}")
        val out = ByteArray(ENTRY_COUNT * ENTRY_LEN)
        entries.forEachIndexed { i, entry ->
            val tag = DATABANK_TAGS.indexOfFirst { it.equals(entry.tag.trim(), ignoreCase = true) }
            if (tag < 0) fail("databank[$i].tag: '${entry.tag}' is not one of $DATABANK_TAGS")
            val text = asciiBytes(entry.text, "databank[$i].text")
            if (text.size > ENTRY_LEN - 1) fail("databank[$i].text: ${text.size} bytes, maximum is ${ENTRY_LEN - 1}")
            val b = i * ENTRY_LEN
            out[b] = tag.toByte()
            text.copyInto(out, b + 1)
        }
        return out
    }
}

// ── debug IO / button injection (0x38) ──────────────────────────────────────

/** Synthetic button gesture levels: `0` none, `1`/`2`/`3` the button's variants. */
enum class Button(val label: String) { ALARM("ALARM"), MODE("MODE"), LIGHT("LIGHT") }

data class ButtonInput(val buttonA: Int, val buttonB: Int, val buttonC: Int) {
    companion object {
        fun decode(payload: ByteArray): ButtonInput {
            if (payload.size != 3) fail("io: expected 3 bytes, got ${payload.size}")
            return ButtonInput(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, payload[2].toInt() and 0xFF)
        }

        /** A payload that presses [button] at [level] (an `ALARM`/`MODE`/`LIGHT` gesture). */
        fun press(button: Button, level: Int): ButtonInput {
            if (level !in 0..3) fail("io.level: $level is out of range 0..3")
            return when (button) {
                Button.ALARM -> ButtonInput(level, 0, 0)
                Button.MODE -> ButtonInput(0, level, 0)
                Button.LIGHT -> ButtonInput(0, 0, level)
            }
        }
    }

    fun encode(): ByteArray = byteArrayOf(buttonA.toByte(), buttonB.toByte(), buttonC.toByte())
}

// ── pairing code (0x29) ─────────────────────────────────────────────────────

data class PairCode(val code: Int, val mode: Int) {
    companion object {
        fun decode(payload: ByteArray): PairCode {
            if (payload.size < 3) fail("pair code: expected 3 bytes, got ${payload.size}")
            return PairCode(((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF), payload[2].toInt() and 0xFF)
        }
    }

    fun encode(): ByteArray {
        if (code !in 0..0xFFFF) fail("pair_code.code: $code is out of range")
        // The app maps its mode argument 1 -> 2, 2 -> 4 before sending.
        val wireMode = when (mode) {
            1 -> 2
            2 -> 4
            else -> mode
        }
        return byteArrayOf(((code shr 8) and 0xFF).toByte(), (code and 0xFF).toByte(), wireMode.toByte())
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────

internal fun asciiBytes(value: String, field: String): ByteArray {
    val out = ByteArray(value.length)
    value.forEachIndexed { i, c ->
        if (c.code !in 0x20..0x7E) fail("$field: '$value' contains a non-printable character")
        out[i] = c.code.toByte()
    }
    return out
}

/** Decode a `0x39` reply: the current BLE connection interval (u16 BE). */
internal fun decodeConnInterval(payload: ByteArray): Int {
    if (payload.size < 2) fail("conn_interval: expected 2 bytes, got ${payload.size}")
    return payload.beU16(0)
}
