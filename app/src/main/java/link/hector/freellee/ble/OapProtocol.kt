package link.hector.freellee.ble

import java.io.IOException
import java.util.UUID

/**
 * Ollee Watch "OAP" wire protocol, reverse-engineered for interoperability
 * from observed device traffic.
 *
 * Frames travel over the Nordic UART Service (NUS):
 *
 *     LEN_HI LEN_LO  AA 55  CK_HI CK_LO  02  CMD  payload...
 *     [--length--]   [magic][---CRC---]  [ty][cmd][--payload--]
 *
 *  - LEN  = big-endian count of the bytes after the length field (= 6 + payload).
 *  - CK   = CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection),
 *           big-endian, over `02 CMD payload`.
 *  - 0x02 = normal frame type; 0x01 marks an error reply `01 CMD CODE_BE32`.
 *  - The watch answers request CMD x with response CMD x + 0x20.
 *
 * This layer is transport-agnostic: [WatchGattClient] feeds it raw notification
 * bytes and sends the frames it builds.
 */
object OapProtocol {

    // ── Nordic UART Service ─────────────────────────────────────────────────

    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── framing constants ───────────────────────────────────────────────────

    const val FRAME_TYPE = 0x02
    const val ERROR_TYPE = 0x01
    const val REPLY_OFFSET = 0x20

    /** Largest reply payload the firmware sender will emit. */
    const val MAX_REPLY_PAYLOAD = 377

    /** Lightweight read used as the connection keep-alive probe (`0x39` conn interval). */
    const val KEEPALIVE_CMD = 0x39

    // ── command table ───────────────────────────────────────────────────────
    //
    // `op` mirrors the request method names used by the companion app; the
    // reply to a request is always `cmd + 0x20`.

    enum class Kind { GET, SET, ACTION }

    data class Command(val code: Int, val op: String, val kind: Kind, val summary: String) {
        val replyCode: Int get() = code + REPLY_OFFSET
    }

    val COMMANDS: List<Command> = listOf(
        Command(0x20, "oap_reset_req", Kind.ACTION, "Reset / factory defaults (destructive)"),
        Command(0x21, "oap_start_bootloader_req", Kind.ACTION, "Reboot into the OTA bootloader"),
        Command(0x23, "oap_set_timeref_req", Kind.SET, "Time / location reference sync"),
        Command(0x25, "oap_set_alarm_req", Kind.SET, "Alarms (main + hourly chime + 5 slots)"),
        Command(0x26, "oap_set_timer_req", Kind.SET, "Countdown timer + presets"),
        Command(0x27, "oap_get_activity_num_req", Kind.GET, "Activity record count"),
        Command(0x28, "oap_get_activity_record_req", Kind.GET, "Activity record fetch"),
        Command(0x29, "oap_set_pair_code_req", Kind.SET, "Pairing code"),
        Command(0x2A, "oap_get_version_req", Kind.GET, "Firmware / device version info"),
        Command(0x2B, "oap_get_alarm_req", Kind.GET, "Alarms (main + hourly chime + 5 slots)"),
        Command(0x2C, "oap_get_timer_req", Kind.GET, "Countdown timer state"),
        Command(0x2D, "oap_erase_activity_req", Kind.ACTION, "Erase stored activity"),
        Command(0x2E, "oap_get_nametag_req", Kind.GET, "Bluetooth name tag"),
        Command(0x2F, "oap_set_nametag_req", Kind.SET, "Bluetooth name tag"),
        Command(0x30, "oap_get_pedometer_req", Kind.GET, "Step goal + pulsometer target beats"),
        Command(0x31, "oap_set_pedometer_req", Kind.SET, "Step goal + pulsometer target beats"),
        Command(0x32, "oap_get_config_register_req", Kind.GET, "Device settings (config register)"),
        Command(0x33, "oap_set_config_register_req", Kind.SET, "Device settings (config register)"),
        Command(0x34, "oap_set_worldtime_req", Kind.SET, "World-time offset + weekday names"),
        Command(0x35, "oap_get_worldtime_req", Kind.GET, "World-time offset + weekday names"),
        Command(0x36, "oap_set_face_req", Kind.SET, "Watch faces (flags + swipe order)"),
        Command(0x37, "oap_get_face_req", Kind.GET, "Watch faces (flags + swipe order)"),
        Command(0x38, "oap_set_io_req", Kind.SET, "Inject a synthetic button gesture (debug)"),
        Command(0x39, "oap_get_conn_interval_req", Kind.GET, "BLE connection interval"),
        Command(0x3A, "oap_set_weather_req", Kind.SET, "5-day weather forecast push"),
        Command(0x3B, "oap_set_databank_req", Kind.SET, "Databank vault"),
        Command(0x3C, "oap_get_databank_req", Kind.GET, "Databank vault"),
    )

    val COMMANDS_BY_CODE: Map<Int, Command> = COMMANDS.associateBy { it.code }

    /** A readable configuration section: a getter/setter command pairing. */
    data class Section(
        val key: String,
        val getCmd: Int?,
        val setCmd: Int?,
        val readOnly: Boolean,
        val dangerous: Boolean,
        val note: String,
    )

