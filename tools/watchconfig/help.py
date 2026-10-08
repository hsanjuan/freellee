"""Human-readable help for every YAML field.

These strings become ``#`` comments in the dumped document (never data) and are
keyed by dotted path, e.g. ``config.led_brightness`` or ``faces[]``.

They are written for someone configuring their watch: no byte offsets, command
ids or bit numbers.
"""
from __future__ import annotations

from . import codecs

_PCT = "0-100"


def _opt(options) -> str:
    return ", ".join(str(o) for o in options)


def _feature_help() -> dict[str, str]:
    out = {"config.features": "Turn watch features on or off."}
    for _bit, (key, name) in codecs.FEATURE_BITS.items():
        out[f"config.features.{key}"] = name
    # a few are worth explaining rather than just naming
    out["config.features.step_accuracy_mode"] = (
        "Step count accuracy mode - the watch face shows '(H)igh sensitivity' when "
        "this is false and '(L)ow sensitivity' when it is true")
    out["config.features.autosleep_on"] = (
        "Auto sleep - turns the screen off by itself (see autosleep_period below)")
    out["config.features.temperature"] = (
        "Temperature - logs a skin-temperature reading every few minutes")
    return out


SECTION_HELP: dict[str, str] = {
    "meta": "Bookkeeping for this file. Ignored when writing.",
    "version": "READ-ONLY. Identifies the watch and its firmware.",
    "config": "The watch's device settings - most of what you can change.",
    "nametag": ("The name shown for the watch over Bluetooth - up to 6 characters, "
                "printable ASCII only."),
    "alarms": ("One main alarm - it can repeat on chosen weekdays and has snooze - "
               "plus 5 daily alarms, each just a time and a chime."),
    "timer": ("Countdown timer presets. The watch cannot report its presets back, "
              "so they are only sent when overwrite is true."),
    "faces": ("The watch faces, in the order they appear when you swipe - exactly 17 "
              "entries (the Clock face is always first and is not listed here)."),
    "worldtime": "World-time offset and the weekday labels.",
    "health": "Step goal and the heart-rate target.",
    "databank": "Exactly 10 short text entries stored on the watch.",
    "weather": ("WRITE-ONLY. A 5-day forecast the phone pushes; the watch cannot "
                "report it back, so it is skipped unless overwrite is true."),
    "timeref": ("WRITE-ONLY. Syncs the clock, timezone and location in one go, and is "
                "skipped unless overwrite is true."),
    "io": "DEBUG. Simulates a button press.",
}

