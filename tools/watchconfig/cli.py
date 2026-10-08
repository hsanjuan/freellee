"""``ollee-config`` — read/write the watch's user configuration as YAML."""
from __future__ import annotations

import argparse
import asyncio
import sys
from typing import Any, Optional

from . import codecs, sections, yamlio
from .protocol import OapClient, OapError
from .sections import (
    BY_KEY,
    DANGEROUS_CMDS,
    SECTIONS,
    command_problem,
)

READ_TIMEOUT = 8.0


class Log:
    """All diagnostics go to stderr so stdout carries only the result.

    That is what makes `ollee-config read | tee config.yaml` work: the YAML is
    the only thing on stdout.
    """

    verbose = False

    @staticmethod
    def _emit(level: str, msg: str) -> None:
        prefix = f"[{level}] " if level else ""
        print(prefix + msg, file=sys.stderr, flush=True)

    @staticmethod
    def info(msg: str) -> None:
        Log._emit("", msg)          # status -> stderr

    @staticmethod
    def warn(msg: str) -> None:
        Log._emit("WARN", msg)

    @staticmethod
    def error(msg: str) -> None:
        Log._emit("ERROR", msg)

    @staticmethod
    def debug(msg: str) -> None:
        if Log.verbose:
            Log._emit("DEBUG", msg)

    @staticmethod
    def out(msg: str = "") -> None:
        """Primary result -> stdout (the only thing that goes there)."""
        print(msg, file=sys.stdout, flush=True)


# ── transport plumbing ──────────────────────────────────────────────────────

async def _open(mac: str, *, timeout: float) -> tuple[OapClient, Any]:
    from .transport import BleTransport

    transport = BleTransport(mac)
    Log.info(f"[BLE] Connecting to {mac}...")
    await transport.connect()
    client = OapClient(transport, timeout=timeout)
    transport.set_notification_handler(client.feed)
    Log.debug(f"[BLE] Negotiated MTU {transport.mtu_size}")
    Log.info("[BLE] Connected (NUS subscribed)")
    return client, transport


# ── read ────────────────────────────────────────────────────────────────────

async def _collect(client: OapClient, keys: Optional[list[str]]) -> dict[str, Any]:
    doc: dict[str, Any] = {}
    for section in SECTIONS:
        if keys and section.key not in keys:
            continue
        if section.get_cmd is None:
            if section.stub is not None:
                doc[section.key] = section.stub()
            continue
        Log.debug(f"[OAP] get {section.key} (0x{section.get_cmd:02X})")
        try:
            payload = await client.request(section.get_cmd)
        except OapError as exc:
            Log.warn(f"{section.key}: {exc}")
            continue
        doc[section.key] = sections.decode_section(section, payload)
        Log.debug(f"[OAP]   {len(payload)} bytes: {payload.hex()}")
    return doc


async def cmd_read(args: argparse.Namespace) -> int:
    client, transport = await _open(args.mac, timeout=args.timeout)
    try:
        doc = yamlio.new_document()
        doc.update(await _collect(client, args.only))
    finally:
        await transport.disconnect()

    text = yamlio.dump(doc)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
        Log.info(f"[OK] Configuration written to {args.output}")
    else:
        sys.stdout.write(text)
    return 0


# ── write ───────────────────────────────────────────────────────────────────

async def cmd_write(args: argparse.Namespace) -> int:
    doc = yamlio.load_file(args.file)
    client = transport = None
    if not args.dry_run:
        client, transport = await _open(args.mac, timeout=args.timeout)
    failed = 0
    try:
        for section in SECTIONS:
            if section.key not in doc:
                continue
            if section.read_only or section.set_cmd is None:
                if not args.quiet:
                    Log.info(f"[SKIP] {section.key} (read-only)")
                continue
            if section.set_cmd in DANGEROUS_CMDS and not args.force:
                Log.error(
                    f"{section.key}: command 0x{section.set_cmd:02X} is destructive; "
                    f"re-run with --force if you really mean it"
                )
                failed += 1
                continue
            if section.skip_write is not None:
                reason = section.skip_write(doc[section.key])
                if reason:
                    if not args.quiet:
                        Log.info(f"[SKIP] {section.key}: {reason}")
                    continue
            try:
                payload = sections.encode_section(section, doc[section.key])
            except Exception as exc:  # noqa: BLE001 - user data
                Log.error(f"{section.key}: cannot encode: {exc}")
                failed += 1
                continue
            if args.dry_run:
                Log.info(f"[DRY] set {section.key} (0x{section.set_cmd:02X}) "
                         f"<- {payload.hex()}")
                continue
            try:
                await client.request(section.set_cmd, payload)
                Log.info(f"[OK] {section.key} set ({len(payload)} bytes)")
            except OapError as exc:
                Log.error(f"{section.key}: {exc}")
                failed += 1
    finally:
        if transport is not None:
            await transport.disconnect()
    return 1 if failed else 0


