#!/usr/bin/env python3
"""Offline tests for the Ollee Watch configuration tooling.

No BLE hardware is needed: a ReplayTransport answers canned replies so the
framing, codecs and YAML round-trip can all be exercised.

Run with:  python3 -m unittest -v test_watchconfig
"""
from __future__ import annotations

import asyncio
import os
import struct
import unittest
from unittest import mock

from watchconfig import codecs, framing, protocol, sections, yamlio
from watchconfig.protocol import OapClient, OapError
from watchconfig.transport import ReplayTransport


def faces_wire() -> bytes:
    """A valid 108-byte face payload: Clock (id 4) first, then ids 5..21."""
    out = bytearray()
    for i, fid in enumerate(range(4, 22)):
        clock = fid == 4
        out += bytes([fid, 0 if clock else 1, 0, 0 if clock else 1, 0, i])
    return bytes(out)


def _client(replies: dict[int, bytes]) -> tuple[OapClient, ReplayTransport]:
    transport = ReplayTransport(replies)
    client = OapClient(transport)
    transport.set_notification_handler(client.feed)
    return client, transport


class ProtocolTests(unittest.TestCase):
    def test_command_table_matches_dispatcher(self):
        # every getter has a same-section setter where one exists, and replies
        # are cmd + 0x20
        self.assertEqual(protocol.BY_CMD[0x32].name, "oap_get_config_register_req")
        self.assertEqual(protocol.BY_CMD[0x2B].name, "oap_get_alarm_req")
        self.assertEqual(protocol.BY_CMD[0x3C].name, "oap_get_databank_req")
        self.assertEqual(protocol.BY_CMD[0x32].reply, 0x52)

    def test_request_round_trip(self):
        payload = bytes.fromhex("001ed62d0000001eff00ff00030001")
        client, transport = _client({0x32: payload})

        async def go():
            return await client.request(0x32)

        self.assertEqual(asyncio.run(go()), payload)
        self.assertEqual(transport.sent, [0x32])

    def test_timeout_raises(self):
        client, _ = _client({})

        async def go():
            return await client.request(0x32, timeout=0.05, retries=1)

        with self.assertRaises(OapError):
            asyncio.run(go())


class YamlTests(unittest.TestCase):
    def test_round_trip(self):
        doc = yamlio.new_document("DEADBEEF01.05.0000.00.02FREELLEE")
        doc["config"] = {"feature_mask": 0x001ED62D}
        doc["nametag"] = "Hector"
        text = yamlio.dump(doc)
        self.assertIn("nametag: Hector", text)
        back = yamlio.load(text)
        self.assertEqual(back["config"]["feature_mask"], 0x001ED62D)
        self.assertEqual(back["nametag"], "Hector")

    def test_reject_non_mapping(self):
        with self.assertRaises(yamlio.YamlError):
            yamlio.load("- 1\n- 2\n")

    def test_reject_future_version(self):
        with self.assertRaises(yamlio.YamlError):
            yamlio.load("meta:\n  doc_version: 99\n")

    def test_hex_helpers(self):
        self.assertEqual(yamlio.from_hex("00 ff"), b"\x00\xff")
        with self.assertRaises(yamlio.YamlError):
            yamlio.from_hex("zz")


class SectionTests(unittest.TestCase):
    def test_read_only_section_cannot_encode(self):
        version = sections.BY_KEY["version"]
        with self.assertRaises(ValueError):
            sections.encode_section(version, "whatever")

    def test_undecoded_payload_falls_back_to_hex(self):
        # any section without a codec must still round-trip, as hex
        section = sections.Section("synthetic", get_cmd=0x39, set_cmd=0x38)
        payload = bytes.fromhex("001ed62d0000001eff00ff00030001")
        self.assertEqual(sections.decode_section(section, payload), payload.hex())
        self.assertEqual(sections.encode_section(section, payload.hex()), payload)

    def test_section_keys_unique(self):
        keys = [s.key for s in sections.SECTIONS]
        self.assertEqual(len(keys), len(set(keys)))


