#!/usr/bin/env python3
"""Unit tests for the protocol core of flash_ollee.py.

These tests do not require bleak or BLE hardware; they exercise CRC, framing,
reassembly and image validation only.

Run with:  python3 -m unittest -v test_flash_ollee
"""
from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import flash_ollee as fw


def make_image(size: int = 0x2000, *, sp: int = fw.EXPECTED_SP, marker_off: int = 0x1800,
               magic: int = fw.EXPECTED_MAGIC, reset: int | None = None) -> bytes:
    """Build a minimal structurally-valid image for validator tests."""
    assert size % 8 == 0 and marker_off + 4 <= size
    data = bytearray(size)
    struct.pack_into("<I", data, 0, sp)
    struct.pack_into("<I", data, 4, (fw.LOAD_BASE + 0x101) if reset is None else reset)
    struct.pack_into("<I", data, 0x140, fw.LOAD_BASE + marker_off)
    struct.pack_into("<I", data, marker_off, magic)
    return bytes(data)


def write_tmp(data: bytes) -> Path:
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".bin")
    tmp.write(data)
    tmp.close()
    return Path(tmp.name)


class CrcTests(unittest.TestCase):
    def test_ccitt_false_check_value(self):
        # CRC-16/CCITT-FALSE("123456789") == 0x29B1
        self.assertEqual(fw.crc16_ccitt(b"123456789"), 0x29B1)

    def test_empty(self):
        self.assertEqual(fw.crc16_ccitt(b""), fw.CRC_INIT)


class FrameTests(unittest.TestCase):
    def test_frame_layout_no_payload(self):
        frame = fw.build_nus_frame(0x2A)
        inner = bytes([0x02, 0x2A])
        crc = fw.crc16_ccitt(inner)
        self.assertEqual(frame, bytes([0x00, 0x06, 0xAA, 0x55, crc >> 8, crc & 0xFF]) + inner)

    def test_frame_length_includes_payload(self):
        frame = fw.build_nus_frame(0x32, b"\x01\x02\x03")
        self.assertEqual(frame[0:2], b"\x00\x09")  # 6 + 3

    def test_frame_round_trips_through_reassembler(self):
        payload = b"hello"
        frame = fw.build_nus_frame(0x2E, payload)
        parsed = fw.FrameReassembler().feed(frame)
        self.assertEqual(parsed, [fw.Frame(0x2E, payload)])


class ReassemblerTests(unittest.TestCase):
    def test_fragmented_frame(self):
        reassembler = fw.FrameReassembler()
        frame = fw.build_nus_frame(0x2B, bytes(range(32)))
        self.assertEqual(reassembler.feed(frame[:10]), [])
        parsed = reassembler.feed(frame[10:])
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0].cmd, 0x2B)
        self.assertEqual(parsed[0].payload, bytes(range(32)))

    def test_resync_skips_garbage_prefix(self):
        reassembler = fw.FrameReassembler()
        frame = fw.build_nus_frame(0x2A, b"XY")
        parsed = reassembler.feed(b"\xff\x00\x99" + frame)
        self.assertEqual([(f.cmd, f.payload) for f in parsed], [(0x2A, b"XY")])

    def test_bad_crc_is_recorded_and_skipped(self):
        reassembler = fw.FrameReassembler()
        frame = bytearray(fw.build_nus_frame(0x2A, b"Z"))
        frame[4] ^= 0xFF  # corrupt CRC
        self.assertEqual(reassembler.feed(bytes(frame)), [])
        self.assertTrue(reassembler.errors)

    def test_rejection_frame_is_surfaced(self):
        reassembler = fw.FrameReassembler()
        parsed = reassembler.feed(fw.build_nus_frame(fw.REJECT_CMD))
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0].cmd, fw.REJECT_CMD)

    def test_multiple_frames_in_one_feed(self):
        reassembler = fw.FrameReassembler()
        data = fw.build_nus_frame(0x2A, b"a") + fw.build_nus_frame(0x32, b"b")
        parsed = reassembler.feed(data)
        self.assertEqual([(f.cmd, f.payload) for f in parsed], [(0x2A, b"a"), (0x32, b"b")])

    def test_short_length_prefix_resyncs(self):
        reassembler = fw.FrameReassembler()
        parsed = reassembler.feed(b"\x00\x03\xaa\x55" + fw.build_nus_frame(0x2A))
        self.assertEqual([f.cmd for f in parsed], [0x2A])


