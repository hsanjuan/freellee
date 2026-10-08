"""Payload codecs: raw OAP payload bytes <-> plain-Python values for YAML.

Payloads here exclude the leading command byte (the protocol layer strips it).

Anything undetermined is marked in the YAML as ``UNKNOWN``.
"""
from __future__ import annotations

import datetime
import struct
from typing import Any

# ── faces (0x36/0x37) ───────────────────────────────────────────────────────
#
# Faces are identified by name.  Ids 0..3 and 22 exist in the watch but are
# unnamed debug screens: a read never returns them and a write never accepts
# them, so the tool ignores them entirely.

FACE_NAMES: dict[int, str] = {
    0x04: "Clock",
    0x05: "Alarm",
    0x06: "World Time",
    0x07: "Stopwatch",
    0x08: "Set Clock",
    0x09: "Timer",
    0x0A: "Step Counter",
    0x0B: "Temperature",
    0x0C: "Heart Rate",
    0x0D: "Counter",
    0x0E: "Flashlight",
    0x0F: "Game A (PING)",
    0x10: "Game B (Blackjack)",
    0x11: "Sun, Moon & Tide",
    0x12: "Game C (Poker)",
    0x13: "Daily Alarm",
    0x14: "Weather",
    0x15: "Databank",
}
FACE_COUNT = 18



CLOCK_FACE_ID = 0x04          # always first, always enabled, never in the file


def decode_faces(payload: bytes) -> list[dict[str, Any]]:
    """Return the 17 configurable faces in swipe order (Clock is implied)."""
    if len(payload) != FACE_COUNT * 6:
        raise ValueError(f"faces: expected {FACE_COUNT * 6} bytes, got {len(payload)}")
    entries = []
    for i in range(FACE_COUNT):
        b = payload[i * 6:(i + 1) * 6]
        entries.append({"id": b[0], "enabled": not bool(b[2]),
                        "favorite": bool(b[4]), "order": b[5]})
    entries.sort(key=lambda e: e["order"])
    return [
        {"_comment": f"id {e['id']}", "name": FACE_NAMES.get(e["id"], f"unnamed {e['id']}"),
         "enabled": e["enabled"], "favorite": e["favorite"]}
        for e in entries if e["id"] != CLOCK_FACE_ID
    ]



FACE_NAMES_BY_ORDER = [name for face_id, name in sorted(FACE_NAMES.items())
                       if face_id != CLOCK_FACE_ID]


def encode_faces(value: Any) -> bytes:
    """Rebuild all 18 entries, with the Clock face prepended.

    The file lists the 17 configurable faces in swipe order; the name identifies
    the face, so names cannot be changed, duplicated or omitted.
    """
    if not isinstance(value, list) or len(value) != len(FACE_NAMES_BY_ORDER):
        raise InvalidValue(
            f"faces: expected exactly {len(FACE_NAMES_BY_ORDER)} entries, got "
            f"{len(value) if isinstance(value, list) else type(value).__name__}")
    names = [str(face.get("name", "")) for face in value]
    if sorted(names) != sorted(FACE_NAMES_BY_ORDER):
        missing = sorted(set(FACE_NAMES_BY_ORDER) - set(names))
        extra = sorted(set(names) - set(FACE_NAMES_BY_ORDER))
        detail = []
        if missing:
            detail.append(f"missing {missing}")
        if extra:
            detail.append(f"unknown/renamed {extra}")
        raise InvalidValue(
            "faces: the face names cannot be changed - each of the "
            f"{len(FACE_NAMES_BY_ORDER)} faces must appear exactly once "
            f"({'; '.join(detail)})")

    # Clock: always present, position 0, cannot be disabled or reordered
    out = bytearray(bytes([CLOCK_FACE_ID, 0, 0, 0, 0, 0]))
    for position, face in enumerate(value):
        face_id = next(i for i, n in FACE_NAMES.items() if n == face["name"])
        out += bytes([
            face_id,
            1,                                        # can_be_disabled
            0 if face.get("enabled", True) else 1,    # isDisabled
            1,                                        # can_be_ordered
            1 if face.get("favorite") else 0,
            position + 1,                             # order (Clock holds 0)
        ])
    return bytes(out)


WORLDTIME_WEEKDAY_LEN = 14
# The 14-byte weekday string is 7 two-character codes, Monday first.
WEEKDAY_FIELDS = ["monday", "tuesday", "wednesday", "thursday", "friday",
                  "saturday", "sunday"]



