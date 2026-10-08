#!/usr/bin/env python3
"""
Flash custom firmware to Ollee Watch over BLE.

Port of ota_transfer.swift from ollee-customizer, rewritten for Linux/bleak.
Implements the OTA transport protocol used by the official iPhone app.

Usage:
    python3 flash_ollee.py --mac AA:BB:CC:DD:EE:FF [--firmware path.bin]

Prerequisites:
    pip install bleak cryptography
    Run as root or with appropriate BLE permissions (systemd-udev, etc.)

Design notes:
    * The protocol core (CRC, framing, reassembly, image validation) does not
      import ``bleak`` and can be unit-tested without BLE hardware or a radio.
    * Image validation is structural (stack pointer / reset vector / descriptor
      magic), not a pinned SHA-256, so any well-formed image can be flashed.
      Pass ``--expected-sha256`` to opt into exact image pinning.
    * The wire behaviour (frames, 20-byte chunks, 20 ms pacing, begin/finish
      signals) is unchanged from the known-good script.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import struct
import sys
import time
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Optional

try:  # BLE is optional so the protocol core stays importable/testable.
    from bleak import BleakClient, BleakError, BleakScanner

    _BLEAK_IMPORT_ERROR: Optional[ImportError] = None
except ImportError as _exc:  # pragma: no cover - depends on environment
    BleakClient = None  # type: ignore[assignment]
    BleakError = None  # type: ignore[assignment,misc]
    BleakScanner = None  # type: ignore[assignment]
    _BLEAK_IMPORT_ERROR = _exc


# ── Image layout constants ──────────────────────────────────────────────────

LOAD_BASE = 0x08007000  # OTA application link address; file offset = addr - base
EXPECTED_SP = 0x20030000
EXPECTED_MAGIC = 0x94448A29
SRAM_RANGE = (0x20000000, 0x20040000)
FLASH_RANGE = (0x08000000, 0x08100000)
MIN_IMAGE_SIZE = 0x1000  # 4 KiB
MAX_IMAGE_SIZE = 1 << 20  # 1 MiB (whole STM32WB55 flash)

# ── BLE Services & Characteristics ──────────────────────────────────────────
#
# The Nordic UART Service UUIDs and the NUS framing live in ``watchconfig.framing``
# so this flasher and the configuration tooling share one implementation.

OTA_SERVICE_BOOT = "0000fe20-cc7a-482a-984a-7f2ed5b3e58f"
OTA_CONTROL = "0000fe22-8e22-4541-9d4c-21edae82ed19"
OTA_CONFIRM = "0000fe23-8e22-4541-9d4c-21edae82ed19"
OTA_RAW = "0000fe24-8e22-4541-9d4c-21edae82ed19"

# ── Protocol constants ──────────────────────────────────────────────────────

CHUNK_SIZE = 20  # bootloader's per-write limit
BEGIN_CMD = bytes([0x02, 0x00, 0x70, 0x00])  # START user app upload @ 0x08007000
FINISH_CMD = bytes([0x07, 0x00, 0x70, 0x00])  # upload finished

NUS_REPLY_TIMEOUT = 12.0
CONNECT_TIMEOUT = 70.0
UPLOAD_TIMEOUT = 600.0
FINISH_TIMEOUT = 30.0

FIRMWARE_VERSION_PREFIX = "DEADBEEF"
DEFAULT_FIRMWARE = "OW-FW-APP_HW_01.05.00_FW_00.01.10.bin"

# (cmd, label, expected byte length) — None means "any length".
QUERIES: list[tuple[int, str, Optional[int]]] = [
    (0x2A, "firmware_string", None),
    (0x32, "settings", 15),
    (0x2B, "status", 32),
    (0x2E, "name_tag", 6),
    (0x37, "capabilities", 108),
]
# 0x2A is version text and legitimately changes across firmware; never compare it.
SNAPSHOT_COMPARE_EXCLUDE = {0x2A}


# ── Logging ─────────────────────────────────────────────────────────────────

class Log:
    """Tiny leveled logger; timestamps are opt-in to match legacy output."""

    timestamps = False
    verbose = False

    @classmethod
    def _emit(cls, level: str, msg: str) -> None:
        prefix = ""
        if cls.timestamps:
            prefix = time.strftime("%Y-%m-%dT%H:%M:%S ") 
        if level and level != "INFO":
            prefix += f"[{level}] "
        print(prefix + msg, flush=True)

    @classmethod
    def info(cls, msg: str) -> None:
        cls._emit("INFO", msg)

    @classmethod
    def warn(cls, msg: str) -> None:
        cls._emit("WARN", msg)

    @classmethod
    def error(cls, msg: str) -> None:
        cls._emit("ERROR", msg)

    @classmethod
    def debug(cls, msg: str) -> None:
        if cls.verbose:
            cls._emit("DEBUG", msg)


# ── Protocol helpers (shared with the configuration tooling) ─────────────────

from watchconfig.framing import (  # noqa: E402  (kept near its old location)
    CRC_INIT,
    REPLY_OFFSET,
    REJECT_CMD,
    UART_RX,
    UART_SERVICE,
    UART_TX,
    Frame,
    FrameReassembler,
    build_nus_frame,
    crc16_ccitt,
)



# ── Firmware validation ─────────────────────────────────────────────────────

class FirmwareError(ValueError):
    pass


@dataclass(frozen=True)
class Firmware:
    path: Path
    data: bytes
    sha256: str
    sp: int
    reset: int
    magic_offset: int

    @property
    def size(self) -> int:
        return len(self.data)


def validate_firmware(
    path: Path,
    *,
    expected_sp: int = EXPECTED_SP,
    expected_magic: int = EXPECTED_MAGIC,
    expected_sha256: Optional[str] = None,
) -> Firmware:
    """Structural validation of an OTA application image.

    Checks (in order):
      * non-empty and 8-byte aligned, within plausible size bounds;
      * initial stack pointer == ``expected_sp`` (inside SRAM, 8-aligned);
      * reset vector is a Thumb address inside the image;
      * descriptor pointer at 0x140 is a 4-aligned flash address;
      * descriptor magic word == ``expected_magic``;
      * optional: SHA-256 == ``expected_sha256``.
    """
    if not path.exists():
        raise FirmwareError(f"Firmware file not found: {path}")
    data = path.read_bytes()

    if len(data) == 0:
        raise FirmwareError("Firmware file is empty")
    if len(data) % 8 != 0:
        raise FirmwareError(f"Firmware size {len(data)} is not a multiple of 8")
    if not (MIN_IMAGE_SIZE <= len(data) <= MAX_IMAGE_SIZE):
        raise FirmwareError(
            f"Firmware size {len(data)} outside plausible range "
            f"{MIN_IMAGE_SIZE}..{MAX_IMAGE_SIZE}"
        )

    digest = hashlib.sha256(data).hexdigest()

    sp = struct.unpack("<I", data[0:4])[0]
    if not (SRAM_RANGE[0] <= sp <= SRAM_RANGE[1]):
        raise FirmwareError(f"Stack pointer {sp:#010x} is not in SRAM")
    if sp % 8 != 0:
        raise FirmwareError(f"Stack pointer {sp:#010x} is not 8-byte aligned")
    if sp != expected_sp:
        raise FirmwareError(
            f"Stack pointer mismatch: expected {expected_sp:#010x}, got {sp:#010x} "
            f"(override with --expected-sp if intentional)"
        )

    reset = struct.unpack("<I", data[4:8])[0]
    if reset % 2 != 1:
        raise FirmwareError(f"Reset vector {reset:#010x} is not a Thumb address")
    if not (LOAD_BASE <= reset < LOAD_BASE + len(data)):
        raise FirmwareError(f"Reset vector {reset:#010x} points outside the image")

    if len(data) < 0x144:
        raise FirmwareError("Image too small to contain the descriptor pointer at 0x140")
    ptr = struct.unpack("<I", data[0x140:0x144])[0]
    if not (FLASH_RANGE[0] <= ptr <= FLASH_RANGE[1]):
        raise FirmwareError(f"Descriptor pointer {ptr:#010x} is not in flash")
    if ptr % 4 != 0:
        raise FirmwareError(f"Descriptor pointer {ptr:#010x} is not 4-byte aligned")

    magic_offset = ptr - LOAD_BASE
    if magic_offset < 0 or magic_offset + 4 > len(data):
        raise FirmwareError(
            f"Descriptor pointer {ptr:#010x} is outside the image "
            f"(offset {magic_offset:#x})"
        )
    marker = struct.unpack("<I", data[magic_offset:magic_offset + 4])[0]
    if marker != expected_magic:
        raise FirmwareError(
            f"Descriptor magic corrupted at offset {magic_offset:#x}: "
            f"expected {expected_magic:#010x}, got {marker:#010x}"
        )

    if expected_sha256 is not None and digest.lower() != expected_sha256.lower():
        raise FirmwareError(
            f"SHA-256 mismatch: expected {expected_sha256.lower()}, got {digest}"
        )

    Log.info(f"[PRECHECK] SHA256: {digest}")
    Log.info(f"[PRECHECK] Size:   {len(data)} bytes")
    Log.info(f"[PRECHECK] SP:     {sp:#010x} OK")
    Log.info(f"[PRECHECK] Reset:  {reset:#010x} OK")
    Log.info(f"[PRECHECK] Magic:  {marker:#010x} OK  (ptr={ptr:#010x})")

    return Firmware(
        path=path, data=data, sha256=digest, sp=sp, reset=reset, magic_offset=magic_offset
    )


# ── BLE helpers ─────────────────────────────────────────────────────────────

def _has_property(char: object, *names: str) -> bool:
    props = set(getattr(char, "properties", []) or [])
    return bool(props & set(names))


class Phase(str, Enum):
    IDLE = "init"
    BACKUP = "backup"
    ENTERING = "entering"
    BOOT = "boot"
    UPLOADING = "uploading"
    FINISHING = "finishing"
    RESTARTING = "restarting"
    VERIFY = "verify"
    DONE = "done"


class FlashError(RuntimeError):
    pass


# ── Flash session ───────────────────────────────────────────────────────────

class OlleeFlasher:
    """Manages the OTA flash lifecycle."""

    def __init__(
        self,
        mac: str,
        firmware: Firmware,
        *,
        bootloader: Optional[str] = None,
        dry_run: bool = False,
        chunk_size: int = CHUNK_SIZE,
        write_delay: float = 0.02,
        write_with_response: bool = False,
        scan_timeout: float = 10.0,
        connect_timeout: float = CONNECT_TIMEOUT,
        verify: bool = True,
        backup_only: bool = False,
        reboot_wait: float = 12.0,
        reboot_via_confirm: bool = False,
        nus_retries: int = 3,
        snapshot_path: Path | None = None,
    ) -> None:
        self.mac = mac
        self.fw = firmware.data
        self.bootloader = bootloader
        self.dry_run = dry_run
        self.chunk_size = chunk_size
        self.write_delay = write_delay
        self.write_with_response = write_with_response
        self.scan_timeout = scan_timeout
        self.connect_timeout = connect_timeout
        self.verify = verify
        self.backup_only = backup_only
        self.reboot_wait = max(0.0, reboot_wait)
        self.reboot_via_confirm = reboot_via_confirm
        self.nus_retries = max(1, nus_retries)
        # Default: pre-flash-snapshot.json lives in the parent analysis repo.
        if snapshot_path is None:
            snapshot_path = Path(__file__).resolve().parent.parent / "pre-flash-snapshot.json"
        self.snapshot_path = snapshot_path

        self.client = None
        self.phase = Phase.IDLE
        self.expected_reply: Optional[int] = None
        self.reassembler = FrameReassembler()

        self._response_event = asyncio.Event()
        self._response_data: list[Frame] = []
        self._rejected = False
        self._rx_bytes = 0
        self._ota_completion_seen = False
        self._ota_completion_event = asyncio.Event()

        self._expect_disconnect = False
        self._disconnected = False
        self._uart_ready = False

    # -- connection ---------------------------------------------------------

    def _make_client(self, address: str):
        if BleakClient is None:
            raise FlashError(f"bleak is not installed: {_BLEAK_IMPORT_ERROR}")
        kwargs = {"disconnected_callback": self._on_disconnect}
        try:
            return BleakClient(address, timeout=self.connect_timeout, **kwargs)
        except TypeError:  # newer bleak dropped the positional timeout kwarg
            return BleakClient(address, **kwargs)

    async def connect(self, address: Optional[str] = None) -> None:
        address = address or self.mac
        if self.dry_run:
            Log.info(f"\n[DRY-RUN] Would connect to Ollee Watch ({address})")
            return

        Log.info(f"\n[BLE] Connecting to {address}...")
        self._disconnected = False
        self._uart_ready = False
        self.reassembler.reset()
        self.client = self._make_client(address)
        await self._with_timeout(
            self.client.connect(), self.connect_timeout, f"Connect to {address} timed out"
        )
        Log.info(f"[BLE] Connected: {self.client.is_connected}")

    def _on_disconnect(self, client) -> None:
        if self._expect_disconnect:
            Log.debug(f"[BLE] Expected disconnect in phase {self.phase.value}")
            return
        Log.warn(f"\n[WARN] Disconnected unexpectedly! Phase: {self.phase.value}")
        self._disconnected = True

    async def _with_timeout(self, awaitable, seconds: float, reason: str):
        try:
            return await asyncio.wait_for(awaitable, seconds)
        except asyncio.TimeoutError:
            raise FlashError(reason) from None

    def _check_connected(self) -> None:
        if self._disconnected:
            raise FlashError(f"Device disconnected during phase '{self.phase.value}'")
        if self.client is None or not self.client.is_connected:
            raise FlashError(f"Not connected during phase '{self.phase.value}'")

    async def _load_services(self):
        """Return the GATT service collection for the current connection.

        bleak runs service discovery during ``connect()``, so the ``services``
        property is already populated here. The collection is per-connection, so
        a reconnect (which builds a fresh client) re-discovers it - which matters
        because the service set changes between the watch (UART) and the OTA
        bootloader.
        """
        try:
            return self.client.services
        except BleakError as exc:  # pragma: no cover - depends on a real stack
            raise FlashError(f"Service discovery failed: {exc}") from exc

    # -- service discovery --------------------------------------------------

    async def discover_services(self, service_uuid: str = "") -> None:
        if self.dry_run:
            Log.info("[DRY-RUN] Would discover services" + (f" ({service_uuid})" if service_uuid else ""))
            return
        if self.client is None:
            raise FlashError("Not connected")
        await self._load_services()
        if service_uuid:
            found = [s for s in self.client.services if str(s.uuid) == service_uuid]
            if not found:
                raise FlashError(f"Service {service_uuid} not found")
            Log.info(f"[BLE] Service {service_uuid} discovered")

    async def _subscribe_uart(self, force: bool = False) -> None:
        """Subscribe to UART TX (idempotent, once per connection).

        Re-subscribing mid-stream can drop in-flight notifications, and a long
        reply (e.g. 0x2A) is split across several notifications, so we subscribe
        once and let the reassembler accumulate across the whole connection.
        """
        if self._uart_ready and not force:
            return
        self._check_connected()
        tx_char = self.client.services.get_characteristic(UART_TX)
        if tx_char is None:
            raise FlashError("UART TX characteristic not found")
        if not _has_property(tx_char, "notify", "indicate"):
            raise FlashError("UART TX characteristic does not support notify/indicate")
        await self.client.start_notify(tx_char, self._nus_notification_callback)
        await asyncio.sleep(0.3)  # let the subscription establish
        self._uart_ready = True

    # -- NUS query path -----------------------------------------------------

    async def read_nus(
        self,
        cmd: int,
        timeout: float = NUS_REPLY_TIMEOUT,
        retries: Optional[int] = None,
    ) -> bytes:
        """Send a NUS command and wait for the matching response payload.

        ``retries`` overrides the session default (used by lightweight probes).
        """
        if self.dry_run:
            Log.info(f"[NUS] Sending command 0x{cmd:02X} (expecting 0x{cmd + REPLY_OFFSET:02X})")
            mock = self._mock_nus_response(cmd)
            frame = build_nus_frame(cmd, mock)
            Log.debug(f"[NUS]   frame={frame.hex()}")
            for parsed in self.reassembler.feed(frame):
                Log.debug(f"  [NUS_REPLY] 0x{parsed.cmd:02X} payload={parsed.payload.hex()}")
            return mock

        self._check_connected()
        rx_char = self.client.services.get_characteristic(UART_RX)
        if rx_char is None:
            raise FlashError("UART RX characteristic not found")

        frame = build_nus_frame(cmd)
        # Use an acknowledged write when the characteristic supports it (as the
        # reference does); fall back to write-without-response otherwise.
        use_response = _has_property(rx_char, "write")
        attempts = self.nus_retries if retries is None else max(1, retries)
        for attempt in range(1, attempts + 1):
            # A reply can be split across notifications and/or arrive after a
            # retry, so the reassembler buffer is intentionally NOT reset here.
            self.reassembler.errors.clear()
            self.expected_reply = cmd + REPLY_OFFSET
            self._rejected = False
            self._rx_bytes = 0
            self._response_data.clear()
            self._response_event.clear()

            await self._subscribe_uart()

            suffix = f" [attempt {attempt}/{attempts}]" if attempt > 1 else ""
            Log.info(
                f"[NUS] Sending command 0x{cmd:02X} "
                f"(expecting 0x{self.expected_reply:02X}){suffix}"
            )
            await self.client.write_gatt_char(rx_char, frame, response=use_response)

            try:
                await asyncio.wait_for(self._response_event.wait(), timeout=timeout)
            except asyncio.TimeoutError:
                if self.reassembler.errors:
                    Log.warn(f"[NUS] Parse errors: {self.reassembler.errors[-3:]}")
                buffered = len(self.reassembler.buffer)
                if attempt < attempts:
                    Log.warn(
                        f"[NUS] No reply to 0x{cmd:02X} "
                        f"({self._rx_bytes} notification bytes, {buffered} buffered); retrying..."
                    )
                    await asyncio.sleep(0.5)
                    continue
                raise FlashError(
                    f"No response to command 0x{cmd:02X} within {timeout}s "
                    f"({self._rx_bytes} notification bytes, {buffered} buffered)"
                ) from None

            if self._rejected:
                raise FlashError(f"Watch rejected command 0x{cmd:02X}")
            if not self._response_data:
                if attempt < attempts:
                    await asyncio.sleep(0.5)
                    continue
                raise FlashError(f"Notification received but no valid frame for 0x{cmd:02X}")
            return self._response_data[-1].payload

    def _mock_nus_response(self, cmd: int) -> bytes:
        responses = {
            0x2A: b"DEADBEEF01.05.0000.01.10DEADBEEF",
            0x32: bytes(range(15)),
            0x2B: bytes(range(32)),
            0x2E: b"Ollee\x00",
            0x37: bytes(108),
            0x21: b"\x01",
        }
        return responses.get(cmd, b"")

    def _nus_notification_callback(self, sender, data: bytearray) -> None:
        self._rx_bytes += len(data)
        Log.debug(f"[NUS] rx {bytes(data).hex()}")
        for frame in self.reassembler.feed(bytes(data)):
            Log.debug(f"  [NUS_REPLY] 0x{frame.cmd:02X} payload={frame.payload.hex()}")
            if frame.cmd == REJECT_CMD:
                self._rejected = True
                self._response_event.set()
                return
            if frame.cmd == self.expected_reply:
                self._response_data.append(frame)
                self._response_event.set()

    # -- device discovery ---------------------------------------------------

    async def _find_device(self, address: str, name_regex: Optional[str] = None, timeout: float = 0.0):
        if BleakScanner is None:
            raise FlashError(f"bleak is not installed: {_BLEAK_IMPORT_ERROR}")
        timeout = timeout or self.scan_timeout
        try:
            device = await BleakScanner.find_device_by_address(address, timeout=timeout)
        except Exception:  # address form unsupported on this backend
            device = None
        if device is not None:
            return device
        if name_regex:
            try:
                devices = await BleakScanner.discover(timeout=timeout)
            except Exception as exc:
                raise FlashError(f"BLE scan failed: {exc}") from exc
            for candidate in devices:
                if candidate.name and name_regex.lower() in candidate.name.lower():
                    return candidate
        return None

    async def _find_stm_ota_device(self, timeout: Optional[float] = None):
        if self.bootloader:
            Log.info(f"[OTA] Using configured bootloader address {self.bootloader}")
            device = await self._find_device(self.bootloader, name_regex="STM", timeout=timeout)
            if device is None:
                raise FlashError(f"Configured bootloader {self.bootloader} not found")
            Log.info(f"[OTA] Found STM_OTA at {device.address} ({device.name})")
            return device

        start_addr = int(self.mac.split(":")[-1], 16)
        ota_mac = ":".join(self.mac.split(":")[:-1]) + f":{start_addr + 1:02X}"
        Log.info("[OTA] Scanning for STM_OTA bootloader...")
        device = await self._find_device(ota_mac, name_regex="STM", timeout=timeout)
        if device is None:
            raise FlashError(
                f"STM_OTA bootloader not found (tried {ota_mac} and name scan). "
                f"The watch may not have entered OTA mode, or pass --bootloader."
            )
        Log.info(f"[OTA] Found STM_OTA at {device.address} ({device.name})")
        return device

    # -- phase 1: backup ----------------------------------------------------

    async def backup_settings(self) -> dict[str, str]:
        Log.info("\n[PHASE 1] Backing up current settings...")
        self.phase = Phase.BACKUP
        snapshots: dict[str, str] = {}

        for cmd, label, _expected_len in QUERIES:
            try:
                data = await self.read_nus(cmd)
                if cmd == 0x2A:
                    version = data[:32].decode("ascii", errors="replace")
                    if not version.startswith(FIRMWARE_VERSION_PREFIX):
                        raise FlashError(
                            f"Firmware version mismatch: got '{version}', "
                            f"expected prefix '{FIRMWARE_VERSION_PREFIX}'"
                        )
                    Log.info(f"  firmware version string: {version}")
                snapshots[label] = data.hex()
                Log.info(f"  {label}: {len(data)} bytes")
            except Exception as exc:  # noqa: BLE001 - reported and recorded
                Log.warn(f"  {label}: FAILED - {exc}")

        self.snapshot_path.write_text(json.dumps(snapshots, indent=2))
        Log.info(f"[PHASE 1] Snapshot saved to {self.snapshot_path}")
        return snapshots

    def _validate_snapshot(self, snapshots: dict[str, str]) -> None:
        problems: list[str] = []
        for cmd, label, expected_len in QUERIES:
            if expected_len is None:
                continue
            value = snapshots.get(label)
            if value is None:
                problems.append(f"missing '{label}' (0x{cmd:02X})")
            elif len(value) != expected_len * 2:
                problems.append(f"'{label}' is {len(value) // 2} bytes, expected {expected_len}")
        if problems:
            raise FlashError(
                "pre-flash settings backup incomplete: "
                + "; ".join(problems)
                + " — retry (BLE can be flaky) or pass --no-verify to flash without a backup"
            )

    def _report_backup_only(self, snapshots: dict[str, str]) -> None:
        """Print a human-readable summary for --backup-only (no firmware written)."""
        Log.info("\n[DONE] Backup-only mode: no firmware was written.")
        raw = snapshots.get("firmware_string")
        if raw:
            text = bytes.fromhex(raw)[:32].decode("ascii", errors="replace")
            Log.info(f"[DONE] Watch reports firmware version: {text}")
        Log.info(f"[DONE] Snapshot saved to {self.snapshot_path}")

    # -- phase 2: enter OTA mode -------------------------------------------
    async def enter_ota_mode(self) -> None:
        Log.info("\n[PHASE 2] Entering OTA mode...")
        self.phase = Phase.ENTERING
        await self.read_nus(0x21, timeout=5.0)
        Log.info("[PHASE 2] Watch should now be in OTA mode")
        await asyncio.sleep(2)

        if self.client and self.client.is_connected:
            self._expect_disconnect = True
            await self.client.disconnect()
            self._expect_disconnect = False
            Log.info("[OTA] Disconnected from watch, waiting for STM_OTA bootloader...")
        self.client = None
        await asyncio.sleep(3)

    # -- phase 3: upload ----------------------------------------------------

    async def upload_firmware(self) -> None:
        Log.info("\n[PHASE 3] Uploading firmware...")
        if self.dry_run:
            await self._dry_run_upload()
            return

        self.phase = Phase.BOOT
        if self.client is None or not self.client.is_connected:
            device = await self._find_stm_ota_device()
            Log.info(f"[OTA] Connecting to {device.address}...")
            self._disconnected = False
            self._uart_ready = False
            self.reassembler.reset()
            self.client = self._make_client(device.address)
            await self._with_timeout(
                self.client.connect(), self.connect_timeout, "Bootloader connect timed out"
            )
            Log.info(f"[OTA] Connected: {self.client.is_connected}")

        await self._load_services()
        control_char, raw_char, confirm_char = self._find_ota_characteristics()
        if control_char is None or raw_char is None:
            raise FlashError("OTA characteristics not found. Watch may not be in OTA mode.")
        if not _has_property(control_char, "write-without-response", "write"):
            raise FlashError("OTA control characteristic is not writable")
        if not _has_property(raw_char, "write-without-response", "write"):
            raise FlashError("OTA raw characteristic is not writable")
        if self.write_with_response and not _has_property(raw_char, "write"):
            raise FlashError("--write-with-response requested but raw characteristic cannot write with response")

        Log.info(f"[OTA] Control: {control_char.uuid}")
        Log.info(f"[OTA] Raw:     {raw_char.uuid}")
        Log.info(f"[OTA] Confirm: {confirm_char.uuid if confirm_char else 'not found'}")
        mtu = getattr(self.client, "mtu_size", None)
        if mtu:
            Log.info(f"[OTA] Negotiated MTU: {mtu}")
            if mtu - 3 < self.chunk_size:
                Log.warn(f"[OTA] MTU {mtu} is smaller than chunk size {self.chunk_size}")

        if confirm_char is not None:
            if not _has_property(confirm_char, "notify", "indicate"):
                raise FlashError("OTA confirm characteristic does not support notify/indicate")
            await self.client.start_notify(confirm_char, self._ota_confirm_callback)
            await asyncio.sleep(0.5)

        Log.info(f"[OTA] Begin signal: {BEGIN_CMD.hex()} (START user app)")
        await self.client.write_gatt_char(control_char, BEGIN_CMD, response=False)

        self.phase = Phase.UPLOADING
        await self._with_timeout(
            self._upload_chunks(raw_char), UPLOAD_TIMEOUT, "Firmware upload timed out"
        )

        self.phase = Phase.FINISHING
        await asyncio.sleep(1)
        Log.info(f"[OTA] Sending finish command: {FINISH_CMD.hex()} (upload finished)")
        await self.client.write_gatt_char(control_char, FINISH_CMD, response=False)
        Log.info(f"[OTA] Upload complete: {len(self.fw)} bytes")
        Log.info("[OTA] Waiting for bootloader to verify and commit...")

        if confirm_char is not None:
            try:
                await asyncio.wait_for(self._ota_completion_event.wait(), timeout=FINISH_TIMEOUT)
                self.phase = Phase.RESTARTING
                Log.info("[OTA] Bootloader confirmed — watch is rebooting...")
            except asyncio.TimeoutError:
                Log.warn("[OTA] No confirm received from bootloader.")
                if self.reboot_via_confirm and self.client and self.client.is_connected:
                    # NOTE: not present in the reference Swift; opt-in only.
                    await self.client.write_gatt_char(confirm_char, bytes([0x01]), response=False)
                    Log.info("[OTA] Reboot command sent via confirm characteristic.")
                    self.phase = Phase.RESTARTING

    async def _upload_chunks(self, raw_char) -> None:
        total = len(self.fw)
        offset = 0
        last_pct = -1
        use_response = self.write_with_response
        while offset < total:
            self._check_connected()
            chunk = self.fw[offset:offset + self.chunk_size]
            await self.client.write_gatt_char(raw_char, chunk, response=use_response)
            offset += len(chunk)
            if self.write_delay:
                await asyncio.sleep(self.write_delay)
            pct = offset * 100 // total
            if pct // 5 != last_pct // 5:
                last_pct = pct
                Log.info(f"  [UPLOAD] {pct}% ({offset}/{total} bytes)")

    async def _dry_run_upload(self) -> None:
        Log.info(f"[DRY-RUN] Would discover OTA service {OTA_SERVICE_BOOT}")
        Log.info(f"[DRY-RUN]   control: {OTA_CONTROL}")
        Log.info(f"[DRY-RUN]   raw:     {OTA_RAW}")
        Log.info(f"[DRY-RUN]   confirm: {OTA_CONFIRM}")
        Log.info(f"[OTA] Would write begin signal to control: {BEGIN_CMD.hex()} (START user app)")
        total = len(self.fw)
        offset = 0
        last_pct = -1
        while offset < total:
            offset = min(offset + self.chunk_size, total)
            pct = offset * 100 // total
            if pct // 5 != last_pct // 5:
                last_pct = pct
                Log.info(f"  [UPLOAD] {pct}% ({offset}/{total} bytes)")
        Log.info(f"[OTA] Would write finish signal to control: {FINISH_CMD.hex()}")
        Log.info(f"[OTA] Simulated upload complete: {total} bytes")
        Log.info("[DRY-RUN] Would wait for watch to process and restart...")

    def _find_ota_characteristics(self):
        control_char = raw_char = confirm_char = None
        for svc in self.client.services:
            if OTA_SERVICE_BOOT not in str(svc.uuid).lower():
                continue
            for char in svc.characteristics:
                uuid_lower = str(char.uuid).lower()
                if OTA_CONTROL in uuid_lower:
                    control_char = char
                elif OTA_RAW in uuid_lower:
                    raw_char = char
                elif OTA_CONFIRM in uuid_lower:
                    confirm_char = char
        return control_char, raw_char, confirm_char

    def _ota_confirm_callback(self, sender, data: bytearray) -> None:
        confirm_val = data[0] if data else 0
        Log.debug(f"[OTA_CONFIRM] {bytes(data).hex()} (first byte: {confirm_val})")
        if confirm_val == 1:
            self._ota_completion_seen = True
            self._ota_completion_event.set()

    # -- phase 4: verify ----------------------------------------------------

    async def _reconnect_for_verify(self, probe_timeout: float = 6.0) -> bool:
        """Reconnect after the OTA reboot and wait until the app answers NUS.

        A successful BLE connect does NOT mean the watch is ready: the link comes
        up well before the application has registered its NUS command handlers, so
        we probe with a cheap query until it actually replies. Each attempt uses a
        fresh connection (and thus a fresh subscription).
        """
        if self.client and self.client.is_connected:
            self._expect_disconnect = True
            await self.client.disconnect()
            self._expect_disconnect = False
        self.client = None

        attempts = 10
        last_error: Optional[Exception] = None
        for attempt in range(1, attempts + 1):
            delay = 2.0 if attempt == 1 else 5.0
            Log.info(
                f"[PHASE 4] Reconnect attempt {attempt}/{attempts} (waiting {delay:.0f}s)..."
            )
            await asyncio.sleep(delay)
            try:
                # connect() resets _uart_ready and the reassembly buffer. That is
                # essential here: the Phase 1 subscription left _uart_ready set, and
                # the bootloader client reused the object, so building the client
                # inline would make the _subscribe_uart() below a silent no-op and
                # we would never receive notifications on this new connection.
                await self.connect(self.mac)
                await self._load_services()
                await self._subscribe_uart()
                probe = await self.read_nus(0x2B, timeout=probe_timeout, retries=1)
                Log.info(f"[PHASE 4] Watch responded to NUS probe ({len(probe)} bytes)")
                return True
            except Exception as exc:  # noqa: BLE001 - retried below
                last_error = exc
                Log.warn(f"[PHASE 4] Attempt {attempt} not ready: {exc}")
                if self.client and self.client.is_connected:
                    self._expect_disconnect = True
                    try:
                        await self.client.disconnect()
                    except Exception:  # noqa: BLE001
                        pass
                    self._expect_disconnect = False
                self.client = None
        Log.warn(f"[PHASE 4] Watch never became responsive: {last_error}")
        return False

    async def verify_settings(self, original: dict[str, str]) -> None:
        Log.info("\n[PHASE 4] Verifying settings after flash...")
        self.phase = Phase.VERIFY

        if self.dry_run:
            await self.connect()
            for cmd, label, _ in QUERIES:
                data = await self.read_nus(cmd)
                match = data.hex() == original.get(label, "")
                Log.info(f"  {label}: {'OK' if match else 'CHANGED!'}")
            Log.info("[DRY-RUN] Settings would match backup — flash verified!")
            return

        if not await self._reconnect_for_verify():
            raise FlashError("watch did not become responsive after the OTA reboot")

        mismatches: list[str] = []
        errors: list[str] = []
        for cmd, label, _expected_len in QUERIES:
            if cmd in SNAPSHOT_COMPARE_EXCLUDE:
                Log.info(f"  {label}: skipped (version text)")
                continue
            try:
                data = await self.read_nus(cmd)
            except Exception as exc:  # noqa: BLE001
                Log.warn(f"  {label}: ERROR - {exc}")
                errors.append(label)
                continue
            match = data.hex() == original.get(label, "")
            Log.info(f"  {label}: {'OK' if match else 'CHANGED!'}")
            if not match:
                mismatches.append(label)

        if not mismatches and not errors:
            Log.info("[PHASE 4] All settings verified — flash successful!")
            return
        raise FlashError(
            "post-flash verification incomplete: "
            + ", ".join(errors + mismatches)
            + f" (backup in {self.snapshot_path}; re-check with --backup-only)"
        )

    # -- top-level orchestration -------------------------------------------

    async def run(self) -> None:
        try:
            if self.dry_run:
                await self._run_dry()
                return

            self.phase = Phase.IDLE
            Log.info("\n[CHECK] Looking for watch in normal mode...")
            normal = await self._find_device(self.mac, timeout=5.0)

            snapshots: dict[str, str] = {}
            if normal is not None:
                Log.info(f"[CHECK] Found watch at {self.mac} ({normal.name})")
                await self.connect()
                await self.discover_services(UART_SERVICE)
                snapshots = await self.backup_settings()
                if self.verify:
                    self._validate_snapshot(snapshots)
                if self.backup_only:
                    self._report_backup_only(snapshots)
                    return
                await self.enter_ota_mode()
            else:
                if self.backup_only:
                    raise FlashError(
                        f"Watch not found at {self.mac}; cannot back up settings"
                    )
                Log.info(f"[CHECK] Watch not found at {self.mac}; looking for STM_OTA...")
                device = await self._find_stm_ota_device()
                Log.info(f"[CHECK] Found STM_OTA at {device.address} ({device.name})")
                Log.info("[CHECK] Skipping backup (watch already in bootloader mode)")

            await self.upload_firmware()

            if self.dry_run:
                Log.info("\n[DONE] Dry run complete. No changes were made.")
                return

            if self._ota_completion_seen:
                Log.info(
                    f"\n[WAIT] Completion acknowledged; waiting {self.reboot_wait:.0f}s "
                    f"for the watch to boot..."
                )
                # The bootloader reboots the watch (and drops the link) on purpose.
                self._expect_disconnect = True
                await asyncio.sleep(self.reboot_wait)
                self._expect_disconnect = False
            else:
                Log.info("\n[WAIT] No completion notification received (confirm char may not notify).")
                await asyncio.sleep(5)

            if snapshots and self.verify:
                try:
                    await self.verify_settings(snapshots)
                except Exception as exc:  # noqa: BLE001
                    if self._ota_completion_seen:
                        # The bootloader committed the image, so the flash itself
                        # succeeded; verification failing here is a readiness or
                        # BLE issue, not a failed write.
                        Log.warn(
                            f"\n[WARN] Post-flash verification failed ({exc}).\n"
                            f"[WARN] The bootloader committed the upload, so the firmware IS "
                            f"flashed. Re-check settings with `--backup-only` once the watch "
                            f"has fully booted."
                        )
                    else:
                        raise
            else:
                Log.info("\n[SKIP] Skipping verification")

            self.phase = Phase.DONE
            if self.client and self.client.is_connected:
                self._expect_disconnect = True
                Log.info("\n[OTA] Cancelling connection — bootloader should reboot...")
                await self.client.disconnect()
                self._expect_disconnect = False
            Log.info("[OTA] Waiting for watch to reboot with new firmware...")
            await asyncio.sleep(15)
            Log.info("\n[DONE] Flash complete. Watch should be running new firmware.")
            Log.info("[DONE] If the watch doesn't respond after 60s, hard reset by removing/reinserting batteries.")
        except Exception as exc:  # noqa: BLE001
            Log.error(f"\n[FAILED] phase={self.phase.value}: {exc}")
            raise
        finally:
            if self.client and self.client.is_connected:
                self._expect_disconnect = True
                await self.client.disconnect()
                self._expect_disconnect = False
                Log.info("[BLE] Disconnected")

    async def _run_dry(self) -> None:
        self.phase = Phase.IDLE
        Log.info("[DRY-RUN] Would scan for the watch...")
        await self.connect()
        await self.discover_services(UART_SERVICE)
        snapshots = await self.backup_settings()
        self._validate_snapshot(snapshots)
        await self.enter_ota_mode()
        await self.upload_firmware()
        Log.info("\n[DRY-RUN] Would wait for OTA completion notification...")
        await self.verify_settings(snapshots)
        Log.info("\n[DONE] Dry run complete. No changes were made.")
        Log.info("[DRY-RUN] To flash for real, remove --dry-run and provide the watch MAC.")


# ── CLI ─────────────────────────────────────────────────────────────────────

def _int_auto(value: str) -> int:
    return int(value, 0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Flash custom firmware to Ollee Watch over BLE",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--mac", help="Bluetooth address of the watch (e.g., AA:BB:CC:DD:EE:FF)")
    parser.add_argument(
        "--bootloader",
        help="Explicit STM_OTA bootloader address (default: watch address + 1)",
    )
    parser.add_argument(
        "--firmware", default=DEFAULT_FIRMWARE,
        help="Firmware binary path",
    )
    parser.add_argument(
        "--expected-sha256",
        help="Optional: require this exact image SHA-256 (otherwise only structural checks)",
    )
    parser.add_argument(
        "--expected-sp", type=_int_auto, default=EXPECTED_SP,
        help="Expected initial stack pointer",
    )
    parser.add_argument("--chunk-size", type=_int_auto, default=CHUNK_SIZE,
                        help="Raw upload chunk size in bytes")
    parser.add_argument("--write-delay", type=float, default=0.02,
                        help="Delay (s) between raw chunks; 0 disables extra pacing")
    parser.add_argument("--write-with-response", action="store_true",
                        help="Use acknowledged writes for raw chunks (slower, safer)")
    parser.add_argument("--scan-timeout", type=float, default=10.0,
                        help="BLE scan timeout (s)")
    parser.add_argument("--connect-timeout", type=float, default=CONNECT_TIMEOUT,
                        help="BLE connect timeout (s)")
    parser.add_argument("--nus-retries", type=int, default=3,
                        help="Retries per NUS query on timeout/no reply")
    parser.add_argument("--no-verify", action="store_true",
                        help="Skip the pre-flash settings backup/validation and "
                             "post-flash verification (flash proceeds regardless)")
    parser.add_argument("--backup-only", action="store_true",
                        help="Connect, back up settings and print the watch's firmware "
                             "version string, then exit without writing firmware")
    parser.add_argument("--reboot-wait", type=float, default=12.0,
                        help="Seconds to wait after the OTA reboot before reconnecting")
    parser.add_argument("--reboot-via-confirm", action="store_true",
                        help="If no completion notification arrives, write [0x01] to the "
                             "confirm characteristic (not part of the reference protocol)")
    parser.add_argument("--snapshot", type=Path, default=None,
                        help="Where to write the pre-flash settings snapshot")
    parser.add_argument("--dry-run", "-n", action="store_true",
                        help="Simulate the entire flash process without connecting to BLE")
    parser.add_argument("--timestamps", action="store_true",
                        help="Prefix log lines with an ISO-8601 timestamp")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Show NUS frames and other debug detail")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    # Resolve snapshot default relative to this script (parent = analysis repo).
    if args.snapshot is None:
        args.snapshot = Path(__file__).resolve().parent.parent / "pre-flash-snapshot.json"

    Log.timestamps = args.timestamps
    Log.verbose = args.verbose

    if not args.dry_run and not args.mac:
        parser.error("--mac is required (unless using --dry-run)")
    if not args.dry_run and BleakClient is None:
        Log.error(f"bleak is required for real flashing: {_BLEAK_IMPORT_ERROR}")
        return 1

    Log.info("=" * 60)
    Log.info("Ollee Watch OTA Flasher" + (" — DRY RUN" if args.dry_run else ""))
    Log.info("=" * 60)

    try:
        firmware = validate_firmware(
            Path(args.firmware),
            expected_sp=args.expected_sp,
            expected_sha256=args.expected_sha256,
        )
    except FirmwareError as exc:
        Log.error(f"[ERROR] Firmware validation failed: {exc}")
        return 1

    flasher = OlleeFlasher(
        args.mac or "00:00:00:00:00:00",
        firmware,
        bootloader=args.bootloader,
        dry_run=args.dry_run,
        chunk_size=args.chunk_size,
        write_delay=args.write_delay,
        write_with_response=args.write_with_response,
        scan_timeout=args.scan_timeout,
        connect_timeout=args.connect_timeout,
        verify=not args.no_verify,
        backup_only=args.backup_only,
        reboot_wait=args.reboot_wait,
        reboot_via_confirm=args.reboot_via_confirm,
        nus_retries=args.nus_retries,
        snapshot_path=args.snapshot,
    )
    try:
        asyncio.run(flasher.run())
    except KeyboardInterrupt:
        Log.warn("\n[ABORTED] Interrupted by user")
        return 130
    except Exception:  # already logged in run()
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
