# Ollee Watch configuration tooling

Read the watch's user configuration over BLE into YAML, edit it, write it back.

```
./ollee-config read  --mac AA:BB:CC:DD:EE:FF -o watch.yaml
$EDITOR watch.yaml
./ollee-config write --mac AA:BB:CC:DD:EE:FF watch.yaml
```

Requires Python 3.11+, `bleak`, and `PyYAML`
(`pip install bleak pyyaml cryptography`). BLE needs root or the right
permissions, same as `flash_ollee.py`.

## Commands

| command | what it does |
|---|---|
| `read` | query every readable section and emit YAML (`-o FILE`, `--only A B`) |
| `write` | apply a YAML file (`-n/--dry-run`, `--force` for destructive sections) |
| `diff` | show field-level differences between the watch and a YAML file |
| `raw CMD [--payload HEX]` | send any OAP command and print the reply |
| `test io <channel> [level]` | drive a debug hardware channel (buzzer/light) |
| `sections` | list the configuration sections and their command bytes |
| `commands` | list every OAP command the firmware implements |

`ollee-config test io <channel> <level>` injects a **synthetic button gesture**
(0x38) — it is not a hardware output test. The three channels are the watch's own
buttons:

`ollee-config test io <button> <level>` injects a **synthetic button press** — it
is not a hardware test. `button` is `a`/`alarm`, `b`/`mode` or `c`/`light`, and
`level` is the gesture: **1 = short press, 2 = long press, 3 = hold**.

Note: `alarm 3` enters a debug screen (it shows the watch name).

`--dry-run` prints the bytes without sending (and needs no `--mac`).

`--mac`, `--timeout` and `-v` work before or after the sub-command.

`--timeout` is the per-reply wait (default 8 s). **Connecting** is capped at 10 s:
the watch switches its Bluetooth radio off when it is idle, so if it has not
answered by then it is asleep or out of range rather than slow.

Writes longer than the negotiated ATT MTU (`mtu - 3` bytes) are split into
several writes; the watch reassembles the NUS frame from the fragments. This is
required — BlueZ rejects an oversized write outright (`Invalid Length`).

**Streams:** stdout carries *only* the result — the YAML for `read`, the listing
for `sections`/`commands`, the hex for `raw`, the differences for `diff` — and
every diagnostic (`[BLE] ...`, progress, warnings) goes to **stderr**. So this
does what you expect:

```bash
./ollee-config read --mac AA:BB:CC:DD:EE:FF | tee config.yaml
```

## Layout

| module | role |
|---|---|
| `framing.py` | NUS framing: CRC, `build_nus_frame`, `Frame`/`FrameReassembler`, UUIDs (no deps) |
| `protocol.py` | the OAP command table + request/response client |
| `transport.py` | `BleTransport` (bleak) and `ReplayTransport` (offline) |
| `sections.py` | section registry: getter/setter pairing + codecs + dangerous-command list |
| `codecs.py` | per-command payload <-> Python value translation |
| `yamlio.py` | YAML document load/dump |
| `cli.py` | the `ollee-config` command line |

`watchconfig/` is self-contained: copy this directory plus the `ollee-config`
wrapper anywhere and `pip install bleak pyyaml` is all it needs. The protocol
core never imports bleak, so `python3 -m unittest test_watchconfig`
(27 tests) runs with no radio and no watch. Codecs are checked against the
watch's factory-default wire values, so a decode that disagrees with the
device's defaults fails the tests.
(`flash_ollee.py` imports `watchconfig/framing.py`, not the other way round.)

## YAML shape

```yaml
meta:
  doc_version: 1
  generated: '2026-09-30T21:42:33Z'
version:            # read-only
  hash_id: DEADBEEF
  hw_id: '01.05.00'
  fw_id: '00.01.10'
  serial: DEADBEEF
config: ...         # device settings (config register)
nametag: Hector
alarms: ...         # main alarm (on/off, time, day mask, chime, snooze) + 5 daily slots
timer: ...          # countdown + 10 presets (seconds) - needs overwrite: true
faces: ...          # 18 faces: enabled + swipe order
worldtime: ...      # offset_seconds + weekday names
health: ...         # step goal + pulsometer target beats
timeref: ...        # write-only: UTC, timezone, lat/lon, lunitidal (the watch's
                    # only source of coordinates - the phone supplies them)
weather: ...        # write-only: 5-day forecast pushed by the phone; no getter
io: ...             # debug: buzzer/haptic channels
databank: ...       # 10 entries {tag, text}
```

Enum-valued settings accept their labels (`led_brightness: Level 5`,
`hour_format: 24H`, `night_sleep: '01:00'`, `light_hold: 2 seconds`) or a raw
integer. Every field carries a `#` comment naming it and listing its valid
values, so the file is self-documenting — guidance is never stored as data.

## Validation

Values are validated, not second-guessed. If a value cannot be represented the
tool refuses to write and tells you why, rather than guessing:

```
config.led_brightness: 9 is out of range - valid values are 0..8,
  i.e. ['Max', 'Level 7', 'Level 6', 'Level 5', 'Level 4', 'Level 3', 'Level 2', 'Level 1', 'Off']
```

`ollee-config write --dry-run` validates and encodes the whole file **without
connecting**, so you can check an edit offline.

## Write-only sections

Some things cannot be read back from the watch (timer presets, the weather
forecast). Their sections are emitted by `read` with `overwrite: false` and are
skipped on write unless you set it to `true` — that way a read/modify/write cycle
never silently replaces data the tool could not see.

Sections not yet decoded appear as hex strings, so nothing is lost.

## Safety

* Never sent, with no override: `0x21` (reboots into the OTA bootloader — that
  belongs to `flash_ollee.py`) and `0x20` sub-command `0x02` (spins forever in the
  firmware; only a hard reset recovers).
* Require `--force` **and** a typed confirmation: `0x20` (reset; sub-command
  `0x01` wipes settings *and* stored activity) and `0x2D` (erase all activity).
* `0x3B` (databank) is a normal write: it zero-fills the 284 unused trailing
  bytes of the record, which nothing reads.
* Some commands are deliberately not part of the config file at all: the OTA
  bootloader, the pairing screen (`0x29`) and the BLE connection interval (`0x39`,
  read-only with no setter). Use `ollee-config raw` if you ever need them.
* ⚠ **The watch keeps its settings in RAM only.** They survive a warm reboot but
  are lost when the battery is removed; on a cold boot the firmware restores its
  factory defaults. So writing settings is not persistent storage — it is
  configuration for the current power cycle.

## Open questions

Some fields can be named but not fully enumerated (valid values, unit,
semantics); those are marked `UNKNOWN` where they appear.