class CodecTests(unittest.TestCase):
    """Codecs are validated against the firmware's own default blob."""

    BIN = "OW-FW-APP_HW_01.05.00_FW_00.01.10.bin"
    BASE = 0x08007000

    def _require_firmware(self):
        """Skip this test if the firmware binary is not present."""
        if not os.path.isfile(self.BIN):
            self.skipTest(f"firmware binary not found: {self.BIN}")

    def _defaults(self) -> bytes:
        self._require_firmware()
        with open(self.BIN, "rb") as fh:
            data = fh.read()
        start = 0x080395B0 - self.BASE          # settings defaults table
        return data[start:start + 0x1F8]

    def test_faces_round_trip_against_defaults(self):

        defaults = self._defaults()

        blob = defaults[0xFC:0xFC + 132]        # 22 slots x 6

        wire = b"".join(blob[6 * i:6 * i + 6] for i in range(4, 22))

        self.assertEqual(len(wire), 108)

        faces = codecs.decode_faces(wire)

        self.assertEqual(len(faces), 17)                 # Clock is implied, not listed

        names = [f["name"] for f in faces]

        self.assertNotIn("Clock", names)

        self.assertEqual(faces[0]["name"], "Alarm")      # position 0 (Clock holds 0)

        self.assertEqual(faces[0]["_comment"], "id 5")   # id shown as a comment

        self.assertNotIn("id", faces[0])

        step = next(f for f in faces if f["name"] == "Step Counter")

        self.assertTrue(step["favorite"])

        self.assertEqual(sorted(names), sorted(codecs.FACE_NAMES_BY_ORDER))

        # the setter is keyed by face id, so the byte order need not match the
        # getter's; the round-trip that matters is decode(encode(x)) == x
        self.assertEqual(codecs.decode_faces(codecs.encode_faces(faces)), faces)


    def test_face_names_cannot_change(self):

        faces = codecs.decode_faces(bytes(range(4, 22)) + bytes(90))

        self.assertEqual(len(faces), 17)

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_faces(faces[:16])                      # wrong count

        renamed = [dict(f) for f in faces]

        renamed[0]["name"] = "Nope"

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_faces(renamed)


    def test_faces_bad_length(self):
        with self.assertRaises(ValueError):
            codecs.decode_faces(b"\x00" * 100)

    def test_worldtime_defaults(self):
        defaults = self._defaults()
        # struct stores the offset little-endian; the wire form is byte-reversed (BE)
        payload = defaults[0x40:0x44][::-1] + defaults[0x84:0x84 + 14]
        self.assertEqual(payload[0:4], bytes.fromhex("00005460"))  # 21600 s
        self.assertEqual(payload[4:18], b"MOTUWETHFRSASU")
        value = codecs.decode_worldtime(payload)
        self.assertEqual(value["offset_seconds"], 21600)
        self.assertEqual(value["weekdays"], {
            "monday": "MO", "tuesday": "TU", "wednesday": "WE", "thursday": "TH",
            "friday": "FR", "saturday": "SA", "sunday": "SU"})
        self.assertNotIn("offset_hours", value)
        self.assertEqual(codecs.encode_worldtime(value), payload)

    def test_worldtime_accepts_hours(self):
        payload = codecs.encode_worldtime({"offset_hours": -3.5,
                                           "weekday_names": "MOTUWETHFRSASU"})
        self.assertEqual(payload[0:4], bytes.fromhex("ffffcec8"))  # -12600 s

    def test_worldtime_rejects_long_names(self):
        with self.assertRaises(ValueError):
            codecs.encode_worldtime({"offset_seconds": 0, "weekday_names": "X" * 15})


    def test_alarm_round_trip_against_defaults(self):
        defs = self._defaults()
        # 12-byte alarm object at +0x5C (payload byte 0 is live), 5 slots at +0xAC
        wire = b"\x00" + defs[0x5C + 1:0x5C + 12] + defs[0xAC:0xAC + 20]
        alarm = codecs.decode_alarm(wire)
        self.assertTrue(alarm["snooze_on"])
        self.assertEqual(alarm["hours"], 12)
        self.assertEqual(alarm["chime"], "Classic")
        self.assertEqual(alarm["hourly_chime_hours"], list(range(24)))  # every hour
        self.assertEqual(alarm["days"], [])
        self.assertEqual(len(alarm["slots"]), 5)
        self.assertEqual(alarm["slots"][0]["mode"], "off")
        # the setter form is the same 32 bytes with a write-mode byte inserted at 8
        encoded = codecs.encode_alarm(alarm)
        self.assertEqual(len(encoded), 33)
        self.assertEqual(encoded[8], 0)          # full write
        self.assertEqual(encoded[:8], wire[:8])
        # the chime mask is normalised to its 24 meaningful hours (bits 24..31 of
        # the factory default are outside the field's range)
        self.assertEqual(encoded[9:13], bytes.fromhex("ffffff00"))
        self.assertEqual(encoded[13:], wire[12:])

    def test_timer_presets_against_defaults(self):
        defs = self._defaults()
        presets = list(struct.unpack("<10I", defs[0x2C:0x2C + 20] + defs[0x94:0x94 + 20]))
        self.assertEqual(presets, [180, 30, 180, 30, 0, 60, 120, 600, 900, 1800])
        value = {"hours": 0, "minutes": 0, "seconds": 0,
                 "presets_seconds": presets}
        encoded = codecs.encode_timer(value)
        self.assertEqual(len(encoded), 44)
        self.assertEqual(encoded[4:], defs[0x2C:0x2C + 20] + defs[0x94:0x94 + 20])

    def test_version_strings(self):
        self._require_firmware()
        with open(self.BIN, "rb") as fh:
            data = fh.read()
        start = 0x080397A8 - self.BASE
        payload = data[start:start + 32] + bytes(4)
        version = codecs.decode_version(payload)
        self.assertEqual(version["hash_id"], "DEADBEEF")
        self.assertEqual(version["hw_id"], "01.05.00")
        self.assertEqual(version["fw_id"], "00.01.10")
    def test_databank_round_trip(self):
        entries = [{"tag": "none", "text": ""} for _ in range(10)]
        entries[0] = {"tag": "note", "text": "hello"}
        entries[1] = {"tag": "phone", "text": "0123"}
        entries[9] = {"tag": "password", "text": "hunter2"}
        wire = codecs.encode_databank(entries)
        self.assertEqual(len(wire), 220)
        # tag 1 really is "phone" (the app's DatabankTag enum), not an ASCII letter
        self.assertEqual(wire[0], 3)          # note
        self.assertEqual(wire[22], 1)         # phone
        self.assertEqual(wire[9 * 22], 5)     # password
        back = codecs.decode_databank(wire)
        self.assertEqual(back[0], {"tag": "note", "text": "hello"})
        self.assertEqual(back[1]["tag"], "phone")
        self.assertEqual(back[9]["tag"], "password")
        self.assertEqual(back[9]["text"], "hunter2")



    def test_weather_round_trip(self):


        days = [{"month": 10, "high": 21, "low": 12, "uv_index": 5,


                 "condition": "Cloudy", "precipitation_pct": 10, "humidity_pct": 60,


                 "feels_like": 20} for _ in range(5)]


        wire = codecs.encode_weather({"days": days, "unit": "fahrenheit"})


        self.assertEqual(len(wire), 41)


        self.assertEqual(wire[40], 1)


        self.assertEqual(wire[0], 0)                     # day byte is implied by position


        back = codecs.decode_weather(wire)


        self.assertEqual(back["unit"], "fahrenheit")


        self.assertEqual(back["days"][0]["condition"], "Cloudy")


        self.assertNotIn("day", back["days"][0])


        self.assertEqual(codecs.encode_weather(back), wire)


        with self.assertRaises(codecs.InvalidValue):


            codecs.encode_weather({"days": [dict(days[0], condition="Nope")] + days[1:],


                                   "unit": "celsius"})


    def test_alarm_slot_modes(self):
        wire = bytearray(32)
        wire[12:16] = bytes([7, 30, 0, 0])     # off
        wire[16:20] = bytes([8, 0, 1, 5])      # daily
        wire[20:24] = bytes([9, 15, 2, 14])    # one shot
        alarm = codecs.decode_alarm(bytes(wire))
        self.assertEqual(alarm["slots"][0]["mode"], "off")
        self.assertEqual(alarm["slots"][1]["mode"], "daily")
        self.assertEqual(alarm["slots"][2]["mode"], "one_shot")
        self.assertEqual(alarm["slots"][2]["chime"], "Sand")
        self.assertEqual(codecs.encode_alarm(alarm)[13:], bytes(wire[12:]))

    def test_pair_code_encoding(self):
        # app maps its modes: 1 -> 2, 2 -> 4
        self.assertEqual(codecs.encode_pair_code({"code": 42, "mode": 1}),
                         bytes([0x00, 42, 2]))
        self.assertEqual(codecs.encode_pair_code({"code": 7, "mode": 0}),
                         bytes([0x00, 7, 0]))


    def test_config_register_against_defaults(self):


        defs = self._defaults()


        wire = (defs[0x44:0x48][::-1] + defs[0x50:0x54][::-1] + defs[0x4C:0x50][::-1]


                + bytes([defs[0x26], defs[0x7C], defs[0xC0]]))


        self.assertEqual(wire.hex(), "0006542d000000050000ff00030002")


        config = codecs.decode_config(wire)


        on = [k for k, v in config["features"].items() if v]


        self.assertEqual(on, ["motion", "temperature", "gestures", "step_chime",


                              "heart_rate_tilt", "hold_for_home", "event_counter",


                              "bluetooth_chime", "backlight_colour_correction"])


        self.assertEqual(config["autosleep_period"], 5)


        self.assertEqual(config["led_rgb"], {"red": 0, "green": 255, "blue": 0})


        # 0 = brightest, 8 = off; the factory default 3 is "the 4th step down"


        self.assertEqual(config["led_brightness"], 5)   # wire default 3


        self.assertEqual(config["light_hold"], "hold")


        self.assertEqual(config["hour_format"], "Toggle")


        self.assertEqual(config["night_sleep"], "01:00")


        self.assertNotIn("unknown_bits", config)          # hidden while zero


        self.assertEqual(codecs.encode_config(config), wire)



    def test_brightness_is_an_integer_with_a_bounded_range(self):


        def bright(n):


            return codecs.encode_config({"features": {}, "light_hold": "hold",


                                         "autosleep_period": 5,


                                         "led_rgb": {"red": 0, "green": 0, "blue": 0},


                                         "led_brightness": n, "hour_format": "toggle",


                                         "night_sleep": "off"})[12]


        self.assertEqual(bright(0), 8)      # 0 = off


        self.assertEqual(bright(8), 0)      # 8 = brightest


        with self.assertRaises(codecs.InvalidValue):


            bright(9)



    def test_light_hold_accepts_keywords_and_seconds(self):


        def hold(v):


            return codecs.encode_config({"features": {}, "light_hold": v,


                                         "autosleep_period": 5,


                                         "led_rgb": {"red": 0, "green": 0, "blue": 0},


                                         "led_brightness": 0, "hour_format": "toggle",


                                         "night_sleep": "off"})[0:4]


        self.assertEqual(hold("hold"), hold(0) if False else hold("hold"))


        self.assertEqual(int.from_bytes(hold("hold"), "big") >> 7 & 7, 0)


        self.assertEqual(int.from_bytes(hold(5), "big") >> 7 & 7, 4)


        self.assertEqual(int.from_bytes(hold("toggle"), "big") >> 7 & 7, 6)


        for bad in ("hold only", 4, 7, "2 seconds"):


            with self.assertRaises(codecs.InvalidValue):


                hold(bad)



    def test_hour_format_is_case_insensitive(self):


        def fmt(v):


            return codecs.encode_config({"features": {}, "light_hold": "hold",


                                         "autosleep_period": 5,


                                         "led_rgb": {"red": 0, "green": 0, "blue": 0},


                                         "led_brightness": 0, "hour_format": v,


                                         "night_sleep": "off"})[13]


        self.assertEqual(fmt("toggle"), 0)


        self.assertEqual(fmt("am/pm"), 1)


        self.assertEqual(fmt("24h"), 2)


        self.assertEqual(fmt("AM/PM"), 1)


        with self.assertRaises(codecs.InvalidValue):


            fmt("12h")


    def test_rgb_and_night_sleep_helpers(self):
        # red/green/blue are 0..255; alpha is opaque
        packed = codecs._pack_rgb({"red": 0x12, "green": 0x34, "blue": 0x56})
        self.assertEqual(packed, 0x00123456)   # alpha defaults to 0x00
        # night_sleep: wire = hour + 1
        self.assertEqual(codecs._encode_night_sleep("off"), 0)
        self.assertEqual(codecs._encode_night_sleep("00:00"), 1)
        self.assertEqual(codecs._encode_night_sleep("03:00"), 4)
        self.assertEqual(codecs._encode_night_sleep("23:00"), 24)
        self.assertEqual(codecs._decode_night_sleep(4), "03:00")
        self.assertEqual(codecs._decode_night_sleep(24), "23:00")
        self.assertEqual(codecs._decode_night_sleep(0), "off")

    def test_night_sleep_survives_yaml(self):
        # 'HH:00' for hours >= 10 is a YAML sexagesimal integer if left bare
        # ('13:00' parses as 780), so the dumper must quote it.
        for value in ("off", "01:00", "09:00", "13:00", "23:00"):
            document = {"config": {"night_sleep": value}}
            back = yamlio.load(yamlio.dump(document))
            self.assertEqual(back["config"]["night_sleep"], value)

    def test_health_against_defaults(self):
        defs = self._defaults()
        wire = defs[0x48:0x4C][::-1] + bytes([defs[0x27]])
        # the 5th byte is the pulsometer target beats, not a sensitivity figure
        self.assertEqual(codecs.decode_health(wire),
                         {"step_goal": 5000, "pulsometer_target_beats": 20})
        self.assertEqual(codecs.encode_health(codecs.decode_health(wire)), wire)
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_health({"step_goal": 5000, "pulsometer_target_beats": 7})
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_health({"step_goal": 50, "pulsometer_target_beats": 20})

    def test_nametag_round_trip(self):
        self.assertEqual(codecs.decode_nametag(b"Ollee\x00"), "Ollee")
        self.assertEqual(codecs.encode_nametag("Ollee"), b"Ollee\x00")
        with self.assertRaises(ValueError):
            codecs.encode_nametag("too long")

    def test_timeref_round_trip(self):
        value = {"utc_seconds": 1759276800, "timezone_offset_seconds": 7200,
                 "latitude": 41.39, "longitude": 2.16, "milliseconds": 250,
                 "lunitidal_interval_minutes": 330}
        wire = codecs.encode_timeref(value)
        self.assertEqual(len(wire), 20)
        back = codecs.decode_timeref(wire)
        self.assertEqual(back["utc_seconds"], 1759276800)
        self.assertEqual(back["latitude"], 41.39)
        self.assertEqual(back["lunitidal_interval_minutes"], 330)
        # 'HH:MM' is accepted too
        self.assertEqual(codecs.encode_timeref(
            {"utc_seconds": 0, "lunitidal_interval_minutes": "05:30"})[18:], 
            codecs.encode_timeref({"utc_seconds": 0,
                                   "lunitidal_interval_minutes": 330})[18:])
        # -1 means "no data" and is the default
        self.assertEqual(codecs._lunitidal_minutes(-1), -1)
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_timeref({"utc_seconds": 0, "lunitidal_interval_minutes": -30})