FIELD_HELP: dict[str, str] = {
    # meta
    "meta.doc_version": "File format version - leave as is.",
    "meta.generated": "When this file was produced.",
    # version
    "version.hash_id": "Firmware build tag.",
    "version.hw_id": "Hardware revision.",
    "version.fw_id": "Firmware revision.",
    "version.serial": "Serial/build tag.",
    "version.voltage": "Battery voltage in millivolts (2815 means 2.815 V).",
    # config
    "config.light_hold": ("What a long press of the LIGHT button does - one of "
                          f"{_opt(codecs.LIGHT_HOLD_VALUES)} "
                          "('hold' keeps the light on while held; the numbers are seconds)"),
    "config.autosleep_period": ("How long to wait before auto sleep turns the screen off, "
                                f"in seconds - one of {sorted(codecs.AUTOSLEEP_OPTIONS)}"),
    "config.led_rgb": "Backlight colour as red/green/blue, each 0-255",
    "config.led_rgb.red": "Red, 0-255",
    "config.led_rgb.green": "Green, 0-255",
    "config.led_rgb.blue": "Blue, 0-255",
    "config.led_rgb.alpha": ("UNUSED: the backlight ignores this. Only present because "
                             "the watch is storing something other than its default."),
    "config.led_brightness": ("Backlight brightness: 0 is off, 8 is brightest, and "
                              "each step in between is one level (values above 8 do "
                              "not exist)."),
    "config.hour_format": "Clock format - 'toggle' (tap to switch), 'am/pm' or '24h'",
    "config.night_sleep": ("Night mode: 'off', or an hour as 'HH:00'. When an hour is "
                           "chosen the face stays dark until a button is pressed."),
    "config.unknown_bits": ("UNKNOWN: settings the watch reports that this tool does not "
                            "recognise. Preserved as-is; nothing else was changed."),
    # nametag
    "nametag": "Up to 6 characters, printable ASCII.",
    # alarms
    "alarms.alarm_on": ("The main alarm's on/off switch. Note: when read back, the watch "
                        "reports whether it will actually ring today, so it can show "
                        "false even when it is switched on."),
    "alarms.hourly_chime_on": "Hourly chime on or off.",
    "alarms.snooze_on": "Whether the main alarm snoozes.",
    "alarms.hours": "Alarm hour.",
    "alarms.minutes": "Alarm minute.",
    "alarms.days": ("Weekdays the main alarm repeats on - choose from "
                    f"{_opt(codecs.WEEKDAYS)}. Leave empty for a one-off alarm."),
    "alarms.chime": f"Ringtone - one of {_opt(codecs.ALARM_CHIMES)}",
    "alarms.snooze_period": ("How long snooze lasts, in minutes - one of "
                             f"{sorted(codecs.SNOOZE_OPTIONS)}"),
    "alarms.hourly_chime_hours": "The hours (0-23) at which the hourly chime sounds.",
    "alarms.slots": ("Exactly 5 daily alarms - a time and a chime each."),
    "alarms.slots[]": "One daily alarm",
    "alarms.slots[].hours": "Alarm hour.",
    "alarms.slots[].minutes": "Alarm minute.",
    "alarms.slots[].mode": ("'off', 'daily' (rings every day) or 'one_shot' (rings once, "
                            "then switches itself off)"),
    "alarms.slots[].chime": f"Ringtone - one of {_opt(codecs.ALARM_CHIMES)}",
    # timer
    "timer.overwrite": "Set true to send these presets (they cannot be read back)",
    "timer.presets_seconds": ("The 10 timer presets in seconds. In interval mode the "
                              "watch runs them in order, stopping at the first 0. The "
                              "watch cannot report them, so these are the factory values. "
                              "Exactly 10 values are required."),
    # faces
    "faces": "One entry per face, in swipe order - exactly 17 faces (the Clock face is always first and is not listed here).",
    "faces[]": "One face. The name identifies it and cannot be changed.",
    "faces[].name": "READ-ONLY: the face name cannot be changed.",
    "faces[].enabled": "Whether the face is shown while swiping",
    "faces[].favorite": "The favourite face, reached by the double-tilt gesture (only one)",
    # worldtime
    "worldtime.offset_hours": ("Home-time offset from UTC in hours (typically -12 to +14)."),
    "worldtime.offset_seconds": "Offset from home time in seconds (21600 is +6 hours)",
    "worldtime.weekdays": "The two-letter label for each day, Monday first",
    "worldtime.weekday_names": "Alternative to the per-day labels: the raw 14-character string (Monday first).",
    # health
    "health.step_goal": "Daily step goal (the app allows 100 to 500000, in steps of 500).",
    "health.pulsometer_target_beats": ("Heart-rate target, as shown on the heart-rate "
                                       "screen - one of 5, 10, 20 or 30 beats"),
    # databank
    "databank": "One entry per slot.",
    "databank[]": "One databank entry",
    "databank[].tag": ("Entry type - 'none' (empty), 'phone', 'list', 'note', "
                       "'reminder' or 'password'"),
    "databank[].text": "Entry text: printable ASCII, up to 21 characters",
    # weather
    "weather.overwrite": "Set true to actually push a forecast",
    "weather.unit": "Temperature unit: 'celsius' or 'fahrenheit'",
    "weather.days": "One record per forecast day, tomorrow first - exactly 5 days.",
    "weather.days[]": "One forecast day",
    "weather.days[].month": "Month",
    "weather.days[].high": "High temperature, in the unit above",
    "weather.days[].low": "Low temperature",
    "weather.days[].feels_like": "Feels-like temperature",
    "weather.days[].uv_index": "UV index, 0-15",
    "weather.days[].condition": f"Sky conditions - one of {_opt(codecs.WEATHER_CONDITIONS)}",
    "weather.days[].precipitation_pct": f"Chance of precipitation, {_PCT}",
    "weather.days[].humidity_pct": f"Humidity, {_PCT}",
    # timeref
    "timeref.overwrite": "Set true to actually send this sync",
    "timeref.latitude": ("Latitude in -90..90, used for sunrise/sunset and the tide face "
                         "(the phone normally provides this)"),
    "timeref.longitude": "Longitude in -180..180",
    "timeref.timezone_offset_seconds": ("Offset from UTC in seconds; defaults to this "
                                        "computer's current offset"),
    "timeref.lunitidal_interval_minutes": ("Lunitidal interval - the gap between the moon "
                                           "passing overhead and high tide at your "
                                           "location, in minutes (or 'HH:MM'). The phone "
                                           "works it out from tide data, so leave it at "
                                           "-1 unless you know better."),
}

FIELD_HELP.update(_feature_help())
