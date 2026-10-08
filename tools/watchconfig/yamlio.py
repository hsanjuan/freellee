"""YAML document handling for the watch configuration.

The document is deliberately plain: top-level keys are section names, plus a
``meta`` block (read-only context).  Values are ordinary YAML scalars, sequences
and mappings.  Human guidance lives in ``#`` comments (from :mod:`watchconfig.help`),
never as extra fields, so the file stays unambiguous to edit and re-write.
"""
from __future__ import annotations

import datetime as _dt
from typing import Any

import yaml

from .help import FIELD_HELP, SECTION_HELP

DOC_VERSION = 1

_HEADER = (
    "# Ollee Watch user configuration\n"
    "#\n"
    "# Written by `ollee-config read`, applied by `ollee-config write`.\n"
    "# Comments explain each field and its valid values; they are ignored on read.\n"
    "# Sections marked READ-ONLY are not sent back to the watch.\n"
    "#\n"
    "# Deliberately absent (not user configuration): the OTA-bootloader command, the\n"
    "# pairing screen and the BLE connection interval. Use `ollee-config raw` for those.\n"
)


class YamlError(ValueError):
    pass


def _now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def new_document(firmware_version: str | None = None) -> dict[str, Any]:
    meta: dict[str, Any] = {"doc_version": DOC_VERSION, "generated": _now()}
    if firmware_version:
        meta["firmware_version"] = firmware_version
    return {"meta": meta}


# ── dumping (with comments) ─────────────────────────────────────────────────

def _scalar(value: Any) -> str:
    """Render a scalar the way YAML would, so quoting is always correct."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, str):
        # dumping {x: value} gets PyYAML's quoting rules exactly right (e.g. it
        # quotes '13:00', which would otherwise parse as a sexagesimal integer)
        return yaml.safe_dump({"x": value}, allow_unicode=True).split(":", 1)[1].strip()
    return str(value)


def _comment_lines(text: str) -> list[str]:
    return text.splitlines() or [""]


def _emit_comment(out: list[str], text: str, indent: int) -> None:
    for line in _comment_lines(text):
        out.append(" " * indent + ("# " + line if line else "#"))


def _render(obj: Any, path: str, out: list[str], indent: int) -> None:
    pad = " " * indent
    if isinstance(obj, dict):
        for key, value in obj.items():
            if str(key).startswith("_"):
                # comment-only key: never written as data
                if value:
                    _emit_comment(out, str(value), indent)
                continue
            child = f"{path}.{key}" if path else str(key)
            help_text = FIELD_HELP.get(child)
            if help_text:
                _emit_comment(out, help_text, indent)
            if isinstance(value, dict):
                if not value:
                    out.append(f"{pad}{key}: {{}}")
                else:
                    out.append(f"{pad}{key}:")
                    _render(value, child, out, indent + 2)
            elif isinstance(value, list):
                if not value:
                    out.append(f"{pad}{key}: []")
                else:
                    out.append(f"{pad}{key}:")
                    _render_list(value, child, out, indent)
            else:
                out.append(f"{pad}{key}: {_scalar(value)}")
    else:  # pragma: no cover - the document root is always a mapping
        out.append(f"{pad}{_scalar(obj)}")


def _render_list(items: list, path: str, out: list[str], indent: int) -> None:
    """Render a sequence.  A sequence of mappings is emitted as block entries,
    with the element's guidance shown once, above the first entry."""
    entry_pad = " " * (indent + 2)
    if any(isinstance(i, dict) for i in items):
        element_help = FIELD_HELP.get(path + "[]")
        if element_help:
            _emit_comment(out, element_help, indent + 2)
        # per-field guidance, listed once above the entries rather than repeated
        for item in items:
            if isinstance(item, dict):
                for key in item:
                    if str(key).startswith("_"):
                        continue
                    key_help = FIELD_HELP.get(f"{path}[].{key}")
                    if key_help:
                        _emit_comment(out, f"{key}: {key_help}", indent + 2)
                break
        for item in items:
            if not isinstance(item, dict):  # pragma: no cover - mixed lists unused
                out.append(f"{entry_pad}- {_scalar(item)}")
                continue
            note = item.get("_comment")
            if note:
                _emit_comment(out, str(note), indent + 2)
            first = True
            for key, value in item.items():
                if str(key).startswith("_"):
                    continue
                lead = "- " if first else "  "
                first = False
                out.append(f"{entry_pad}{lead}{key}: {_scalar(value)}")
    else:
        for item in items:
            out.append(f"{entry_pad}- {_scalar(item)}")


def dump(document: dict[str, Any]) -> str:
    """Serialise a configuration document to YAML text, with field comments."""
    out: list[str] = []
    for key, value in document.items():
        section = SECTION_HELP.get(str(key))
        if section:
            if out:
                out.append("")
            banner = f"{key} " + "─" * max(1, 66 - len(str(key)))
            out.append("# " + banner)
            _emit_comment(out, section, 0)
        if isinstance(value, dict) and value:
            out.append(f"{key}:")
            _render(value, str(key), out, 2)
        elif isinstance(value, list) and value:
            out.append(f"{key}:")
            _render_list(value, str(key), out, 0)
        else:
            out.append(f"{key}: {_scalar(value)}")
    return _HEADER + "\n" + "\n".join(out).rstrip() + "\n"


# ── loading ─────────────────────────────────────────────────────────────────

def load(text: str) -> dict[str, Any]:
    """Parse YAML text into a configuration document (comments are ignored)."""
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as exc:  # pragma: no cover - bubbled to the CLI
        raise YamlError(f"invalid YAML: {exc}") from exc
    if doc is None:
        raise YamlError("empty document")
    if not isinstance(doc, dict):
        raise YamlError(f"top level must be a mapping, got {type(doc).__name__}")
    version = doc.get("meta", {}).get("doc_version", DOC_VERSION)
    if isinstance(version, int) and version > DOC_VERSION:
        raise YamlError(
            f"document version {version} is newer than this tool ({DOC_VERSION})"
        )
    return doc


def load_file(path) -> dict[str, Any]:
    with open(path, "r", encoding="utf-8") as fh:
        return load(fh.read())


def dump_file(path, document: dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(dump(document))


def to_hex(data: bytes) -> str:
    return data.hex()


def from_hex(text: str) -> bytes:
    try:
        return bytes.fromhex(str(text).strip().replace(" ", ""))
    except (ValueError, AttributeError) as exc:
        raise YamlError(f"not a hex string: {text!r}") from exc