class EndToEndTests(unittest.TestCase):
    """Read every section from canned default replies, then write them back."""

    BIN = "OW-FW-APP_HW_01.05.00_FW_00.01.10.bin"
    BASE = 0x08007000

    def _require_firmware(self):
        """Skip this test if the firmware binary is not present."""
        if not os.path.isfile(self.BIN):
            self.skipTest(f"firmware binary not found: {self.BIN}")

    def _default_replies(self) -> dict[int, bytes]:
        self._require_firmware()
        with open(self.BIN, "rb") as fh:
            data = fh.read()
        defs = data[0x080395B0 - self.BASE:0x080395B0 - self.BASE + 0x1F8]
        def rev(o, n):
            return defs[o:o + n][::-1]
        faces = b"".join(defs[0xFC + 6 * i:0xFC + 6 * i + 6] for i in range(4, 22))
        return {
            0x2A: defs[:0] + data[0x080397A8 - self.BASE:0x080397A8 - self.BASE + 32] + bytes(4),
            0x32: rev(0x44, 4) + rev(0x50, 4) + rev(0x4C, 4) + bytes([defs[0x26], defs[0x7C], defs[0xC0]]),
            0x2E: defs[0x20:0x26],
            0x2B: b"\x00" + defs[0x5D:0x5C + 12] + defs[0xAC:0xAC + 20],
            0x2C: bytes([1, 2, 3, 2]),
            0x37: faces,
            0x35: rev(0x40, 4) + defs[0x84:0x84 + 14],
            0x30: rev(0x48, 4) + bytes([defs[0x27]]),
            0x39: b"\x00\x06",
            0x3C: bytes(220),
        }

    def test_read_then_write_all_sections(self):
        from watchconfig.cli import _collect
        from watchconfig.sections import BY_KEY

        transport = ReplayTransport(self._default_replies())
        client = OapClient(transport)
        transport.set_notification_handler(client.feed)

        doc = asyncio.run(_collect(client, None))

        # every readable section was decoded into structured data
        for key in ("version", "config", "nametag", "alarms", "timer",
                    "faces", "worldtime", "health"):
            # conn_interval and pair_code are deliberately not config sections
            self.assertIn(key, doc, key)
        self.assertEqual(doc["nametag"], " o11ee")
        self.assertEqual(doc["config"]["features"]["temperature"], True)
        self.assertEqual(doc["health"]["step_goal"], 5000)
        self.assertEqual(len(doc["faces"]), 17)        # Clock is implicit

        # YAML round-trip, then re-encode every writable section back to the
        # exact bytes the watch sent.
        reloaded = yamlio.load(yamlio.dump(doc))
        for key in ("config", "nametag", "alarms", "faces", "worldtime", "health"):
            section = BY_KEY[key]
            original = self._default_replies()[section.get_cmd]
            if key == "faces":
                # id-keyed on the wire, so compare semantically
                self.assertEqual(
                    codecs.decode_faces(sections.encode_section(section, reloaded[key])),
                    codecs.decode_faces(original))
                continue
            if key == "alarms":
                # 0x25 = the 0x2B reply with a write-mode byte inserted at offset 8,
                # and the chime mask normalised to its 24 meaningful bits.
                mask = int.from_bytes(original[8:12], "little") & 0x00FFFFFF
                original = (original[:8] + b"\x00" + mask.to_bytes(4, "little")
                            + original[12:])
            self.assertEqual(
                sections.encode_section(section, reloaded[key]), original,
                f"{key} did not round-trip through YAML",
            )

        # The watch cannot report its timer presets, so a read yields the factory
        # defaults flagged as such, and writing is opt-in via overwrite.
        self.assertEqual(doc["timer"]["presets_seconds"],
                         [180, 30, 180, 30, 0, 60, 120, 600, 900, 1800])
        self.assertFalse(doc["timer"]["overwrite"])
        self.assertIsNotNone(BY_KEY["timer"].skip_write(doc["timer"]))
        reloaded["timer"]["overwrite"] = True
        self.assertIsNone(BY_KEY["timer"].skip_write(reloaded["timer"]))
        encoded = sections.encode_section(BY_KEY["timer"], reloaded["timer"])
        self.assertEqual(len(encoded), 44)
        # only the presets are carried: no duration, no action
        self.assertEqual(encoded[:4], bytes([0, 0, 0, 0]))
        self.assertEqual(struct.unpack("<10I", encoded[4:]), tuple(
            doc["timer"]["presets_seconds"]))


