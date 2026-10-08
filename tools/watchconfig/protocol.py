"""OAP wire protocol: framing, command table, and a request/response client.

OAP runs over the Nordic UART Service. A frame is::

    [len_hi len_lo] [AA 55] [crc_hi crc_lo] [0x02] [cmd] [payload...]

* ``len`` = 6 + payload length (includes the two length bytes themselves)
* CRC is CRC-16/CCITT-FALSE over ``[0x02 cmd payload...]``
* the reply to command ``X`` is command ``X + 0x20``

The wire framing itself lives in :mod:`watchconfig.framing`, which is
self-contained, so this package has no dependency on the flasher.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Optional

from .framing import (
    Frame,
    FrameReassembler,
    REPLY_OFFSET,
    REJECT_CMD,
    build_nus_frame,
)

DEFAULT_TIMEOUT = 8.0
GET = "get"
SET = "set"
ACTION = "action"


@dataclass(frozen=True)
class Command:
    cmd: int
    name: str          # request method name
    kind: str          # GET | SET | ACTION
    summary: str = ""

    @property
    def reply(self) -> int:
        return self.cmd + REPLY_OFFSET


# OAP request catalogue; a request ``cmd`` is answered with ``cmd + 0x20``.
COMMANDS: tuple[Command, ...] = (
    Command(0x20, "oap_reset_req", ACTION, "Reset / factory defaults (destructive)"),
    Command(0x21, "oap_start_bootloader_req", ACTION, "Reboot into OTA bootloader"),
    Command(0x23, "oap_set_timeref_req", SET, "Time reference sync"),
    Command(0x25, "oap_set_alarm_req", SET, "Alarms (one-time + recurring)"),
    Command(0x26, "oap_set_timer_req", SET, "Timer presets"),
    Command(0x27, "oap_get_activity_num_req", GET, "Activity record count"),
    Command(0x28, "oap_get_activity_record_req", GET, "Activity record fetch"),
    Command(0x29, "oap_set_pair_code_req", SET, "Pairing code"),
    Command(0x2A, "oap_get_version_req", GET, "Firmware / device version info"),
    Command(0x2B, "oap_get_alarm_req", GET, "Alarms (one-time + recurring)"),
    Command(0x2C, "oap_get_timer_req", GET, "Timer presets"),
    Command(0x2D, "oap_erase_activity_req", ACTION, "Erase stored activity"),
    Command(0x2E, "oap_get_nametag_req", GET, "Bluetooth name tag"),
    Command(0x2F, "oap_set_nametag_req", SET, "Bluetooth name tag"),
    Command(0x30, "oap_get_pedometer_req", GET, "Pedometer settings"),
    Command(0x31, "oap_set_pedometer_req", SET, "Pedometer settings"),
    Command(0x32, "oap_get_config_register_req", GET, "Device settings (config register)"),
    Command(0x33, "oap_set_config_register_req", SET, "Device settings (config register)"),
    Command(0x34, "oap_set_worldtime_req", SET, "World-time entries"),
    Command(0x35, "oap_get_worldtime_req", GET, "World-time entries"),
    Command(0x36, "oap_set_face_req", SET, "Watch faces (enabled + order)"),
    Command(0x37, "oap_get_face_req", GET, "Watch faces (enabled + order)"),
    Command(0x38, "oap_set_io_req", SET, "IO / hardware test (debug)"),
    Command(0x39, "oap_get_conn_interval_req", GET, "BLE connection interval"),
    Command(0x3A, "oap_set_weather_req", SET, "Weather data push"),
    Command(0x3B, "oap_set_databank_req", SET, "Databank vault"),
    Command(0x3C, "oap_get_databank_req", GET, "Databank vault"),
)

BY_CMD = {c.cmd: c for c in COMMANDS}
BY_NAME = {c.name: c for c in COMMANDS}


class OapError(RuntimeError):
    """Protocol-level failure (bad frame, rejection, timeout)."""


class OapClient:
    """Correlates OAP requests with their replies over a byte transport.

    The transport only needs ``send(bytes)`` and a way to hand incoming
    notification bytes back through :meth:`feed`.
    """

    def __init__(self, transport=None, *, timeout: float = DEFAULT_TIMEOUT,
                 retries: int = 2) -> None:
        self.transport = transport
        self.timeout = timeout
        self.retries = max(1, retries)
        self._reassembler = FrameReassembler()
        self._expected: Optional[int] = None
        self._reply: list[Frame] = []
        self._event = asyncio.Event()
        self._rejected = False

    # -- inbound -------------------------------------------------------------
    def feed(self, data: bytes) -> None:
        """Feed notification bytes (wired to the transport's notify callback)."""
        for frame in self._reassembler.feed(bytes(data)):
            if frame.cmd == REJECT_CMD:
                self._rejected = True
                self._event.set()
                return
            if frame.cmd == self._expected:
                self._reply.append(frame)
                self._event.set()

    # -- outbound ------------------------------------------------------------
    async def request(self, cmd: int, payload: bytes = b"", *,
                      timeout: Optional[float] = None,
                      retries: Optional[int] = None) -> bytes:
        """Send ``cmd`` and return the reply payload."""
        if self.transport is None:
            raise OapError("no transport attached")
        timeout = self.timeout if timeout is None else timeout
        attempts = self.retries if retries is None else max(1, retries)
        frame = build_nus_frame(cmd, payload)
        expected = cmd + REPLY_OFFSET

        for attempt in range(1, attempts + 1):
            self._reassembler.errors.clear()
            self._expected = expected
            self._rejected = False
            self._reply.clear()
            self._event.clear()

            await self.transport.send(frame)
            try:
                await asyncio.wait_for(self._event.wait(), timeout=timeout)
            except asyncio.TimeoutError:
                if attempt < attempts:
                    await asyncio.sleep(0.2)
                    continue
                raise OapError(
                    f"command 0x{cmd:02X}: no reply within {timeout}s"
                ) from None

            if self._rejected:
                raise OapError(f"command 0x{cmd:02X}: rejected by watch")
            if not self._reply:
                if attempt < attempts:
                    await asyncio.sleep(0.2)
                    continue
                raise OapError(f"command 0x{cmd:02X}: no valid frame in reply")
            return self._reply[-1].payload

        raise OapError(f"command 0x{cmd:02X}: exhausted retries")  # pragma: no cover
