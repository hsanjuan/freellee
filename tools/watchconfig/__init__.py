"""Ollee Watch user-configuration tooling.

Read/write the watch's user configuration over BLE (Nordic UART Service) as YAML.

Layout:
    protocol   wire framing (NUS/OAP) + command table + request/response client
    transport  bleak BLE transport (the only part that needs a radio)
    sections   per-command payload codecs (bytes <-> plain Python values)
    yamlio     YAML document <-> section values
    cli        `ollee-config read|write|diff`

The protocol core never imports bleak, so it stays unit-testable offline.
"""

__all__ = ["protocol", "transport", "sections", "yamlio", "cli"]