class ValidationTests(unittest.TestCase):
    """Invalid values must be rejected, not silently coerced."""

    def _config(self, **overrides):
        base = {"features": {}, "light_hold": "Hold only", "autosleep_period": 5,
                "led_rgb": {"red": 0, "green": 0, "blue": 0}, "led_brightness": "Max",
                "hour_format": "Toggle", "night_sleep": "off"}
        base.update(overrides)
        return base

    def test_other_enums_are_rejected(self):
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(hour_format=3))
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(light_hold=7))
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(night_sleep="24:00"))
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(autosleep_period=7))   # not an option
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(led_rgb={"red": 256}))
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_config(self._config(features={"no_such_feature": True}))

    def test_alarm_values_are_rejected(self):
        alarm = codecs.decode_alarm(b"\x05" + bytes(7) + b"\x00" * 4 + bytes(20))
        bad = dict(alarm)
        bad["snooze_period"] = 7                      # not 3/5/10/15/30
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_alarm(bad)
        bad = dict(alarm, minutes=60)
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_alarm(bad)
        bad = dict(alarm, chime="Nope")
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_alarm(bad)
        bad = dict(alarm, days=["Funday"])
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_alarm(bad)

    def test_worldtime_names_must_be_14_bytes(self):
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_worldtime({"offset_seconds": 0, "weekday_names": "TOO SHORT"})

    def test_timer_action_is_not_configurable(self):
        # the tool only ever sets the duration; it must never start a timer
        base = {"hours": 0, "minutes": 5, "seconds": 0, "overwrite": True,
                "presets_seconds": [180, 30, 180, 30, 0, 60, 120, 600, 900, 1800]}
        for action in (0, 1, 2, "start"):
            payload = codecs.encode_timer(dict(base, action=action))
            self.assertEqual(payload[3], 0)

    def test_timer_read_carries_only_the_presets(self):

        value = codecs.decode_timer(bytes([1, 2, 3, 2]))

        self.assertEqual(set(value), {"overwrite", "presets_seconds"})

        self.assertFalse(value["overwrite"])


    def test_weather_condition_range(self):
        days = [{"day": 1, "month": 1, "high": 0, "low": 0, "uv_index": 0,
                 "condition": 7, "precipitation_pct": 0, "humidity_pct": 0,
                 "feels_like": 0} for _ in range(5)]
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_weather({"days": days, "unit": "celsius"})


