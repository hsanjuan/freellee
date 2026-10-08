"""BLE transports.

``BleTransport`` is the real one (bleak + NUS).  ``ReplayTransport`` is a tiny
in-memory stand-in used by the offline tests and by ``--from-hex`` style usage,
so the whole codec/YAML pipeline can be exercised without a watch.
"""
from __future__ import annotations

import asyncio
from typing import Callable, Optional

try:
    from bleak import BleakClient
except ImportError:  # pragma: no cover - depends on the environment
    BleakClient = None  # type: ignore[assignment]

from .framing import UART_RX, UART_TX

# The watch drops its Bluetooth radio when it idles, so there is nothing to wait
# for: if it has not answered within a few seconds it is asleep or out of range.
CONNECT_TIMEOUT = 10.0

# The watch's NUS receive path accumulates packets in a 384-byte buffer and only
# dispatches a command once the frame's length field is satisfied.
WATCH_RX_BUFFER = 384


class BleTransport:
    """NUS transport over BLE using bleak."""

    def __init__(self, address: str, *, connect_timeout: float = CONNECT_TIMEOUT) -> None:
        if BleakClient is None:
            raise RuntimeError("bleak is not installed (pip install bleak)")
        self.address = address
        self.connect_timeout = connect_timeout
        self.client = None
        self._handler: Optional[Callable[[bytes], None]] = None

    def set_notification_handler(self, handler: Callable[[bytes], None]) -> None:
        self._handler = handler

    async def connect(self) -> None:
        self.client = BleakClient(self.address, timeout=self.connect_timeout)
        await self.client.connect()
        # bleak performs service discovery as part of connect(), so the services
        # property is already populated here (get_services() is deprecated).
        tx = self.client.services.get_characteristic(UART_TX)
        if tx is None:
            raise RuntimeError(f"NUS TX characteristic not found on {self.address}")
        await self.client.start_notify(tx, self._on_notify)
        await asyncio.sleep(0.3)  # let the subscription settle

    def _on_notify(self, _sender, data: bytearray) -> None:
        if self._handler is not None:
            self._handler(bytes(data))

    @property
    def mtu_size(self) -> int:
        """Negotiated ATT MTU, or the 23-byte default if bleak cannot tell us."""
        mtu = getattr(self.client, "mtu_size", 0) or 0
        return mtu if mtu >= 23 else 23

    async def send(self, data: bytes) -> None:
        if self.client is None or not self.client.is_connected:
            raise RuntimeError("not connected")
        rx = self.client.services.get_characteristic(UART_RX)
        if rx is None:
            raise RuntimeError("NUS RX characteristic not found")
        # One ATT write carries at most MTU-3 bytes, so anything larger (every
        # write really - the watch's own frames are up to ~230 bytes) has to be
        # split. The watch reassembles the NUS frame from the fragments: its RX
        # path appends packets to a 384-byte buffer until the length field is
        # satisfied.
        if len(data) > WATCH_RX_BUFFER:
            raise RuntimeError(
                f"NUS frame is {len(data)} bytes but the watch only reassembles "
                f"{WATCH_RX_BUFFER}; this command cannot be sent")
        chunk = self.mtu_size - 3
        for start in range(0, len(data), chunk):
            await self.client.write_gatt_char(rx, data[start:start + chunk],
                                              response=True)

    async def disconnect(self) -> None:
        if self.client is not None and self.client.is_connected:
            await self.client.disconnect()
        self.client = None


class ReplayTransport:
    """Offline transport that answers from a canned ``{request_payload: reply}`` map.

    Only useful for round-trip tests: the caller supplies the reply payload for
    each command, so encode/decode can be validated without hardware.
    """

    def __init__(self, replies: Optional[dict[int, bytes]] = None) -> None:
        from .framing import build_nus_frame

        self._build = build_nus_frame
        self.replies = dict(replies or {})
        self.sent: list[int] = []
        self._handler: Optional[Callable[[bytes], None]] = None

    def set_notification_handler(self, handler: Callable[[bytes], None]) -> None:
        self._handler = handler

    async def connect(self) -> None:  # pragma: no cover - trivial
        return None

    async def disconnect(self) -> None:  # pragma: no cover - trivial
        return None

    async def send(self, data: bytes) -> None:
        cmd = data[7]  # [len][AA55][crc][0x02][cmd]
        self.sent.append(cmd)
        reply = self.replies.get(cmd)
        if reply is not None and self._handler is not None:
            self._handler(self._build(cmd + 0x20, reply))