def decode_worldtime(payload: bytes) -> dict[str, Any]:
    if len(payload) < 4 + WORLDTIME_WEEKDAY_LEN:
        raise ValueError(f"worldtime: expected >= 18 bytes, got {len(payload)}")
    offset = struct.unpack(">i", payload[0:4])[0]          # signed
    names = payload[4:4 + WORLDTIME_WEEKDAY_LEN]
    weekdays = {}
    for i, field in enumerate(WEEKDAY_FIELDS):
        chunk = names[i * 2:(i + 1) * 2].rstrip(b"\x00")
        weekdays[field] = chunk.decode("ascii", "replace")
    return {
        "offset_seconds": offset,
        "weekdays": weekdays,
    }



def encode_worldtime(value: Any) -> bytes:
    if "offset_hours" in value and "offset_seconds" not in value:
        offset = int(round(float(value["offset_hours"]) * 3600))
    else:
        offset = _int_value(value.get("offset_seconds", 0), "worldtime.offset_seconds",
                            -(2 ** 31), 2 ** 31 - 1)
    if "weekdays" in value:
        raw = b""
        for field in WEEKDAY_FIELDS:
            code = str(value["weekdays"].get(field, "") or "")
            try:
                encoded = code.encode("ascii")
            except UnicodeEncodeError:
                raise InvalidValue(f"worldtime.weekdays.{field}: {code!r} is not ASCII") from None
            if len(encoded) > 2:
                raise InvalidValue(f"worldtime.weekdays.{field}: {code!r} is longer than "
                                   f"2 characters")
            if any(c < 0x20 or c > 0x7E for c in encoded):
                raise InvalidValue(f"worldtime.weekdays.{field}: printable ASCII only")
            raw += encoded.ljust(2, b"\x00")
    else:
        names = str(value.get("weekday_names", "MOTUWETHFRSASU"))
        raw = names.encode("ascii", "replace")
    if len(raw) != WORLDTIME_WEEKDAY_LEN:
        raise InvalidValue(f"worldtime: weekday codes must total exactly "
                           f"{WORLDTIME_WEEKDAY_LEN} bytes, got {len(raw)}")
    return struct.pack(">i", offset) + raw


DATABANK_ENTRIES = 10
DATABANK_ENTRY_LEN = 22           # 1 tag byte + 21 text bytes
# The tag byte is a small enum: 0 = none, 1 = phone, ...
DATABANK_TAGS = {0: "none", 1: "phone", 2: "list", 3: "note",
                 4: "reminder", 5: "password"}



def decode_databank(payload: bytes) -> list[dict[str, Any]]:
    if len(payload) != DATABANK_ENTRIES * DATABANK_ENTRY_LEN:
        raise ValueError(f"databank: expected 220 bytes, got {len(payload)}")
    out = []
    for i in range(DATABANK_ENTRIES):
        e = payload[i * DATABANK_ENTRY_LEN:(i + 1) * DATABANK_ENTRY_LEN]
        text = e[1:].split(b"\x00", 1)[0].decode("ascii", "replace")
        out.append({"tag": DATABANK_TAGS.get(e[0], e[0]), "text": text})
    return out



def encode_databank(value: Any) -> bytes:
    if not isinstance(value, list) or len(value) != DATABANK_ENTRIES:
        raise InvalidValue(f"databank: expected exactly {DATABANK_ENTRIES} entries, "
                           f"got {value!r}")
    by_name = {name: byte for byte, name in DATABANK_TAGS.items()}
    out = bytearray()
    for i, entry in enumerate(value):
        tag = entry.get("tag", "none")
        if isinstance(tag, str):
            key = tag.strip().lower()
            if key in ("", "none"):
                tag_byte = 0
            elif key in by_name:
                tag_byte = by_name[key]
            else:
                raise InvalidValue(
                    f"databank[{i}].tag: {tag!r} is not valid - choose one of "
                    f"{list(DATABANK_TAGS.values())}")
        else:
            tag_byte = _byte(tag, f"databank[{i}].tag")
        text = str(entry.get("text", ""))
        try:
            raw = text.encode("ascii")
        except UnicodeEncodeError:
            raise InvalidValue(f"databank[{i}].text: ASCII only") from None
        if any(c < 0x20 or c > 0x7E for c in raw):
            raise InvalidValue(f"databank[{i}].text: printable ASCII only")
        if len(raw) > DATABANK_ENTRY_LEN - 1:
            raise InvalidValue(f"databank[{i}].text: {len(raw)} bytes, maximum is "
                               f"{DATABANK_ENTRY_LEN - 1}")
        out += bytes([tag_byte]) + raw.ljust(DATABANK_ENTRY_LEN - 1, b"\x00")
    return bytes(out)