class ValidationTests(unittest.TestCase):
    def test_valid_image(self):
        firmware = fw.validate_firmware(write_tmp(make_image()))
        self.assertEqual(firmware.size, 0x2000)
        self.assertEqual(firmware.sp, fw.EXPECTED_SP)
        self.assertEqual(len(firmware.sha256), 64)

    def test_wrong_stack_pointer(self):
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(make_image(sp=0x20030008)))

    def test_unaligned_size(self):
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(make_image()[:-1]))

    def test_bad_magic(self):
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(make_image(magic=0xDEADBEEF)))

    def test_descriptor_pointer_outside_image(self):
        data = bytearray(make_image())
        struct.pack_into("<I", data, 0x140, fw.LOAD_BASE + len(data))  # one past the end
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(bytes(data)))

    def test_reset_vector_not_thumb(self):
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(make_image(reset=fw.LOAD_BASE)))

    def test_expected_sha256_opt_in(self):
        import hashlib
        data = make_image()
        digest = hashlib.sha256(data).hexdigest()
        fw.validate_firmware(write_tmp(data), expected_sha256=digest)  # passes
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(write_tmp(data), expected_sha256="0" * 64)

    def test_missing_file(self):
        with self.assertRaises(fw.FirmwareError):
            fw.validate_firmware(Path("/nonexistent/firmware.bin"))


class _FakeChar:
    def __init__(self, uuid, properties):
        self.uuid = uuid
        self.properties = properties


class _FakeServices:
    def __init__(self, chars):
        self._chars = {c.uuid: c for c in chars}

    def get_characteristic(self, uuid):
        return self._chars.get(uuid)


class _FakeClient:
    """Minimal BleakClient stand-in that answers NUS queries immediately."""

    def __init__(self):
        self.is_connected = False
        self.notify_calls = []
        self.services = _FakeServices([
            _FakeChar(fw.UART_TX, ["notify"]),
            _FakeChar(fw.UART_RX, ["write"]),
        ])
        self._callback = None

    async def connect(self):
        self.is_connected = True

    async def start_notify(self, char, callback):
        self.notify_calls.append(char.uuid)
        self._callback = callback

    async def write_gatt_char(self, char, data, response=False):
        if self._callback is None:
            return
        # Mirror the watch: reply to command X with command X + 0x20.
        # Frame layout: [len_hi len_lo AA 55 crc_hi crc_lo type(=2) cmd ...]
        reply = data[7] + fw.REPLY_OFFSET
        payload = bytes(32) if reply == 0x4B else b""
        self._callback(char, bytearray(fw.build_nus_frame(reply, payload)))

    async def disconnect(self):
        self.is_connected = False


async def _no_sleep(*_args, **_kwargs):
    return None


class ReconnectTests(unittest.IsolatedAsyncioTestCase):
    """Regression: the post-flash reconnect must re-subscribe on its new link."""

    def _flasher(self, client: _FakeClient) -> fw.OlleeFlasher:
        firmware = fw.Firmware(
            path=Path("x"), data=b"\x00" * 16, sha256="0" * 64,
            sp=fw.EXPECTED_SP, reset=fw.LOAD_BASE + 0x101, magic_offset=0x1800,
        )
        flasher = fw.OlleeFlasher("00:00:00:00:00:00", firmware, nus_retries=1)
        flasher._make_client = lambda address: client  # type: ignore[method-assign]
        return flasher

    async def test_reconnect_resubscribes_after_bootloader_phase(self):
        client = _FakeClient()
        flasher = self._flasher(client)
        # Leftover state from Phase 1: _uart_ready stays set across the bootloader
        # connection. If the reconnect skips connect(), _subscribe_uart() becomes a
        # no-op and the fresh link silently receives nothing.
        flasher._uart_ready = True
        with mock.patch.object(fw.asyncio, "sleep", _no_sleep):
            ready = await flasher._reconnect_for_verify(probe_timeout=0.5)
        self.assertTrue(ready)
        self.assertEqual(client.notify_calls, [fw.UART_TX])
        self.assertTrue(flasher._uart_ready)

    async def test_reconnect_fails_when_device_never_answers(self):
        class _SilentClient(_FakeClient):
            async def write_gatt_char(self, char, data, response=False):
                return None  # never notifies

        client = _SilentClient()
        flasher = self._flasher(client)
        with mock.patch.object(fw.asyncio, "sleep", _no_sleep):
            ready = await flasher._reconnect_for_verify(probe_timeout=0.05)
        self.assertFalse(ready)


if __name__ == "__main__":
    unittest.main()
