# Ollee Watch Tools

BLE utilities for the Ollee Watch smartwatch (STM32WB55).

## Contents

| Tool | Description |
|------|-------------|
| `flash_ollee.py` | OTA firmware flasher via BLE (NUS + STM32 bootloader) |
| `ollee-config` | YAML-based config reader/writer (reads and writes watch settings) |
| `watchconfig/` | Python library used by both tools |

## Requirements

```bash
pip install bleak cryptography pyyaml
```

## Usage

### Flash firmware

```bash
sudo python3 flash_ollee.py --mac AA:BB:CC:DD:EE:FF [--firmware <path>]
python3 flash_ollee.py --dry-run      # offline validation only
```

### Read / write settings

```bash
./ollee-config --mac AA:BB:CC:DD:EE:FF read   > config.yaml
./ollee-config --mac AA:BB:CC:DD:EE:FF write  config.yaml
./ollee-config --mac AA:BB:CC:DD:EE:FF diff   config.yaml
```

See `watchconfig/README.md` for the full command reference and safety policy.

## License

GPLv3 — see [LICENSE](LICENSE).