class WriteOnlySectionTests(unittest.TestCase):
    def test_io_test_payload(self):
        from watchconfig.cli import _io_payload
        self.assertEqual(_io_payload("a", 2), bytes([2, 0, 0]))
        self.assertEqual(_io_payload("b", 3), bytes([0, 3, 0]))
        self.assertEqual(_io_payload("c", 0), bytes([0, 0, 0]))
        self.assertEqual(_io_payload("1", 1), bytes([0, 1, 0]))
        self.assertEqual(len(_io_payload("a", 1)), 3)      # exactly 3 bytes
        for bad in ("d", "", "4"):
            with self.assertRaises(ValueError):
                _io_payload(bad, 1)
        with self.assertRaises(ValueError):
            _io_payload("a", 4)

    def test_accuracy_mode_defaults_off(self):

        # the owner confirmed the default is OFF: bit 19 stays clear unless asked

        config = {"features": {}, "light_hold": "hold", "autosleep_period": 5,

                  "led_rgb": {"red": 0, "green": 0, "blue": 0},

                  "led_brightness": 0, "hour_format": "toggle", "night_sleep": "off"}

        mask = int.from_bytes(codecs.encode_config(config)[0:4], "big")

        self.assertEqual(mask & 0x00080000, 0)              # bit 19 clear

        config["features"]["step_accuracy_mode"] = True

        mask = int.from_bytes(codecs.encode_config(config)[0:4], "big")

        self.assertEqual(mask & 0x00080000, 0x00080000)


    def test_weather_stub_is_skipped_unless_overwrite(self):
        weather = sections.BY_KEY["weather"]
        stub = weather.stub()
        self.assertFalse(stub["overwrite"])
        # a read hands out the stub, but a write must not push it
        self.assertIsNotNone(weather.skip_write(stub))
        stub["overwrite"] = True
        self.assertIsNone(weather.skip_write(stub))
        self.assertEqual(len(sections.encode_section(weather, stub)), 41)

    def test_command_policy(self):
        # 0x21 (OTA bootloader) is never sent by this tool
        self.assertIsNotNone(sections.command_problem(0x21))
        # 0x20 sub-command 0x02 wedges the firmware
        self.assertIsNotNone(sections.command_problem(0x20, bytes([0x02])))
        # 0x20 reset and 0x2D erase need confirmation; the databank does not
        self.assertIn(0x20, sections.DANGEROUS_CMDS)
        self.assertIn(0x2D, sections.DANGEROUS_CMDS)
        self.assertNotIn(0x3B, sections.DANGEROUS_CMDS)
        # normal commands are fine
        for cmd in (0x32, 0x33, 0x36, 0x3B, 0x20):
            self.assertIsNone(sections.command_problem(cmd, b"\x06"))

    def test_databank_limits(self):

        good = [{"tag": "note", "text": "ok"} for _ in range(10)]

        self.assertEqual(len(codecs.encode_databank(good)), 220)

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_databank(good[:9])                    # must be exactly 10

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_databank([{"tag": "note", "text": "x" * 22}] + good[1:])

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_databank([{"tag": "note", "text": "caf\u00e9"}] + good[1:])

        with self.assertRaises(codecs.InvalidValue):

            codecs.encode_databank([{"tag": "N", "text": "x"}] + good[1:])   # byte, not name


    def test_nametag_charset(self):
        # printable ASCII, spaces included
        self.assertEqual(codecs.encode_nametag("Ollee"), b"Ollee\x00")
        self.assertEqual(codecs.encode_nametag(" o1 1e"), b" o1 1e")
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_nametag("Ollee!\n")      # control character
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_nametag("caf\u00e9")       # non-ASCII
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_nametag("toolong")

    def test_worldtime_weekdays_are_monday_first(self):
        value = {"offset_seconds": 0, "weekdays": {
            "monday": "Mo", "tuesday": "Tu", "wednesday": "We", "thursday": "Th",
            "friday": "Fr", "saturday": "Sa", "sunday": "Su"}}
        wire = codecs.encode_worldtime(value)
        self.assertEqual(wire[4:], b"MoTuWeThFrSaSu")
        self.assertEqual(codecs.decode_worldtime(wire)["weekdays"]["sunday"], "Su")
        bad = dict(value, weekdays=dict(value["weekdays"], monday="Monday"))
        with self.assertRaises(codecs.InvalidValue):
            codecs.encode_worldtime(bad)

    def test_no_option_tables_leak_into_the_document(self):
        # guidance belongs in comments, not in fields
        doc = {"meta": yamlio.new_document()["meta"], "config": {"led_brightness": "Max"}}
        text = yamlio.dump(doc)
        loaded = yamlio.load(text)
        self.assertEqual(set(loaded["config"]), {"led_brightness"})


    def test_list_entries_document_their_fields(self):
        # regression: per-field help inside a list was being dropped
        doc = {"meta": yamlio.new_document()["meta"],
               "databank": [{"tag": "none", "text": ""} for _ in range(10)],
               "faces": [{"name": "Alarm", "enabled": True, "favorite": False}]}
        text = yamlio.dump(doc)
        self.assertIn("# tag: Entry type - 'none' (empty), 'phone'", text)
        self.assertIn("# text: Entry text: printable ASCII", text)
        self.assertIn("# name: READ-ONLY: the face name cannot be changed.", text)
        self.assertIn("# enabled: Whether the face is shown while swiping", text)
        # listed once, not once per entry
        self.assertEqual(text.count("# tag: Entry type"), 1)
        # and the document still parses
        self.assertEqual(len(yamlio.load(text)["databank"]), 10)