def decode_pair_code(payload: bytes) -> dict[str, Any]:
    if len(payload) < 3:
        raise ValueError("pair code: expected 3 bytes")
    return {"code": (payload[0] << 8) | payload[1], "mode": payload[2]}



def encode_pair_code(value: Any) -> bytes:
    code = _int_value(value["code"], "pair_code.code", 0, 0xFFFF)
    mode = int(value.get("mode", 0))
    if mode == 1:
        mode = 2
    elif mode == 2:
        mode = 4
    return bytes([(code >> 8) & 0xFF, code & 0xFF, _byte(mode, "pair_code.mode")])



WEATHER_CONDITIONS = ["Sunny", "Partly Cloudy", "Cloudy", "Rainy", "Snowy",
                      "Stormy", "Foggy"]


def decode_weather(payload: bytes) -> dict[str, Any]:
    if len(payload) != 41:
        raise ValueError(f"weather: expected 41 bytes, got {len(payload)}")
    days = []
    for i in range(5):
        d = payload[i * 8:(i + 1) * 8]
        condition = d[4] & 0x0F
        days.append({
            "month": d[1],
            "high": struct.unpack("b", d[2:3])[0],
            "low": struct.unpack("b", d[3:4])[0],
            "uv_index": d[4] >> 4,
            "condition": (WEATHER_CONDITIONS[condition]
                          if condition < len(WEATHER_CONDITIONS) else condition),
            "precipitation_pct": d[5],
            "humidity_pct": d[6],
            "feels_like": struct.unpack("b", d[7:8])[0],
        })
    return {"overwrite": False,
            "unit": "fahrenheit" if payload[40] == 1 else "celsius",
            "days": days}



def encode_weather(value: Any) -> bytes:
    days = value.get("days")
    if not isinstance(days, list) or len(days) != 5:
        raise InvalidValue(f"weather.days: expected 5 records, got {days!r}")
    unit = str(value.get("unit", "celsius")).lower()
    if unit.startswith("f"):
        unit_byte = 1
    elif unit.startswith("c"):
        unit_byte = 0
    else:
        raise InvalidValue("weather.unit: expected 'celsius' or 'fahrenheit'")
    lowered = [c.lower() for c in WEATHER_CONDITIONS]
    out = bytearray()
    for i, d in enumerate(days):
        f = f"weather.days[{i}]"
        condition = d.get("condition", 0)
        if isinstance(condition, str):
            if condition.strip().lower() not in lowered:
                raise InvalidValue(f"{f}.condition: {condition!r} is not valid - choose "
                                   f"one of {WEATHER_CONDITIONS}")
            condition = lowered.index(condition.strip().lower())
        else:
            condition = _int_value(condition, f"{f}.condition", 0,
                                   len(WEATHER_CONDITIONS) - 1,
                                   choices=set(range(len(WEATHER_CONDITIONS))))
        out += bytes([
            0,                                            # day: implied by position
            _int_value(d.get("month", 0), f"{f}.month", 0, 12),
            _int_value(d.get("high", 0), f"{f}.high", -128, 127) & 0xFF,
            _int_value(d.get("low", 0), f"{f}.low", -128, 127) & 0xFF,
            ((_int_value(d.get("uv_index", 0), f"{f}.uv_index", 0, 15) & 0x0F) << 4)
            | (condition & 0x0F),
            _int_value(d.get("precipitation_pct", 0), f"{f}.precipitation_pct", 0, 100),
            _int_value(d.get("humidity_pct", 0), f"{f}.humidity_pct", 0, 100),
            _int_value(d.get("feels_like", 0), f"{f}.feels_like", -128, 127) & 0xFF,
        ])
    out += bytes([unit_byte])
    return bytes(out)


def weather_stub() -> dict[str, Any]:
    """Template for the write-only weather section (cannot be read back)."""
    return {
        "overwrite": False,
        "unit": "celsius",
        "days": [{"_comment": f"day {i + 1}", "month": 0, "high": 0, "low": 0,
                  "uv_index": 0, "condition": "Sunny", "precipitation_pct": 0,
                  "humidity_pct": 0, "feels_like": 0} for i in range(5)],
    }


def weather_skip_reason(value: Any) -> Optional[str]:
    if not value.get("overwrite"):
        return ("overwrite is false - the watch cannot report weather back, so it "
                "is not pushed unless you opt in")
    return None


ALARM_CHIMES = ["Classic", "Breeze", "Westminster", "Retro", "Wire", "Plumber",
                "Indy", "Galactic", "Dinosaur", "Superman", "Tequila",
                "Beethoven", "Blocks", "Ghosts", "Sand"]