# ── diff ────────────────────────────────────────────────────────────────────

async def _flatten(value: Any, prefix: str = "") -> dict[str, Any]:
    out: dict[str, Any] = {}
    if isinstance(value, dict):
        for k, v in value.items():
            out.update(await _flatten(v, f"{prefix}.{k}" if prefix else str(k)))
    elif isinstance(value, list):
        for i, v in enumerate(value):
            out.update(await _flatten(v, f"{prefix}[{i}]"))
    else:
        out[prefix] = value
    return out


async def cmd_diff(args: argparse.Namespace) -> int:
    want = yamlio.load_file(args.file)
    client, transport = await _open(args.mac, timeout=args.timeout)
    try:
        have = await _collect(client, None)
    finally:
        await transport.disconnect()

    changes = 0
    for key in BY_KEY:
        if key not in want:
            continue
        a = await _flatten(have.get(key))
        b = await _flatten(want.get(key))
        for field in sorted(set(a) | set(b)):
            if a.get(field) != b.get(field):
                Log.out(f"{key}.{field}: watch={a.get(field)!r} file={b.get(field)!r}")
                changes += 1
    Log.info(f"[OK] {changes} difference(s)")
    return 0


# ── hardware test ───────────────────────────────────────────────────────────

IO_BUTTONS = {"a": 0, "b": 1, "c": 2, "0": 0, "1": 1, "2": 2,
              "alarm": 0, "mode": 1, "light": 2}


def _io_payload(button: str, level: int) -> bytes:
    key = str(button).strip().lower()
    if key not in IO_BUTTONS:
        raise ValueError(f"button must be a/alarm, b/mode or c/light, got {button!r}")
    if not 0 <= level <= 3:
        raise ValueError(f"level must be 0-3 (0 turns it off), got {level}")
    bytes_ = [0, 0, 0]
    bytes_[IO_BUTTONS[key]] = level
    return codecs.encode_io({"button_a": bytes_[0], "button_b": bytes_[1],
                             "button_c": bytes_[2]})


async def cmd_test_io(args: argparse.Namespace) -> int:
    """One-off button test via the debug command (0x38)."""
    try:
        payload = _io_payload(args.button, args.level)
    except ValueError as exc:
        Log.error(str(exc))
        return 2
    if args.dry_run:
        Log.info(f"[DRY] button {args.button.upper()} level {args.level} "
                 f"-> 0x38 {payload.hex()}")
        return 0
    client, transport = await _open(args.mac, timeout=args.timeout)
    try:
        await client.request(0x38, payload)
    finally:
        await transport.disconnect()
    Log.info(f"[OK] button {args.button.upper()} level {args.level} ({payload.hex()})")
    return 0


# ── raw ─────────────────────────────────────────────────────────────────────

def _confirm(prompt: str, *, assume_yes: bool) -> bool:
    """Loud confirmation for a destructive command."""
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        Log.error("refusing a destructive command without a terminal; pass --yes")
        return False
    print("!", "!" * 70, sep="")
    print(f"  {prompt}")
    print("!", "!" * 70, sep="")
    return input("Type 'yes' to continue: ").strip().lower() == "yes"


async def cmd_raw(args: argparse.Namespace) -> int:
    cmd = int(args.cmd, 0)
    payload = bytes.fromhex(args.payload) if args.payload else b""
    problem = command_problem(cmd, payload)
    if problem:
        Log.error(problem)
        return 2
    if cmd in DANGEROUS_CMDS:
        if not args.force:
            Log.error(
                f"0x{cmd:02X} destroys user data; re-run with --force if you mean it"
            )
            return 2
        what = "erase ALL stored activity" if cmd == 0x2D else "reset the watch"
        if not _confirm(f"About to {what} (command 0x{cmd:02X}).",
                        assume_yes=getattr(args, "yes", False)):
            Log.warn("aborted")
            return 2
    client, transport = await _open(args.mac, timeout=args.timeout)
    try:
        reply = await client.request(cmd, payload)
    finally:
        await transport.disconnect()
    Log.out(f"{reply.hex()}")
    return 0


# ── CLI ─────────────────────────────────────────────────────────────────────

