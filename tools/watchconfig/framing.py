"""Nordic UART Service framing — the wire layer under OAP.

This module is self-contained (no third-party imports) so the configuration
tooling can be lifted out of the repository on its own.  ``flash_ollee.py``
imports these same helpers, so there is exactly one implementation.

Frame layout::

    [len_hi len_lo] [AA 55] [crc_hi crc_lo] [0x02] [cmd] [payload...]

* ``len`` counts itself: ``len == 6 + len(payload)``
* CRC is CRC-16/CCITT-FALSE (poly ``0x1021``, init ``0xFFFF``) over
  ``[0x02 cmd payload...]``
* ``0x02`` is the fixed "application" type byte
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

# ── BLE service / characteristic UUIDs (Nordic UART Service) ────────────────

UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
UART_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
UART_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

# ── framing constants ───────────────────────────────────────────────────────

CRC_INIT = 0xFFFF
FRAME_TYPE = 0x02
REJECT_CMD = 0x01     # watch-side rejection response
REPLY_OFFSET = 0x20   # response to command X arrives as command X + 0x20


def crc16_ccitt(data: bytes) -> int:
    """CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection)."""
    crc = CRC_INIT
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def build_nus_frame(cmd: int, payload: bytes = b"") -> bytes:
    """Build a NUS frame carrying ``cmd`` and ``payload``."""
    inner = bytes([FRAME_TYPE, cmd]) + payload
    crc = crc16_ccitt(inner)
    length = 6 + len(payload)
    return struct.pack(">H", length) + bytes([0xAA, 0x55, crc >> 8, crc & 0xFF]) + inner


@dataclass(frozen=True)
class Frame:
    cmd: int
    payload: bytes


class FrameReassembler:
    """Reassemble NUS frames from notification bytes.

    A single logical frame may arrive split across BLE notifications. Bytes that
    cannot start a valid frame (length prefix != 0x00) are discarded until the
    stream resynchronises. Malformed frames (bad magic / short length / bad CRC /
    bad type) are recorded in :attr:`errors` and skipped.
    """

    def __init__(self) -> None:
        self.buffer = bytearray()
        self.errors: list[str] = []

    def reset(self) -> None:
        self.buffer.clear()

    def feed(self, data: bytes) -> list[Frame]:
        self.buffer.extend(data)
        found: list[Frame] = []

        while len(self.buffer) >= 4:
            # Resynchronise on the frame header: 0x00, len>=6, then AA 55 magic.
            # Checking the magic here (not after the length wait) avoids stalling
            # on a stray byte whose value looks like a huge length.
            if (
                self.buffer[0] != 0
                or self.buffer[1] < 6
                or self.buffer[2] != 0xAA
                or self.buffer[3] != 0x55
            ):
                self.buffer.pop(0)
                continue

            total_len = self.buffer[1] + 2  # length field includes itself
            if len(self.buffer) < total_len:
                break

            frame = bytes(self.buffer[:total_len])
            del self.buffer[:total_len]

            body = frame[6:]
            wire_crc = (frame[4] << 8) | frame[5]
            calc_crc = crc16_ccitt(body)
            if wire_crc != calc_crc:
                self.errors.append(
                    f"CRC mismatch: wire={wire_crc:#06x} calc={calc_crc:#06x}"
                )
                continue

            if body[0] != FRAME_TYPE:
                self.errors.append(f"bad type {body[0]:#04x}")
                continue

            found.append(Frame(cmd=body[1], payload=body[2:]))

        return found
