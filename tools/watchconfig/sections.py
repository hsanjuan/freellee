"""Section registry: which commands make up the user configuration.

Each :class:`Section` pairs a getter with a setter and a pair of codecs that
translate between the raw wire payload and a plain-Python value that YAML can
hold.  Anything without a codec yet is exposed under ``raw`` as a hex string so
nothing is silently lost.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Optional

from . import protocol
from . import codecs

Decode = Callable[[bytes], Any]
Encode = Callable[[Any], bytes]


@dataclass(frozen=True)
class Section:
    key: str                       # YAML key
    get_cmd: Optional[int] = None  # OAP command to read
    set_cmd: Optional[int] = None  # OAP command to write (None => read-only)
    decode: Optional[Decode] = None
    encode: Optional[Encode] = None
    read_only: bool = False
    note: str = ""
    # Optional predicate: return a reason string to skip this section on write
    # (used where writing would destroy something that cannot be read back).
    skip_write: Optional[Callable[[Any], Optional[str]]] = None
    # For write-only sections: a template emitted by `read` so the file documents
    # what can be set (the section is skipped on write unless it opts in).
    stub: Optional[Callable[[], Any]] = None


def hex_encode(value: Any) -> bytes:
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    return bytes.fromhex(str(value).strip().replace(" ", ""))


def hex_decode(payload: bytes) -> str:
    return payload.hex()


# ── registry ────────────────────────────────────────────────────────────────
# Filled in as payload specs are confirmed.  Anything not listed here can still
# be read with `ollee-config raw <cmd>`.

SECTIONS: list[Section] = [
    Section("version",     get_cmd=0x2A, read_only=True,
            decode=codecs.decode_version, note="firmware/device version (read-only)"),
    Section("config",      get_cmd=0x32, set_cmd=0x33,
            decode=codecs.decode_config, encode=codecs.encode_config,
            note="device settings (config register)"),
    Section("nametag",     get_cmd=0x2E, set_cmd=0x2F,
            decode=codecs.decode_nametag, encode=codecs.encode_nametag,
            note="Bluetooth name tag (6 bytes)"),
    Section("alarms",      get_cmd=0x2B, set_cmd=0x25,
            decode=codecs.decode_alarm, encode=codecs.encode_alarm,
            note="one-time alarm + hourly chime + 5 daily slots"),
    Section("timer",       get_cmd=0x2C, set_cmd=0x26,
            decode=codecs.decode_timer, encode=codecs.encode_timer,
            skip_write=codecs.timer_skip_reason,
            note="countdown timer + presets (needs overwrite: true)"),
    Section("faces",       get_cmd=0x37, set_cmd=0x36,
            decode=codecs.decode_faces, encode=codecs.encode_faces,
            note="watch faces (enabled + swipe order)"),
    Section("worldtime",   get_cmd=0x35, set_cmd=0x34,
            decode=codecs.decode_worldtime, encode=codecs.encode_worldtime,
            note="world-time offset + weekday names"),
    Section("health",      get_cmd=0x30, set_cmd=0x31,
            decode=codecs.decode_health, encode=codecs.encode_health,
            note="step goal + pulsometer target beats (OAP 'pedometer' command)"),
    # write-only (no getter exists)
    Section("timeref",     get_cmd=None, set_cmd=0x23,
            decode=codecs.decode_timeref, encode=codecs.encode_timeref,
            skip_write=codecs.timeref_skip_reason, stub=codecs.timeref_stub,
            note="time/location sync - needs overwrite: true"),
    Section("io",          get_cmd=None, set_cmd=0x38,
            decode=codecs.decode_io, encode=codecs.encode_io,
            note="DEBUG: simulate button presses (a ALARM, b MODE, c LIGHT)"),
    Section("databank",    get_cmd=0x3C, set_cmd=0x3B,
            decode=codecs.decode_databank, encode=codecs.encode_databank,
            note="10 text entries; write zeroes the 284 unused tail bytes"),
    # write-only (no corresponding getter exists)
    Section("weather",     get_cmd=None, set_cmd=0x3A,
            decode=codecs.decode_weather, encode=codecs.encode_weather,
            skip_write=codecs.weather_skip_reason, stub=codecs.weather_stub,
            note="5-day forecast push (write-only; needs overwrite: true)"),
]

# Commands this tool never sends, with no override.
#   0x21 reboots the watch into the OTA bootloader - that belongs to flash_ollee.py.
FORBIDDEN_CMDS: frozenset[int] = frozenset({0x21})

# 0x20 sub-commands that must never be sent: 0x02 spins forever in the handler and
# can only be cleared by a hard reset.
FORBIDDEN_RESET_SUBCOMMANDS: frozenset[int] = frozenset({0x02})

# Commands that destroy user data; they require --force and a typed confirmation.
DANGEROUS_CMDS: frozenset[int] = frozenset({
    0x20,  # reset: sub 0x01 = factory defaults + wipe all activity
    0x2D,  # erase all stored activity
})


def command_problem(cmd: int, payload: bytes = b"") -> Optional[str]:
    """Return a reason string if this tool must refuse to send ``cmd``."""
    if cmd in FORBIDDEN_CMDS:
        return (f"0x{cmd:02X} is not permitted by this tool: it reboots the watch into "
                f"the OTA bootloader. Use flash_ollee.py for firmware work.")
    if cmd == 0x20 and payload and payload[0] in FORBIDDEN_RESET_SUBCOMMANDS:
        return (f"0x20 sub-command 0x{payload[0]:02X} makes the firmware spin forever "
                f"(only a hard reset recovers). It is refused.")
    return None

BY_KEY = {s.key: s for s in SECTIONS}


def decode_section(section: Section, payload: bytes) -> Any:
    if section.decode is None:
        return hex_decode(payload)
    return section.decode(payload)


def encode_section(section: Section, value: Any) -> bytes:
    if section.set_cmd is None:
        raise ValueError(f"section '{section.key}' is read-only")
    if section.encode is None:
        return hex_encode(value)
    return section.encode(value)


def list_sections() -> str:
    lines = [f"{'section':<16} {'get':<6} {'set':<6}  note"]
    for s in SECTIONS:
        g = f"0x{s.get_cmd:02X}" if s.get_cmd is not None else "-"
        w = f"0x{s.set_cmd:02X}" if s.set_cmd is not None else "ro"
        lines.append(f"{s.key:<16} {g:<6} {w:<6}  {s.note}")
    return "\n".join(lines)


def list_commands() -> str:
    lines = [f"{'cmd':<6} {'kind':<7} name"]
    for c in protocol.COMMANDS:
        lines.append(f"0x{c.cmd:02X}   {c.kind:<7} {c.name}")
    return "\n".join(lines)