ALARM_SLOTS = 5
ALARM_SLOT_MODES = {0: "off", 1: "daily", 2: "one_shot"}
WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def _decode_alarm_slot(b: bytes) -> dict[str, Any]:
    chime = b[3]
    mode = b[2]
    return {
        "hours": b[0],
        "minutes": b[1],
        # slot byte +2 is both the enable and the mode: 0 = off, 1 = repeats
        # daily, 2 = one-shot (the firmware clears it to 0 after it fires).
        "mode": ALARM_SLOT_MODES.get(mode, mode),
        "chime": ALARM_CHIMES[chime] if chime < len(ALARM_CHIMES) else chime,
    }


def decode_alarm(payload: bytes) -> dict[str, Any]:
    if len(payload) != 32:
        raise ValueError(f"alarm: expected 32 bytes, got {len(payload)}")
    mask = struct.unpack("<I", payload[8:12])[0]
    day_bits = payload[5]
    return {
        # NOTE: on a 0x2B reply this byte is recomputed by the watch as
        # day_bits & (1 << today), not the stored master enable.
        "alarm_on": bool(payload[0]),
        "hourly_chime_on": bool(payload[1]),
        "snooze_on": bool(payload[2]),
        "hours": payload[3],
        "minutes": payload[4],
        # Human-readable canonical forms; the raw `day_bits` / `hourly_chime_mask`
        # integers are also accepted on write.
        "days": [WEEKDAYS[i] for i in range(7) if day_bits & (1 << (i + 1))],
        "chime": ALARM_CHIMES[payload[6]] if payload[6] < len(ALARM_CHIMES) else payload[6],
        "snooze_period": payload[7],       # minutes: 3/5/10/15/30
        # All 32 set bits are listed so the value round-trips exactly (the watch
        # only ever tests hours 0..23, but the factory default sets 24..31 too).
        "hourly_chime_hours": [h for h in range(24) if mask & (1 << h)],
        "slots": [_decode_alarm_slot(payload[12 + 4 * i:16 + 4 * i])
                  for i in range(ALARM_SLOTS)],
    }



def _encode_alarm_slot(slot: dict[str, Any], index: int) -> bytes:
    field = f"alarms.slots[{index}]"
    chime = slot.get("chime", 0)
    if isinstance(chime, str):
        if chime not in ALARM_CHIMES:
            raise InvalidValue(f"{field}.chime: {chime!r} is not valid - choose one of "
                               f"{ALARM_CHIMES}")
        chime = ALARM_CHIMES.index(chime)
    else:
        chime = _int_value(chime, f"{field}.chime", 0, len(ALARM_CHIMES) - 1)
    mode = slot.get("mode", "off")
    if isinstance(mode, str):
        if mode not in ALARM_SLOT_MODES.values():
            raise InvalidValue(f"{field}.mode: {mode!r} is not valid - choose one of "
                               f"{sorted(ALARM_SLOT_MODES.values())}")
        mode = {v: k for k, v in ALARM_SLOT_MODES.items()}[mode]
    else:
        mode = _int_value(mode, f"{field}.mode", 0, 2)
    return bytes([
        _int_value(slot.get("hours", 0), f"{field}.hours", 0, 23),
        _int_value(slot.get("minutes", 0), f"{field}.minutes", 0, 59),
        mode,
        chime,
    ])



def encode_alarm(value: Any) -> bytes:
    chime = value.get("chime", 0)
    if isinstance(chime, str):
        if chime not in ALARM_CHIMES:
            raise InvalidValue(f"alarms.chime: {chime!r} is not valid - choose one of "
                               f"{ALARM_CHIMES}")
        chime = ALARM_CHIMES.index(chime)
    else:
        chime = _int_value(chime, "alarms.chime", 0, len(ALARM_CHIMES) - 1)

    day_bits = value.get("day_bits")
    if day_bits is None:
        day_bits = 0
        for name in value.get("days", []):
            if name not in WEEKDAYS:
                raise InvalidValue(f"alarms.days: {name!r} is not a weekday - choose from "
                                   f"{WEEKDAYS}")
            day_bits |= 1 << (WEEKDAYS.index(name) + 1)
    else:
        day_bits = _byte(day_bits, "alarms.day_bits")

    mask = value.get("hourly_chime_mask")
    if mask is None:
        mask = 0
        for hour in value.get("hourly_chime_hours", []):
            mask |= 1 << _int_value(hour, "alarms.hourly_chime_hours", 0, 23)
    else:
        mask = _int_value(mask, "alarms.hourly_chime_mask", 0, 0x00FFFFFF)

    slots = value.get("slots", [])
    if not isinstance(slots, list) or len(slots) != ALARM_SLOTS:
        raise InvalidValue(f"alarms.slots: expected {ALARM_SLOTS} entries, got {slots!r}")

    head = bytes([
        int(bool(value.get("alarm_on", False))),
        int(bool(value.get("hourly_chime_on", False))),
        int(bool(value.get("snooze_on", False))),
        _int_value(value.get("hours", 0), "alarms.hours", 0, 23),
        _int_value(value.get("minutes", 0), "alarms.minutes", 0, 59),
        day_bits,
        chime,
        _int_value(value.get("snooze_period", 5), "alarms.snooze_period", 0, 255,
                   choices=set(SNOOZE_OPTIONS) | {0}),   # 0 = unset
    ])
    # 0x25 inserts a write-mode byte at offset 8: 0 = full write
    return (head + b"\x00" + struct.pack("<I", mask & 0x00FFFFFF)
            + b"".join(_encode_alarm_slot(s, i) for i, s in enumerate(slots)))