def _common_parent(suppress: bool) -> argparse.ArgumentParser:
    """Common options.  The sub-parser copy suppresses defaults so that a value
    given *before* the sub-command is not clobbered by an unused default."""
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument("--mac", help="watch Bluetooth address")
    parent.add_argument("--timeout", type=float, default=READ_TIMEOUT,
                        help="per-request timeout (s)")
    parent.add_argument("-v", "--verbose", action="store_true")
    if suppress:
        for action in parent._actions:
            action.default = argparse.SUPPRESS
    return parent


def build_parser() -> argparse.ArgumentParser:
    # Common options are accepted both before and after the sub-command.
    main_common = _common_parent(False)
    sub_common = _common_parent(True)

    p = argparse.ArgumentParser(
        prog="ollee-config",
        description="Read/write Ollee Watch user configuration as YAML",
        parents=[main_common],
    )
    sub = p.add_subparsers(dest="subcommand", required=True)

    r = sub.add_parser("read", help="read the watch configuration into YAML",
                       parents=[sub_common])
    r.add_argument("-o", "--output", help="write to this file (default: stdout)")
    r.add_argument("--only", nargs="+", metavar="SECTION", help="limit to these sections")
    r.set_defaults(func=cmd_read)

    w = sub.add_parser("write", help="apply a YAML configuration to the watch",
                       parents=[sub_common])
    w.add_argument("file")
    w.add_argument("-n", "--dry-run", action="store_true", help="show what would be sent")
    w.add_argument("-q", "--quiet", action="store_true")
    w.add_argument("--force", action="store_true",
                   help="allow destructive sections (databank etc.)")
    w.set_defaults(func=cmd_write)

    d = sub.add_parser("diff", help="compare the watch against a YAML file",
                       parents=[sub_common])
    d.add_argument("file")
    d.set_defaults(func=cmd_diff)

    x = sub.add_parser("raw", help="send a raw OAP command", parents=[sub_common])
    x.add_argument("cmd", help="command byte, e.g. 0x32")
    x.add_argument("--payload", default="", help="hex payload")
    x.add_argument("--force", action="store_true",
                   help="allow destructive commands (0x20 reset, 0x2D erase activity)")
    x.add_argument("--yes", action="store_true",
                   help="skip the interactive confirmation for destructive commands")
    x.set_defaults(func=cmd_raw)

    t = sub.add_parser("test", help="one-off hardware test", parents=[sub_common])
    tsub = t.add_subparsers(dest="test_kind", required=True)
    tio = tsub.add_parser(
        "io",
        parents=[sub_common],
        help="press a watch button (for testing)",
        description="Injects a synthetic button press (for testing).",
        epilog=("button: a = ALARM, b = MODE, c = LIGHT\n"
                "level:  1 = short press, 2 = long press, 3 = hold\n"
                "\nNote: alarm 3 enters a debug screen (it shows the watch name).\n"),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    tio.add_argument("button", help="a/alarm, b/mode or c/light (or 0, 1, 2)")
    tio.add_argument("level", nargs="?", type=int, default=1,
                     help="1 = short press, 2 = long press, 3 = hold; 0 does nothing")
    tio.add_argument("-n", "--dry-run", action="store_true", help="print, do not send")
    tio.set_defaults(func=cmd_test_io)

    sub.add_parser("sections", help="list configuration sections").set_defaults(
        func=lambda a: (print(sections.list_sections()), 0)[1]
    )
    sub.add_parser("commands", help="list every OAP command").set_defaults(
        func=lambda a: (print(sections.list_commands()), 0)[1]
    )
    return p


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    Log.verbose = getattr(args, "verbose", False)
    if getattr(args, "func", None) is None:
        return 2
    args.mac = getattr(args, "mac", None)
    args.timeout = getattr(args, "timeout", READ_TIMEOUT)
    if (not args.mac and args.subcommand not in {"sections", "commands"}
            and not getattr(args, "dry_run", False)):
        Log.error("--mac is required")
        return 2
    try:
        result = args.func(args)
        if asyncio.iscoroutine(result):
            return asyncio.run(result)
        return int(result) if result is not None else 0
    except KeyboardInterrupt:
        Log.warn("interrupted")
        return 130
    except (OapError, yamlio.YamlError, RuntimeError) as exc:
        Log.error(str(exc))
        return 1
    except TimeoutError:
        Log.error(
            "timed out talking to the watch - make sure it is awake, in range, and "
            "not still connected to the phone"
        )
        return 1
    except OSError as exc:
        Log.error(f"Bluetooth error: {exc}")
        return 1
    except Exception as exc:  # noqa: BLE001 - a CLI should not dump a traceback
        if Log.verbose:
            raise
        Log.error(f"{type(exc).__name__}: {exc} (use -v for the full traceback)")
        return 1


if __name__ == "__main__":
    sys.exit(main())