class CrashHandlingTests(unittest.TestCase):
    """A failed connection should read as an error, not a Python traceback."""

    def _run(self, exc, extra=None):
        import contextlib
        import io
        from watchconfig import cli

        async def boom(mac, *, timeout):
            raise exc

        err = io.StringIO()
        argv = ["read", "--mac", "X"] + (extra or [])
        with mock.patch.object(cli, "_open", boom):
            with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                rc = cli.main(argv)
        return rc, err.getvalue()

    def test_timeout_is_reported_cleanly(self):
        rc, err = self._run(TimeoutError())
        self.assertEqual(rc, 1)
        self.assertIn("timed out talking to the watch", err)
        self.assertNotIn("Traceback", err)

    def test_other_errors_are_reported_cleanly(self):
        rc, err = self._run(RuntimeError("device not found"))
        self.assertEqual(rc, 1)
        self.assertIn("device not found", err)
        self.assertNotIn("Traceback", err)

    def test_unexpected_error_hides_the_traceback_unless_verbose(self):
        rc, err = self._run(ValueError("boom"))
        self.assertEqual(rc, 1)
        self.assertNotIn("Traceback", err)
        self.assertIn("ValueError: boom", err)
        self.assertIn("use -v", err)


class CommentTests(unittest.TestCase):
    def test_fields_carry_help_comments(self):
        doc = {
            "meta": yamlio.new_document()["meta"],
            "config": {"led_brightness": 3, "hour_format": "24H"},
            "alarms": {"chime": "Breeze", "snooze_period": 5,
                       "slots": [{"hours": 7, "minutes": 0, "mode": "daily",
                                  "chime": "Classic"}]},
        }
        text = yamlio.dump(doc)
        self.assertIn("# Backlight brightness: 0 is off, 8 is brightest", text)
        
        self.assertIn("# Clock format - 'toggle'", text)
        self.assertIn("# Ringtone - one of Classic, Breeze", text)
        self.assertIn("# One daily alarm", text)
        # comments are user-facing: no command ids, no bit numbers, no byte offsets
        for leak in ("0x", "OAP", "feature bit", "byte 1", "offset"):
            self.assertNotIn(leak, text)