TIMER_PRESETS = 10
# Factory presets (seconds).  The watch cannot report its presets back, so these
# are the defaults, not the live values - see overwrite.
TIMER_PRESET_DEFAULTS = [180, 30, 180, 30, 0, 60, 120, 600, 900, 1800]
TIMER_ACTIONS = {0: "set duration", 1: "unknown-1", 2: "start/pause"}




def decode_timer(payload: bytes) -> dict[str, Any]:
    # The countdown value and run state are transient, so they are not surfaced:
    # only the presets are worth carrying in a configuration file.
    return {
        "overwrite": False,
        "presets_seconds": list(TIMER_PRESET_DEFAULTS),
    }


def timer_skip_reason(value: Any) -> Optional[str]:
    """Skip the timer command unless the caller opts in to sending presets.

    The 0x26 request is fixed at 44 bytes and always carries all ten presets, so
    writing a duration necessarily rewrites the presets as well.
    """
    if not value.get("overwrite"):
        return ("overwrite is false - the 44-byte request always carries all ten "
                "presets, and they cannot be read back, so sending it would replace "
                "them with these values")
    return None





def encode_timer(value: Any) -> bytes:
    presets = value.get("presets_seconds")
    if not isinstance(presets, list) or len(presets) != TIMER_PRESETS:
        raise InvalidValue(f"timer.presets_seconds: expected {TIMER_PRESETS} second values, "
                           f"got {presets!r}")
    # No duration and no action: this tool only uploads the presets, never starts
    # or sets a running timer.
    head = bytes([0, 0, 0, 0])
    return head + b"".join(
        struct.pack("<I", _int_value(p, f"timer.presets_seconds[{i}]", 0, 0xFFFFFFFF))
        for i, p in enumerate(presets))


def decode_version(payload: bytes) -> dict[str, Any]:
    if len(payload) < 32:
        raise ValueError(f"version: expected >= 32 bytes, got {len(payload)}")
    def chunk(n: int) -> str:
        return payload[n:n + 8].rstrip(b"\x00").decode("utf-8", "replace")
    out = {
        "hash_id": chunk(0),
        "hw_id": chunk(8),
        "fw_id": chunk(16),
        "serial": chunk(24),
    }
    if len(payload) >= 36:
        out["voltage"] = struct.unpack(">I", payload[32:36])[0]   # millivolts
    return out


# ── config register (0x32/0x33) ─────────────────────────────────────────────

# bit -> (key, app UI name).  Bits 7..9 are a 3-bit field handled separately.
FEATURE_BITS: dict[int, tuple[str, str]] = {
    0:  ("motion", "Motion"),
    1:  ("face_timeout_disable", "Face timeout disable"),
    2:  ("temperature", "Temperature"),
    3:  ("gestures", "Gestures"),
    4:  ("audio_mute", "Audio mute"),
    5:  ("step_chime", "Step count chime"),
    6:  ("autosleep_on", "Auto sleep"),
    10: ("heart_rate_tilt", "Heart rate tilt"),
    11: ("animations", "Animations"),
    12: ("hold_for_home", "Hold for home"),
    13: ("bluetooth_always_on", "Bluetooth always on"),
    14: ("event_counter", "Events - Counter enable"),
    15: ("event_stopwatch", "Events - Stopwatch enable"),
    16: ("backlight_tilt", "Backlight tilt"),
    17: ("bluetooth_chime", "Bluetooth chime"),
    18: ("backlight_colour_correction", "Backlight colour correction"),
    19: ("step_accuracy_mode", "Step count accuracy mode"),
    20: ("alarm_timer_backlight", "Alarm/Timer backlight"),
    21: ("bluetooth_triple_tap", "Bluetooth triple tap"),
    22: ("favorite_glance", "Favorite glance"),
    23: ("alarm_flip_snooze", "Alarm flip snooze"),
    24: ("night_mode_dnd", "Night mode DND"),
    26: ("backlight_disco", "Backlight disco mode"),
    27: ("screensaver", "Screen saver"),
}
LIGHT_HOLD_SHIFT = 7
LIGHT_HOLD_BITS = 0x7          # 3-bit field
_KNOWN_MASK = sum(1 << b for b in FEATURE_BITS) | (LIGHT_HOLD_BITS << LIGHT_HOLD_SHIFT)