    val SECTIONS: List<Section> = listOf(
        Section("version", 0x2A, null, readOnly = true, dangerous = false,
            note = "firmware/device version"),
        Section("config", 0x32, 0x33, readOnly = false, dangerous = false,
            note = "device settings (config register)"),
        Section("nametag", 0x2E, 0x2F, readOnly = false, dangerous = false,
            note = "Bluetooth name tag (6 bytes)"),
        Section("alarms", 0x2B, 0x25, readOnly = false, dangerous = false,
            note = "main alarm + hourly chime + 5 daily slots"),
        Section("timer", 0x2C, 0x26, readOnly = false, dangerous = false,
            note = "countdown timer + presets"),
        Section("faces", 0x37, 0x36, readOnly = false, dangerous = false,
            note = "watch faces (flags + swipe order)"),
        Section("worldtime", 0x35, 0x34, readOnly = false, dangerous = false,
            note = "world-time offset + weekday names"),
        Section("health", 0x30, 0x31, readOnly = false, dangerous = false,
            note = "step goal + pulsometer target beats"),
        Section("timeref", null, 0x23, readOnly = false, dangerous = false,
            note = "time/location sync (write-only)"),
        Section("io", null, 0x38, readOnly = false, dangerous = false,
            note = "debug: inject a button gesture (write-only)"),
        Section("databank", 0x3C, 0x3B, readOnly = false, dangerous = false,
            note = "10 text entries; a write zeroes the unused tail"),
        Section("weather", null, 0x3A, readOnly = false, dangerous = false,
            note = "5-day forecast push (write-only)"),
    )

    /** Commands this layer must never send on its own. */
    val FORBIDDEN_CMDS: Set<Int> = setOf(0x21)

    /** Commands that destroy user data. */
    val DANGEROUS_CMDS: Set<Int> = setOf(0x20, 0x2D)

    /** `0x20` sub-commands that wedge the firmware (`do {} while(true)`). */
    val FORBIDDEN_RESET_SUBCOMMANDS: Set<Int> = setOf(0x02)

    /** Return a reason string when [cmd] must not be sent, or null when it may. */
    fun commandProblem(cmd: Int, payload: ByteArray = ByteArray(0)): String? {
        if (cmd in FORBIDDEN_CMDS) {
            return "0x%02X reboots the watch into the OTA bootloader".format(cmd)
        }
        if (cmd == 0x20 && payload.isNotEmpty() && (payload[0].toInt() and 0xFF) in FORBIDDEN_RESET_SUBCOMMANDS) {
            return "0x20 sub-command 0x%02X spins the firmware forever".format(payload[0].toInt() and 0xFF)
        }
        return null
    }

    // ── framing ─────────────────────────────────────────────────────────────

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection). */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Wrap a command + payload into a full on-the-wire frame. */
    fun buildFrame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(FRAME_TYPE.toByte(), cmd.toByte()) + payload
        val crc = crc16(body)
        val after = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(),
            ((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(),
        ) + body
        val len = after.size
        return byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + after
    }

    /** Split a frame into <=[size]-byte BLE writes; the watch reassembles by length. */
    fun chunk(frame: ByteArray, size: Int = 20): List<ByteArray> {
        val step = if (size < 1) 20 else size
        val out = ArrayList<ByteArray>((frame.size + step - 1) / step)
        var start = 0
        while (start < frame.size) {
            val end = minOf(start + step, frame.size)
            out.add(frame.copyOfRange(start, end))
            start = end
        }
        return out
    }

    /** One decoded frame. */
    class Frame(
        val cmd: Int,
        val type: Int,
        val payload: ByteArray,
        val crcOk: Boolean,
        val raw: ByteArray,
    ) {
        val isError: Boolean get() = type == ERROR_TYPE

        /** The error code from an error frame, or null for a normal reply. */
        val errorCode: Long? get() = if (isError && payload.size >= 4) payload.beU32(0) else null

        override fun toString(): String =
            "Frame(cmd=0x%02X, type=0x%02X, len=%d, crcOk=%s)".format(cmd, type, payload.size, crcOk)
    }

    /** Validate magic + CRC and split a complete frame into fields. */
    fun decode(frame: ByteArray): Frame? {
        if (frame.size < 8) return null
        if (frame[2] != 0xAA.toByte() || frame[3] != 0x55.toByte()) return null
        val len = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (len < 6) return null
        val after = frame.copyOfRange(2, minOf(frame.size, 2 + len))
        if (after.size < 6) return null
        val crcRx = ((after[2].toInt() and 0xFF) shl 8) or (after[3].toInt() and 0xFF)
        val body = after.copyOfRange(4, after.size)
        if (body.size < 2) return null
        val crcOk = crc16(body) == crcRx
        val type = body[0].toInt() and 0xFF
        val cmd = body[1].toInt() and 0xFF
        val payload = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0)
        return Frame(cmd, type, payload, crcOk, frame)
    }

    /** Human-readable hex, handy for logs. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

/** Raised when the watch rejects a request or a reply cannot be understood. */
class OapException(val cmd: Int, val code: Long?, message: String) : IOException(message)

/**
 * Reassembles NUS notification chunks into complete frames using the 2-byte
 * length header. Stray bytes are discarded until the stream resynchronises on
 * the `AA 55` magic; frames with a bad CRC are skipped.
 */
class FrameReassembler {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<OapProtocol.Frame> {
        buf += data
        val out = ArrayList<OapProtocol.Frame>()
        while (buf.size >= 4) {
            val len = buf[1].toInt() and 0xFF
            if (buf[0] != 0.toByte() || len < 6 ||
                buf[2] != 0xAA.toByte() || buf[3] != 0x55.toByte()
            ) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val total = len + 2
            if (buf.size < total) break
            val frame = buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
            OapProtocol.decode(frame)?.let { if (it.crcOk) out.add(it) }
        }
        return out
    }

    fun reset() {
        buf = ByteArray(0)
    }
}