class OutputStreamTests(unittest.TestCase):
    """stdout must carry only the result, so `read | tee` works."""

    def _fake_open(self, replies):
        from watchconfig import cli
        from watchconfig.protocol import OapClient
        from watchconfig.transport import ReplayTransport

        transport = ReplayTransport(replies)
        client = OapClient(transport)
        transport.set_notification_handler(client.feed)

        async def fake_open(mac, *, timeout):
            cli.Log.info(f"[BLE] Connecting to {mac}...")
            return client, transport

        return cli, fake_open

    def test_read_stdout_is_pure_yaml(self):
        import contextlib
        import io
        from watchconfig import cli, yamlio

        version = (b"DEADBEEF" + b"01.05.00" + b"00.01.10" + b"DEADBEEF" + bytes(4))
        cli_mod, fake_open = self._fake_open({0x2A: version})
        args = cli_mod.build_parser().parse_args(
            ["read", "--mac", "X", "--only", "version"])

        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(cli_mod, "_open", fake_open):
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                rc = asyncio.run(cli_mod.cmd_read(args))

        self.assertEqual(rc, 0)
        document = yamlio.load(out.getvalue())          # stdout parses standalone
        self.assertEqual(document["version"]["hw_id"], "01.05.00")
        self.assertNotIn("[BLE]", out.getvalue())       # no diagnostics on stdout
        self.assertIn("[BLE]", err.getvalue())          # they went to stderr

    def test_command_listing_is_on_stdout(self):
        import contextlib
        import io
        from watchconfig import cli

        args = cli.build_parser().parse_args(["sections"])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = cli.main(["sections"])
        self.assertEqual(rc, 0)
        self.assertIn("config", out.getvalue())
        self.assertEqual(err.getvalue(), "")