# Option tables.  These are the values written in the corresponding field.
LED_BRIGHTNESS_OPTIONS = ["Max", "Level 7", "Level 6", "Level 5", "Level 4",
                          "Level 3", "Level 2", "Level 1", "Off"]
HOUR_FORMAT_OPTIONS = ["Toggle", "AM/PM", "24H"]
# Wire index -> the value written in the file.  "hold"/"toggle" are keywords; the
# numbers are seconds.
LIGHT_HOLD_VALUES = ["hold", 1, 2, 3, 5, 10, "toggle"]
LIGHT_HOLD_OPTIONS = LIGHT_HOLD_VALUES          # alias used by the help text

AUTOSLEEP_OPTIONS = {5: "5 seconds", 10: "10 seconds", 30: "30 seconds",
                     60: "60 seconds", 120: "120 seconds"}
SNOOZE_OPTIONS = {3: "3 minutes", 5: "5 minutes", 10: "10 minutes",
                  15: "15 minutes", 30: "30 minutes"}


class InvalidValue(ValueError):
    """A value in the YAML file is not acceptable for its field."""


def _enum_decode(options: list[str], value: int):
    """Wire int -> label, falling back to the raw int if out of range."""
    return options[value] if 0 <= value < len(options) else value


def _enum_encode(options: list[str], value, field: str) -> int:
    """Label or int -> wire int, rejecting anything the field cannot hold."""
    if isinstance(value, str):
        if value not in options:
            raise InvalidValue(
                f"{field}: {value!r} is not valid - choose one of {options}"
            )
        return options.index(value)
    number = int(value)
    if not 0 <= number < len(options):
        raise InvalidValue(
            f"{field}: {number} is out of range - valid values are "
            f"0..{len(options) - 1}, i.e. {options}"
        )
    return number


def _int_value(value, field: str, lo: int, hi: int, choices=None) -> int:
    """Validate an integer field against an inclusive range and/or a choice set."""
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise InvalidValue(f"{field}: expected an integer, got {value!r}") from None
    if choices is not None and number not in choices:
        raise InvalidValue(
            f"{field}: {number} is not valid - choose one of {sorted(choices)}"
        )
    if not lo <= number <= hi:
        raise InvalidValue(f"{field}: {number} is out of range {lo}..{hi}")
    return number


def _byte(value, field: str) -> int:
    return _int_value(value, field, 0, 255)


def _as_int(value: Any) -> int:
    if isinstance(value, str):
        return int(value, 0)
    return int(value)



def decode_config(payload: bytes) -> dict[str, Any]:
    if len(payload) != 15:
        raise ValueError(f"config: expected 15 bytes, got {len(payload)}")
    mask, autosleep, led = struct.unpack(">III", payload[0:12])
    features = {key: bool(mask & (1 << bit)) for bit, (key, _) in FEATURE_BITS.items()}
    config = {
        "features": features,
        "light_hold": LIGHT_HOLD_VALUES[(mask >> LIGHT_HOLD_SHIFT) & LIGHT_HOLD_BITS],
        "autosleep_period": autosleep,           # seconds
        "led_rgb": dict(
            {"red": (led >> 16) & 0xFF,
             "green": (led >> 8) & 0xFF,
             "blue": led & 0xFF},
            **({"alpha": (led >> 24) & 0xFF} if (led >> 24) else {}),
        ),
        "led_brightness": _decode_brightness(payload[12]),
        "hour_format": _enum_decode(HOUR_FORMAT_OPTIONS, payload[13]),
        "night_sleep": _decode_night_sleep(payload[14]),
    }
    leftover = mask & ~_KNOWN_MASK
    if leftover:
        # only worth showing when the watch has something we do not understand
        config["unknown_bits"] = leftover
    return config


def _decode_brightness(wire: int) -> int:
    """The watch counts down from full brightness: 0 is brightest, 8 is off.

    The file is written the natural way round instead - 0 is off, 8 is brightest -
    so the value is flipped here.
    """
    return 8 - wire if 0 <= wire <= 8 else wire


def _encode_brightness(value: Any) -> int:
    return 8 - _int_value(value, "config.led_brightness", 0, 8)


def _decode_night_sleep(value: int) -> Any:
    """0 = off; otherwise the wire value is ``hour + 1`` (1 -> 0:00 ... 24 -> 23:00).

    When an hour is selected the watch face stays off until a button is pressed.
    """
    if value == 0:
        return "off"
    if 1 <= value <= 24:
        return f"{value - 1:02d}:00"
    return value                                   # unexpected value: pass through


def _encode_night_sleep(value: Any) -> int:
    if isinstance(value, str):
        text = value.strip().lower()
        if text in ("off", "none", "disabled", ""):
            return 0
        if ":" in text:
            hour = _int_value(text.split(":")[0], "config.night_sleep", 0, 23)
            return hour + 1
        return _int_value(int(text, 0), "config.night_sleep", 0, 24)
    return _int_value(value, "config.night_sleep", 0, 24)


def _pack_rgb(value: Any, field: str = "config.led_rgb") -> int:
    if isinstance(value, dict):
        # alpha is unused by the backlight and defaults to the firmware's 0x00;
        # it is only surfaced (and preserved) when the watch holds something else
        r = _byte(value.get("red", 0), f"{field}.red")
        g = _byte(value.get("green", 0), f"{field}.green")
        b = _byte(value.get("blue", 0), f"{field}.blue")
        a = _byte(value.get("alpha", 0), f"{field}.alpha")
        return (a << 24) | (r << 16) | (g << 8) | b
    return _int_value(_as_int(value), field, 0, 0xFFFFFFFF)



def _enum_encode_ci(options: list[str], value: Any, field: str) -> int:
    """Like _enum_encode but case-insensitive for labels."""
    if isinstance(value, str):
        lowered = [o.lower() for o in options]
        if value.strip().lower() not in lowered:
            raise InvalidValue(f"{field}: {value!r} is not valid - choose one of {options}")
        return lowered.index(value.strip().lower())
    return _enum_encode(options, value, field)


def _light_hold_index(value: Any) -> int:
    if isinstance(value, str):
        wanted = value.strip().lower().replace(" ", "")
        for i, option in enumerate(LIGHT_HOLD_VALUES):
            if isinstance(option, str) and option.replace(" ", "") == wanted:
                return i
        raise InvalidValue(
            f"config.light_hold: {value!r} is not valid - choose one of "
            f"{LIGHT_HOLD_VALUES}")
    number = int(value)
    if number not in [v for v in LIGHT_HOLD_VALUES if isinstance(v, int)]:
        raise InvalidValue(
            f"config.light_hold: {number} is not valid - choose one of "
            f"{LIGHT_HOLD_VALUES}")
    return LIGHT_HOLD_VALUES.index(number)


def encode_config(value: Any) -> bytes:
    mask = int(value.get("unknown_bits", 0)) & ~_KNOWN_MASK
    for key, enabled in (value.get("features") or {}).items():
        for bit, (name, _) in FEATURE_BITS.items():
            if name == key:
                if enabled:
                    mask |= 1 << bit
                break
        else:
            raise InvalidValue(f"config.features.{key}: unknown feature name")
    mask |= _light_hold_index(value.get("light_hold", "hold")) << LIGHT_HOLD_SHIFT
    autosleep = _int_value(value.get("autosleep_period", 5), "config.autosleep_period",
                           0, 0xFFFFFFFF, choices=set(AUTOSLEEP_OPTIONS))
    brightness = _encode_brightness(value.get("led_brightness", 0))
    return (struct.pack(">III", mask, autosleep, _pack_rgb(value.get("led_rgb", 0)))
            + bytes([
                brightness,
                _enum_encode_ci(HOUR_FORMAT_OPTIONS, value.get("hour_format", "Toggle"),
                                "config.hour_format"),
                _encode_night_sleep(value.get("night_sleep", 0)),
            ]))


PULSOMETER_BEATS = [5, 10, 20, 30]


def decode_health(payload: bytes) -> dict[str, Any]:
    if len(payload) != 5:
        raise ValueError(f"health: expected 5 bytes, got {len(payload)}")
    return {"step_goal": struct.unpack(">I", payload[0:4])[0],
            "pulsometer_target_beats": payload[4]}






def encode_health(value: Any) -> bytes:
    goal = _int_value(value.get("step_goal", 5000), "health.step_goal", 100, 500000)
    beats = _int_value(value.get("pulsometer_target_beats", 20),
                       "health.pulsometer_target_beats", 0, 255,
                       choices=set(PULSOMETER_BEATS))
    return struct.pack(">I", goal) + bytes([beats])


NAMETAG_LEN = 6