class _FakeNusChar:
    def __init__(self, uuid):
        self.uuid = uuid


class _FakeNusServices:
    def __init__(self):
        self._chars = {framing.UART_TX: _FakeNusChar(framing.UART_TX),
                       framing.UART_RX: _FakeNusChar(framing.UART_RX)}

    def get_characteristic(self, uuid):
        return self._chars.get(uuid)


class _FakeNusClient:
    """BleakClient stand-in that enforces the ATT MTU, exactly like a real peer."""

    def __init__(self, address, timeout=None):
        self.services = _FakeNusServices()
        self.mtu_size = 23
        self.is_connected = False
        self.writes: list[bytes] = []

    async def connect(self):
        self.is_connected = True

    async def start_notify(self, char, callback):
        pass

    async def write_gatt_char(self, char, data, response=True):
        if len(data) > self.mtu_size - 3:
            # This is what BlueZ really says when a write exceeds the MTU.
            raise RuntimeError("org.bluez.Error.InvalidArguments: Invalid Length")
        self.writes.append(bytes(data))

    async def disconnect(self):
        self.is_connected = False


class BleTransportWriteTests(unittest.IsolatedAsyncioTestCase):
    """Regression: a write above the ATT MTU must be split, not rejected."""

    async def asyncSetUp(self):
        from watchconfig import transport as transport_mod

        self.transport_mod = transport_mod
        self._saved = transport_mod.BleakClient
        transport_mod.BleakClient = _FakeNusClient

    async def asyncTearDown(self):
        self.transport_mod.BleakClient = self._saved

    async def _connected(self):
        tr = self.transport_mod.BleTransport("AA:BB:CC:DD:EE:FF")
        await tr.connect()
        return tr

    async def test_large_frame_is_chunked_to_the_mtu(self):
        tr = await self._connected()
        frame = framing.build_nus_frame(0x3B, bytes(220))        # 227 bytes
        await tr.send(frame)
        self.assertGreater(len(tr.client.writes), 1)
        self.assertTrue(all(len(w) <= 20 for w in tr.client.writes))
        # the far side sees the same byte stream, so it reassembles the frame
        stream = b"".join(tr.client.writes)
        self.assertEqual(stream, frame)
        self.assertEqual([f.cmd for f in framing.FrameReassembler().feed(stream)],
                         [0x3B])

    async def test_small_frame_is_a_single_write(self):
        tr = await self._connected()
        frame = framing.build_nus_frame(0x31)                    # 8 bytes
        await tr.send(frame)
        self.assertEqual(tr.client.writes, [frame])

    async def test_frame_bigger_than_the_watch_buffer_is_refused(self):
        tr = await self._connected()
        frame = framing.build_nus_frame(0x3B, bytes(self.transport_mod.WATCH_RX_BUFFER))
        with self.assertRaises(RuntimeError):
            await tr.send(frame)
        self.assertEqual(tr.client.writes, [])


if __name__ == "__main__":
    unittest.main()