def decode_nametag(payload: bytes) -> str:
    if len(payload) != NAMETAG_LEN:
        raise ValueError(f"nametag: expected {NAMETAG_LEN} bytes, got {len(payload)}")
    return payload.rstrip(b"\x00").decode("utf-8", "replace")





def encode_nametag(value: Any) -> bytes:
    text = str(value)
    try:
        raw = text.encode("ascii")
    except UnicodeEncodeError:
        raise InvalidValue(f"nametag: {value!r} is not ASCII") from None
    if any(c < 0x20 or c > 0x7E for c in raw):
        raise InvalidValue(f"nametag: {value!r} contains a non-printable character")
    if len(raw) > NAMETAG_LEN:
        raise InvalidValue(f"nametag: {value!r} is {len(raw)} characters, "
                           f"maximum is {NAMETAG_LEN}")
    return raw.ljust(NAMETAG_LEN, b"\x00")


def decode_conn_interval(payload: bytes) -> int:
    if len(payload) != 2:
        raise ValueError(f"conn_interval: expected 2 bytes, got {len(payload)}")
    return struct.unpack(">H", payload)[0]


def decode_io(payload: bytes) -> dict[str, Any]:
    if len(payload) != 3:
        raise ValueError("io: expected 3 bytes")
    return {"button_a": payload[0], "button_b": payload[1], "button_c": payload[2]}



def encode_io(value: Any) -> bytes:
    return bytes([_byte(value.get("button_a", 0), "io.button_a"),
                  _byte(value.get("button_b", 0), "io.button_b"),
                  _byte(value.get("button_c", 0), "io.button_c")])


def _lunitidal_minutes(value: Any) -> int:
    """Accept minutes as an int, or an 'HH:MM' string."""
    if isinstance(value, str):
        text = value.strip()
        if not text or text in ("-1", "none", "off"):
            return -1
        if ":" in text:
            hours, _, minutes = text.partition(":")
            return int(hours) * 60 + int(minutes or 0)
        return int(text)
    return int(value)


def decode_timeref(payload: bytes) -> dict[str, Any]:
    if len(payload) != 20:
        raise ValueError(f"timeref: expected 20 bytes, got {len(payload)}")
    utc, tz, lat, lon, ms, lunitidal = struct.unpack("<IiiiHh", payload)
    return {
        "utc_seconds": utc,
        "timezone_offset_seconds": tz,
        "latitude": lat / 1000.0,
        "longitude": lon / 1000.0,
        "milliseconds": ms,
        "lunitidal_interval_minutes": lunitidal,
    }



def _host_utc_offset() -> int:
    """The host's current UTC offset in seconds (used when the file omits it)."""
    offset = datetime.datetime.now().astimezone().utcoffset()
    return int(offset.total_seconds()) if offset else 0


def encode_timeref(value: Any) -> bytes:
    now = datetime.datetime.now(datetime.timezone.utc)
    utc = value.get("utc_seconds")
    utc = _int_value(utc, "timeref.utc_seconds", 0, 0xFFFFFFFF) if utc is not None \
        else int(now.timestamp())
    millis = value.get("milliseconds")
    millis = _int_value(millis, "timeref.milliseconds", 0, 999) if millis is not None \
        else now.microsecond // 1000
    tz = value.get("timezone_offset_seconds")
    tz = _int_value(tz, "timeref.timezone_offset_seconds", -86400, 86400) if tz is not None \
        else _host_utc_offset()
    lat = _int_value(int(round(float(value.get("latitude", 0) or 0) * 1000)),
                     "timeref.latitude", -90 * 1000, 90 * 1000)
    lon = _int_value(int(round(float(value.get("longitude", 0) or 0) * 1000)),
                     "timeref.longitude", -180 * 1000, 180 * 1000)
    lti = _lunitidal_minutes(value.get("lunitidal_interval_minutes", -1))
    if not -1 <= lti <= 1440:
        raise InvalidValue(f"timeref.lunitidal_interval_minutes: {lti} is out of range "
                           f"(use 0-1440 minutes, 'HH:MM', or -1 for no data)")
    return struct.pack("<IiiiHh", utc, tz, lat, lon, millis, lti)


def timeref_stub() -> dict[str, Any]:
    """Template for the write-only time/location sync."""
    return {
        "overwrite": False,
        "latitude": 0.0,
        "longitude": 0.0,
        "timezone_offset_seconds": _host_utc_offset(),
        "lunitidal_interval_minutes": -1,
    }


def timeref_skip_reason(value: Any) -> Optional[str]:
    if not value.get("overwrite"):
        return ("overwrite is false - this is a live time/location sync, not stored "
                "configuration, so it is not sent unless you opt in")
    return None


