#!/usr/bin/env python3
"""Validate immutable benchmark fixtures and create strict APFS CoW clones."""

from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import json
import math
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Iterator, Sequence


DIGEST_VERSION = b"metallum-content-tree-v1\0"
SOURCE_DIGEST_VERSION = b"metallum-source-tree-v1\0"
ARTIFACT_DIGEST_VERSION = b"metallum-build-artifact-set-v1\0"
SETTINGS_DIGEST_VERSION = b"metallum-benchmark-settings-v1\0"
RESOURCE_PACK_DIGEST_VERSION = b"metallum-resource-pack-set-v1\0"
SODIUM_SETTINGS_DIGEST_VERSION = b"metallum-sodium-settings-v1\0"
SESSION_LOCK = Path("session.lock")
OWNER_MARKER = Path(".metallum-benchmark-owner")
CLONE_NOFOLLOW = 0x0001
SHA256_RE = re.compile(r"[0-9a-f]{64}")
SAFE_ID_RE = re.compile(r"[a-z0-9][a-z0-9._-]*")
PLAYER_RE = re.compile(r"[A-Za-z0-9_]{3,16}")
DIMENSION_RE = re.compile(r"[a-z0-9_.-]+:[a-z0-9/._-]+")
BLOCK_ID_RE = re.compile(r"[a-z0-9_.-]+:[a-z0-9/._-]+")
OPTION_BARE_RE = re.compile(r"[A-Za-z0-9_.+/-]+")
PROPERTY_KEY_RE = re.compile(r"[A-Za-z][A-Za-z0-9._-]*")


class FixtureError(ValueError):
    """The fixture or route cannot satisfy the deterministic-run contract."""


def _frame(hasher: "hashlib._Hash", value: bytes) -> None:
    hasher.update(struct.pack(">Q", len(value)))
    hasher.update(value)


def _sorted_children(path: Path) -> list[Path]:
    try:
        return sorted(path.iterdir(), key=lambda child: os.fsencode(child.name))
    except OSError as error:
        raise FixtureError(f"cannot list {path}: {error}") from error


def _entries(root: Path, *, allow_owner: bool) -> Iterator[tuple[Path, Path, os.stat_result]]:
    try:
        root_stat = root.lstat()
    except OSError as error:
        raise FixtureError(f"cannot inspect {root}: {error}") from error
    if not stat.S_ISDIR(root_stat.st_mode):
        raise FixtureError(f"fixture root is not a directory: {root}")
    root_device = root_stat.st_dev

    def walk(directory: Path, relative: Path) -> Iterator[tuple[Path, Path, os.stat_result]]:
        for child in _sorted_children(directory):
            child_relative = relative / child.name
            if child_relative == SESSION_LOCK:
                continue
            if child_relative == OWNER_MARKER:
                if allow_owner:
                    continue
                raise FixtureError(f"fixture contains reserved marker {OWNER_MARKER}")
            try:
                child_stat = child.lstat()
            except OSError as error:
                raise FixtureError(f"cannot inspect {child}: {error}") from error
            if child_stat.st_dev != root_device:
                raise FixtureError(f"fixture crosses filesystem boundary at {child_relative}")
            if stat.S_ISLNK(child_stat.st_mode):
                raise FixtureError(f"fixture contains symlink {child_relative}")
            if stat.S_ISREG(child_stat.st_mode):
                if child_stat.st_nlink != 1:
                    raise FixtureError(f"fixture contains hard-linked file {child_relative}")
            elif not stat.S_ISDIR(child_stat.st_mode):
                raise FixtureError(f"fixture contains special file {child_relative}")
            yield child_relative, child, child_stat
            if stat.S_ISDIR(child_stat.st_mode):
                yield from walk(child, child_relative)

    yield from walk(root, Path())


def tree_digest(root: Path, *, allow_owner: bool = False) -> str:
    hasher = hashlib.sha256(DIGEST_VERSION)
    for relative, path, entry_stat in _entries(root, allow_owner=allow_owner):
        relative_bytes = os.fsencode(relative.as_posix())
        if stat.S_ISDIR(entry_stat.st_mode):
            hasher.update(b"D")
            _frame(hasher, relative_bytes)
            continue
        hasher.update(b"F")
        _frame(hasher, relative_bytes)
        hasher.update(struct.pack(">Q", entry_stat.st_size))
        try:
            with path.open("rb") as handle:
                while chunk := handle.read(1024 * 1024):
                    hasher.update(chunk)
        except OSError as error:
            raise FixtureError(f"cannot read {relative}: {error}") from error
    return hasher.hexdigest()


def source_digest(root: Path) -> str:
    """Hash every Git-visible working-tree input except the known external net/ artifact."""
    try:
        root = root.resolve(strict=True)
        result = subprocess.run(
            [
                "git", "-C", str(root), "ls-files", "-z", "--cached", "--others",
                "--exclude-standard", "--deduplicate",
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise FixtureError(f"cannot enumerate benchmark source tree: {error}") from error

    raw_paths = sorted(set(filter(None, result.stdout.split(b"\0"))))
    hasher = hashlib.sha256(SOURCE_DIGEST_VERSION)
    for raw_path in raw_paths:
        if raw_path == b"net" or raw_path.startswith(b"net/"):
            continue
        relative = Path(os.fsdecode(raw_path))
        if relative.is_absolute() or ".." in relative.parts:
            raise FixtureError(f"unsafe Git path in source tree: {relative}")
        path = root / relative
        try:
            entry_stat = path.lstat()
        except FileNotFoundError:
            hasher.update(b"X")
            _frame(hasher, raw_path)
            continue
        except OSError as error:
            raise FixtureError(f"cannot inspect source input {relative}: {error}") from error
        if stat.S_ISREG(entry_stat.st_mode):
            hasher.update(b"F")
            _frame(hasher, raw_path)
            hasher.update(b"1" if entry_stat.st_mode & 0o111 else b"0")
            hasher.update(struct.pack(">Q", entry_stat.st_size))
            try:
                with path.open("rb") as handle:
                    while chunk := handle.read(1024 * 1024):
                        hasher.update(chunk)
            except OSError as error:
                raise FixtureError(f"cannot read source input {relative}: {error}") from error
        elif stat.S_ISLNK(entry_stat.st_mode):
            hasher.update(b"L")
            _frame(hasher, raw_path)
            _frame(hasher, os.fsencode(os.readlink(path)))
        else:
            raise FixtureError(f"source input is not a regular file or symlink: {relative}")
    return hasher.hexdigest()


def artifact_digest(root: Path, relative_paths: Sequence[Path]) -> str:
    """Hash an explicitly named set of build outputs without path or timestamp noise."""
    try:
        root = root.resolve(strict=True)
        root_stat = root.lstat()
    except OSError as error:
        raise FixtureError(f"cannot inspect artifact root {root}: {error}") from error
    if not stat.S_ISDIR(root_stat.st_mode):
        raise FixtureError(f"artifact root is not a directory: {root}")
    if not relative_paths:
        raise FixtureError("artifact digest requires at least one relative path")

    normalized: list[Path] = []
    for relative in relative_paths:
        if relative.is_absolute() or relative == Path() or ".." in relative.parts:
            raise FixtureError(f"unsafe artifact path: {relative}")
        canonical = Path(*relative.parts)
        if canonical.as_posix() != relative.as_posix():
            raise FixtureError(f"artifact path is not normalized: {relative}")
        normalized.append(canonical)

    if len(set(normalized)) != len(normalized):
        raise FixtureError("artifact digest paths must be unique")
    normalized.sort(key=lambda value: os.fsencode(value.as_posix()))
    for index, relative in enumerate(normalized):
        for other in normalized[index + 1:]:
            if relative in other.parents:
                raise FixtureError(
                    f"artifact digest paths must not overlap: {relative} and {other}"
                )

    hasher = hashlib.sha256(ARTIFACT_DIGEST_VERSION)

    def add(path: Path, relative: Path, device: int) -> None:
        try:
            entry_stat = path.lstat()
        except OSError as error:
            raise FixtureError(f"cannot inspect build artifact {relative}: {error}") from error
        if entry_stat.st_dev != device:
            raise FixtureError(f"build artifact crosses filesystem boundary at {relative}")
        relative_bytes = os.fsencode(relative.as_posix())
        if stat.S_ISLNK(entry_stat.st_mode):
            raise FixtureError(f"build artifact contains symlink {relative}")
        if stat.S_ISDIR(entry_stat.st_mode):
            hasher.update(b"D")
            _frame(hasher, relative_bytes)
            for child in _sorted_children(path):
                add(child, relative / child.name, device)
            return
        if not stat.S_ISREG(entry_stat.st_mode):
            raise FixtureError(f"build artifact contains special file {relative}")
        hasher.update(b"F")
        _frame(hasher, relative_bytes)
        hasher.update(b"1" if entry_stat.st_mode & 0o111 else b"0")
        hasher.update(struct.pack(">Q", entry_stat.st_size))
        try:
            with path.open("rb") as handle:
                while chunk := handle.read(1024 * 1024):
                    hasher.update(chunk)
        except OSError as error:
            raise FixtureError(f"cannot read build artifact {relative}: {error}") from error

    for relative in normalized:
        current = root
        for component in relative.parts[:-1]:
            current /= component
            try:
                component_stat = current.lstat()
            except OSError as error:
                raise FixtureError(
                    f"cannot inspect artifact path component {current.relative_to(root)}: {error}"
                ) from error
            if stat.S_ISLNK(component_stat.st_mode):
                raise FixtureError(
                    f"artifact path contains symlink {current.relative_to(root)}"
                )
            if not stat.S_ISDIR(component_stat.st_mode):
                raise FixtureError(
                    f"artifact path component is not a directory: {current.relative_to(root)}"
                )
        add(root / relative, relative, root_stat.st_dev)
    return hasher.hexdigest()


def _resource_pack_path_digest(path: Path) -> str:
    hasher = hashlib.sha256(b"metallum-resource-pack-content-v1\0")
    try:
        root_stat = path.lstat()
    except OSError as error:
        raise FixtureError(f"cannot inspect resource pack {path}: {error}") from error
    if stat.S_ISLNK(root_stat.st_mode):
        raise FixtureError(f"resource pack must not be a symlink: {path}")

    def add_file(relative: Path, file_path: Path, file_stat: os.stat_result) -> None:
        if not stat.S_ISREG(file_stat.st_mode):
            raise FixtureError(f"resource pack contains a special file: {file_path}")
        if file_stat.st_nlink != 1:
            raise FixtureError(f"resource pack contains a hard-linked file: {file_path}")
        hasher.update(b"F")
        _frame(hasher, os.fsencode(relative.as_posix()))
        hasher.update(struct.pack(">Q", file_stat.st_size))
        try:
            with file_path.open("rb") as handle:
                while chunk := handle.read(1024 * 1024):
                    hasher.update(chunk)
        except OSError as error:
            raise FixtureError(f"cannot read resource pack file {file_path}: {error}") from error

    if stat.S_ISREG(root_stat.st_mode):
        add_file(Path(path.name), path, root_stat)
        return hasher.hexdigest()
    if not stat.S_ISDIR(root_stat.st_mode):
        raise FixtureError(f"resource pack is not a file or directory: {path}")
    root_device = root_stat.st_dev

    def walk(directory: Path, relative: Path) -> None:
        for child in _sorted_children(directory):
            child_relative = relative / child.name
            try:
                child_stat = child.lstat()
            except OSError as error:
                raise FixtureError(f"cannot inspect resource pack entry {child}: {error}") from error
            if child_stat.st_dev != root_device:
                raise FixtureError(f"resource pack crosses a filesystem boundary: {child}")
            if stat.S_ISLNK(child_stat.st_mode):
                raise FixtureError(f"resource pack contains a symlink: {child}")
            if stat.S_ISDIR(child_stat.st_mode):
                hasher.update(b"D")
                _frame(hasher, os.fsencode(child_relative.as_posix()))
                walk(child, child_relative)
            else:
                add_file(child_relative, child, child_stat)

    walk(path, Path())
    return hasher.hexdigest()


def _resource_pack_set_digest(
    resourcepacks_root: Path,
    enabled: object,
    incompatible: object,
) -> str:
    if not isinstance(enabled, list) or not isinstance(incompatible, list):
        raise FixtureError("resourcePacks and incompatibleResourcePacks must be arrays")
    try:
        root = resourcepacks_root.resolve(strict=True)
    except OSError as error:
        raise FixtureError(f"cannot resolve resource-pack directory {resourcepacks_root}: {error}") from error
    if not root.is_dir():
        raise FixtureError(f"resource-pack path is not a directory: {root}")

    content: dict[str, dict[str, str]] = {}
    for group_name, values in (("enabled", enabled), ("incompatible", incompatible)):
        if any(
            not isinstance(value, str) or not value or "\n" in value or "\t" in value
            for value in values
        ):
            raise FixtureError("resource-pack identifiers must be non-empty strings")
        if len(set(values)) != len(values):
            raise FixtureError(f"duplicate resource-pack identifier in {group_name} list")
    for value in dict.fromkeys([*enabled, *incompatible]):
        if not value.startswith("file/"):
            content[value] = {"kind": "builtin", "sha256": "builtin"}
            continue
        name = value[len("file/"):]
        relative = Path(name)
        if not name or relative.is_absolute() or relative.name != name or name in {".", ".."}:
            raise FixtureError(f"unsafe file resource-pack identifier: {value}")
        path = root / name
        try:
            resolved_parent = path.parent.resolve(strict=True)
        except OSError as error:
            raise FixtureError(f"cannot resolve resource pack {value}: {error}") from error
        if resolved_parent != root or not path.exists():
            raise FixtureError(f"file resource pack does not exist under {root}: {value}")
        content[value] = {"kind": "file", "sha256": _resource_pack_path_digest(path)}

    hasher = hashlib.sha256(RESOURCE_PACK_DIGEST_VERSION)
    hasher.update(_canonical_json({
        "enabled": enabled,
        "incompatible": incompatible,
        "content": content,
    }))
    return hasher.hexdigest()


def _make_tree_writable(root: Path) -> None:
    if not root.exists():
        return
    for _relative, path, entry_stat in _entries(root, allow_owner=True):
        try:
            path.chmod(stat.S_IMODE(entry_stat.st_mode) | stat.S_IWUSR)
        except OSError:
            pass
    try:
        root.chmod(stat.S_IMODE(root.lstat().st_mode) | stat.S_IWUSR)
    except OSError:
        pass


def _make_tree_read_only(root: Path) -> None:
    entries = list(_entries(root, allow_owner=False))
    for _relative, path, entry_stat in reversed(entries):
        path.chmod(stat.S_IMODE(entry_stat.st_mode) & ~0o222)
    root.chmod(stat.S_IMODE(root.lstat().st_mode) & ~0o222)


def verify_read_only(root: Path) -> None:
    root_stat = root.lstat()
    if root_stat.st_mode & 0o222 or os.access(root, os.W_OK):
        raise FixtureError(f"fixture root is writable: {root}")
    for relative, path, entry_stat in _entries(root, allow_owner=False):
        if entry_stat.st_mode & 0o222 or os.access(path, os.W_OK):
            raise FixtureError(f"fixture entry is writable: {relative}")


def _clonefile(source: Path, destination: Path) -> None:
    if sys.platform != "darwin":
        raise FixtureError("strict benchmark cloning requires macOS clonefile(2)")
    libc = ctypes.CDLL(None, use_errno=True)
    clonefile = libc.clonefile
    clonefile.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint32]
    clonefile.restype = ctypes.c_int
    if clonefile(os.fsencode(source), os.fsencode(destination), CLONE_NOFOLLOW) != 0:
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number), str(source))
    source_stat = source.lstat()
    destination_stat = destination.lstat()
    if destination_stat.st_nlink != 1:
        raise FixtureError(f"clone unexpectedly has multiple hard links: {destination}")
    if (source_stat.st_dev, source_stat.st_ino) == (
        destination_stat.st_dev,
        destination_stat.st_ino,
    ):
        raise FixtureError(f"clone reused the source inode: {destination}")


def clone_tree(source: Path, destination: Path, *, destination_has_owner: bool) -> None:
    source_stat = source.lstat()
    destination_stat = destination.lstat()
    if not stat.S_ISDIR(destination_stat.st_mode):
        raise FixtureError(f"clone destination is not a directory: {destination}")
    if source_stat.st_dev != destination_stat.st_dev:
        raise FixtureError("fixture and temporary world are on different filesystems")
    allowed = {OWNER_MARKER.name} if destination_has_owner else set()
    unexpected = [child.name for child in destination.iterdir() if child.name not in allowed]
    if unexpected:
        raise FixtureError(f"clone destination is not empty: {unexpected[0]}")

    for relative, path, entry_stat in _entries(source, allow_owner=False):
        target = destination / relative
        if stat.S_ISDIR(entry_stat.st_mode):
            # Fixture directories are deliberately read-only.  Keep clone
            # parents writable while the tree is being materialized; the
            # caller applies the final fixture/run permissions afterwards.
            target.mkdir(mode=0o700)
        else:
            try:
                _clonefile(path, target)
            except OSError as error:
                if error.errno in (errno.ENOTSUP, errno.EXDEV):
                    raise FixtureError(
                        f"APFS CoW clone is unavailable for {relative}: {error}"
                    ) from error
                raise FixtureError(f"clonefile failed for {relative}: {error}") from error


def verify_fixture(root: Path, expected: str) -> str:
    if not SHA256_RE.fullmatch(expected):
        raise FixtureError("expected fixture digest is not lowercase SHA-256")
    verify_read_only(root)
    actual = tree_digest(root)
    if actual != expected:
        raise FixtureError(f"fixture digest mismatch: expected {expected}, found {actual}")
    return actual


def prepare_fixture(source: Path, destination: Path) -> str:
    if destination.exists():
        raise FixtureError(f"fixture already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    source_before = tree_digest(source)
    staging = destination.parent / f".{destination.name}.staging-{uuid.uuid4()}"
    staging.mkdir(mode=0o700)
    try:
        clone_tree(source, staging, destination_has_owner=False)
        staged_digest = tree_digest(staging)
        source_after = tree_digest(source)
        if source_after != source_before:
            raise FixtureError("source world changed while creating the fixture")
        if staged_digest != source_before:
            raise FixtureError("fixture clone does not match the source world")
        _make_tree_read_only(staging)
        verify_read_only(staging)
        staging.rename(destination)
        return staged_digest
    except Exception:
        _make_tree_writable(staging)
        shutil.rmtree(staging, ignore_errors=True)
        raise


def clone_run(source: Path, destination: Path, expected: str) -> str:
    fixture_before = verify_fixture(source, expected)
    clone_tree(source, destination, destination_has_owner=True)
    clone_digest = tree_digest(destination, allow_owner=True)
    if clone_digest != fixture_before:
        raise FixtureError("temporary world does not match the immutable fixture")
    fixture_after = verify_fixture(source, expected)
    if fixture_after != fixture_before:
        raise FixtureError("fixture changed while creating the temporary world")
    return clone_digest


def _object(value: object, field: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise FixtureError(f"route {field} must be an object")
    return value


def _exact_keys(value: dict[str, object], field: str, expected: set[str]) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        details: list[str] = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if unexpected:
            details.append("unexpected " + ", ".join(unexpected))
        raise FixtureError(f"route {field} fields are invalid ({'; '.join(details)})")


def _strict_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise FixtureError(f"JSON contains duplicate field {key}")
        result[key] = value
    return result


def _canonical_json(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise FixtureError(f"value cannot be represented as strict JSON: {error}") from error


def _load_strict_json(path: Path, description: str) -> tuple[bytes, object]:
    try:
        raw = path.read_bytes()
        payload = json.loads(
            raw,
            object_pairs_hook=_strict_json_object,
            parse_constant=lambda value: (_ for _ in ()).throw(
                FixtureError(f"{description} contains non-finite JSON value {value}")
            ),
        )
    except (OSError, json.JSONDecodeError) as error:
        raise FixtureError(f"cannot read {description} {path}: {error}") from error
    return raw, payload


def _settings_exact_keys(
    value: dict[str, object],
    field: str,
    expected: set[str],
) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        details: list[str] = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if unexpected:
            details.append("unexpected " + ", ".join(unexpected))
        raise FixtureError(
            f"benchmark settings {field} fields are invalid ({'; '.join(details)})"
        )


def _parse_options(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise FixtureError(f"cannot read Minecraft options {path}: {error}") from error
    result: dict[str, str] = {}
    for line_number, line in enumerate(lines, 1):
        if not line or ":" not in line:
            raise FixtureError(f"invalid Minecraft option at {path}:{line_number}")
        key, value = line.split(":", 1)
        if not key or key in result:
            detail = "empty key" if not key else f"duplicate key {key}"
            raise FixtureError(f"{detail} in Minecraft options at {path}:{line_number}")
        result[key] = value
    return result


def _parse_properties(path: Path, description: str) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise FixtureError(f"cannot read {description} {path}: {error}") from error
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise FixtureError(f"invalid {description} entry at {path}:{line_number}")
        key, value = (part.strip() for part in line.split("=", 1))
        if not PROPERTY_KEY_RE.fullmatch(key) or "\\" in value \
                or any(ord(character) < 0x20 or ord(character) > 0x7E for character in value):
            raise FixtureError(
                f"unsupported {description} syntax at {path}:{line_number}"
            )
        if key in result:
            detail = "empty key" if not key else f"duplicate key {key}"
            raise FixtureError(f"{detail} in {description} at {path}:{line_number}")
        result[key] = value
    return result


def _option_json(raw: str, key: str, path: Path) -> object:
    try:
        return json.loads(
            raw,
            parse_constant=lambda value: (_ for _ in ()).throw(
                FixtureError(f"Minecraft option {key} is non-finite: {value}")
            ),
        )
    except json.JSONDecodeError as error:
        if OPTION_BARE_RE.fullmatch(raw):
            return raw
        raise FixtureError(
            f"Minecraft option {key} in {path} is neither JSON nor a safe bare value: "
            f"{error.msg}"
        ) from error


def _setting_output(value: object, field: str) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        result = value
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        result = _canonical_json(value).decode("utf-8")
    else:
        raise FixtureError(f"benchmark setting {field} must be a scalar")
    if not result or "\t" in result or "\n" in result:
        raise FixtureError(f"benchmark setting {field} is not safe for launcher output")
    return result


def settings_values(
    spec_path: Path,
    options_path: Path,
    hdr_path: Path,
    metalfx_path: Path,
    sodium_options_path: Path,
    sodium_mixins_path: Path,
    resourcepacks_root: Path,
    fabric_default_packs_path: Path,
) -> list[str]:
    spec_raw, payload = _load_strict_json(spec_path, "benchmark settings")
    spec = _object(payload, "benchmark settings root")
    _settings_exact_keys(
        spec,
        "root",
        {
            "schema_version", "id", "options", "hdr_properties",
            "metalfx_properties", "sodium_options", "sodium_mixin_properties",
            "fabric_default_resource_packs", "runtime",
        },
    )
    if _integer(spec.get("schema_version"), "settings.schema_version", 1) != 1:
        raise FixtureError("unsupported benchmark settings schema_version")
    settings_id = _string(spec.get("id"), "settings.id", SAFE_ID_RE)
    expected_options = _object(spec.get("options"), "settings.options")
    expected_hdr = _object(spec.get("hdr_properties"), "settings.hdr_properties")
    expected_metalfx = _object(
        spec.get("metalfx_properties"), "settings.metalfx_properties"
    )
    expected_sodium = _object(spec.get("sodium_options"), "settings.sodium_options")
    expected_sodium_mixins = _object(
        spec.get("sodium_mixin_properties"), "settings.sodium_mixin_properties"
    )
    expected_fabric_packs = _object(
        spec.get("fabric_default_resource_packs"),
        "settings.fabric_default_resource_packs",
    )
    expected_runtime = _object(spec.get("runtime"), "settings.runtime")
    if not expected_options or not expected_hdr or not expected_metalfx \
            or not expected_sodium:
        raise FixtureError("required benchmark settings groups must not be empty")
    _settings_exact_keys(
        expected_sodium,
        "sodium_options",
        {"quality", "performance", "advanced", "debug"},
    )
    _settings_exact_keys(
        expected_fabric_packs,
        "fabric_default_resource_packs",
        {"values"},
    )
    _settings_exact_keys(
        expected_runtime,
        "runtime",
        {"active_resource_pack_ids", "sodium_chunk_builder_threads"},
    )
    active_resource_pack_ids = expected_runtime.get("active_resource_pack_ids")
    if not isinstance(active_resource_pack_ids, list) or not active_resource_pack_ids:
        raise FixtureError("benchmark runtime active_resource_pack_ids must be a non-empty array")
    for pack_id in active_resource_pack_ids:
        if not isinstance(pack_id, str) or not pack_id \
                or any(character in pack_id for character in (",", "\t", "\n")):
            raise FixtureError("benchmark runtime resource-pack IDs must be safe strings")
    if len(set(active_resource_pack_ids)) != len(active_resource_pack_ids):
        raise FixtureError("benchmark runtime resource-pack IDs must be unique")
    sodium_worker_threads = _integer(
        expected_runtime.get("sodium_chunk_builder_threads"),
        "settings.runtime.sodium_chunk_builder_threads",
        1,
    )

    raw_options = _parse_options(options_path)
    actual_options: dict[str, object] = {}
    for key, expected in expected_options.items():
        if key not in raw_options:
            raise FixtureError(f"Minecraft option {key} is missing from {options_path}")
        actual = _option_json(raw_options[key], key, options_path)
        if _canonical_json(actual) != _canonical_json(expected):
            raise FixtureError(
                f"Minecraft option {key} differs from {settings_id}: "
                f"expected {_canonical_json(expected).decode('utf-8')}, "
                f"found {_canonical_json(actual).decode('utf-8')}"
            )
        actual_options[key] = actual

    actual_hdr_all = _parse_properties(hdr_path, "HDR properties")
    actual_metalfx_all = _parse_properties(metalfx_path, "MetalFX properties")

    def selected_properties(
        expected: dict[str, object],
        actual: dict[str, str],
        description: str,
    ) -> dict[str, str]:
        selected: dict[str, str] = {}
        if set(actual) != set(expected):
            missing = sorted(set(expected) - set(actual))
            unexpected = sorted(set(actual) - set(expected))
            details = []
            if missing:
                details.append("missing " + ", ".join(missing))
            if unexpected:
                details.append("unexpected " + ", ".join(unexpected))
            raise FixtureError(
                f"{description} property keys differ from {settings_id} "
                f"({'; '.join(details)})"
            )
        for key, expected_value in expected.items():
            if not isinstance(expected_value, str):
                raise FixtureError(f"benchmark settings {description}.{key} must be a string")
            if key not in actual:
                raise FixtureError(f"{description} property {key} is missing")
            if actual[key] != expected_value:
                raise FixtureError(
                    f"{description} property {key} differs from {settings_id}: "
                    f"expected {expected_value}, found {actual[key]}"
                )
            selected[key] = actual[key]
        return selected

    actual_hdr = selected_properties(expected_hdr, actual_hdr_all, "HDR")
    actual_metalfx = selected_properties(expected_metalfx, actual_metalfx_all, "MetalFX")
    _sodium_raw, sodium_payload = _load_strict_json(
        sodium_options_path, "Sodium options"
    )
    sodium_root = _object(sodium_payload, "Sodium options root")
    missing_sodium_groups = set(expected_sodium) - set(sodium_root)
    unexpected_sodium_groups = set(sodium_root) - set(expected_sodium) - {"notifications"}
    if missing_sodium_groups or unexpected_sodium_groups:
        details: list[str] = []
        if missing_sodium_groups:
            details.append("missing " + ", ".join(sorted(missing_sodium_groups)))
        if unexpected_sodium_groups:
            details.append("unexpected " + ", ".join(sorted(unexpected_sodium_groups)))
        raise FixtureError(
            "Sodium top-level option groups are not covered by the benchmark "
            f"settings contract ({'; '.join(details)})"
        )
    actual_sodium: dict[str, object] = {}
    for group, expected_group in expected_sodium.items():
        actual_group = sodium_root.get(group)
        if _canonical_json(actual_group) != _canonical_json(expected_group):
            raise FixtureError(
                f"Sodium option group {group} differs from {settings_id}"
            )
        actual_sodium[group] = actual_group

    actual_sodium_mixins = _parse_properties(
        sodium_mixins_path, "Sodium mixin properties"
    )
    for key, expected_value in expected_sodium_mixins.items():
        if not isinstance(expected_value, str):
            raise FixtureError(
                f"benchmark settings sodium_mixin_properties.{key} must be a string"
            )
    if _canonical_json(actual_sodium_mixins) != _canonical_json(expected_sodium_mixins):
        raise FixtureError(f"Sodium mixin overrides differ from {settings_id}")

    _fabric_raw, actual_fabric_packs = _load_strict_json(
        fabric_default_packs_path, "Fabric default resource packs"
    )
    if _canonical_json(actual_fabric_packs) != _canonical_json(expected_fabric_packs):
        raise FixtureError(f"Fabric default resource packs differ from {settings_id}")

    external_resource_packs_sha256 = _resource_pack_set_digest(
        resourcepacks_root,
        actual_options["resourcePacks"],
        actual_options["incompatibleResourcePacks"],
    )
    sodium_hasher = hashlib.sha256(SODIUM_SETTINGS_DIGEST_VERSION)
    sodium_hasher.update(_canonical_json({
        "options": actual_sodium,
        "mixin_properties": actual_sodium_mixins,
    }))
    resource_hasher = hashlib.sha256(RESOURCE_PACK_DIGEST_VERSION)
    resource_hasher.update(_canonical_json({
        "external_profile_sha256": external_resource_packs_sha256,
        "fabric_default": actual_fabric_packs,
        "active_resource_pack_ids": active_resource_pack_ids,
    }))
    resource_packs_sha256 = resource_hasher.hexdigest()
    canonical = _canonical_json({
        "schema_version": 1,
        "id": settings_id,
        "options": actual_options,
        "hdr_properties": actual_hdr,
        "metalfx_properties": actual_metalfx,
        "sodium_options": actual_sodium,
        "sodium_mixin_properties": actual_sodium_mixins,
        "fabric_default_resource_packs": actual_fabric_packs,
        "runtime": expected_runtime,
        "resource_packs_sha256": resource_packs_sha256,
    })
    settings_hasher = hashlib.sha256(SETTINGS_DIGEST_VERSION)
    settings_hasher.update(canonical)

    option_fields = (
        "renderDistance", "simulationDistance", "graphicsPreset",
        "entityDistanceScaling", "particles", "mipmapLevels",
        "biomeBlendRadius", "maxFps", "ao", "renderClouds", "cloudRange",
        "textureFiltering", "maxAnisotropyBit", "improvedTransparency", "guiScale",
    )
    return [
        settings_id,
        hashlib.sha256(spec_raw).hexdigest(),
        settings_hasher.hexdigest(),
        *(_setting_output(actual_options[key], f"options.{key}") for key in option_fields),
        resource_packs_sha256,
        sodium_hasher.hexdigest(),
        ",".join(active_resource_pack_ids),
        str(sodium_worker_threads),
        _setting_output(actual_hdr["mode"], "hdr.mode"),
        _setting_output(actual_hdr["sourceEncoding"], "hdr.sourceEncoding"),
        _setting_output(actual_hdr["bloomStrength"], "hdr.bloomStrength"),
        _setting_output(actual_hdr["hdrStrength"], "hdr.hdrStrength"),
        _setting_output(actual_metalfx["mode"], "metalfx.mode"),
    ]


def _offline_player_uuid(name: str) -> str:
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def _string(value: object, field: str, pattern: re.Pattern[str] | None = None) -> str:
    if not isinstance(value, str) or not value or "\t" in value or "\n" in value:
        raise FixtureError(f"route {field} must be a non-empty single-line string")
    if pattern is not None and not pattern.fullmatch(value):
        raise FixtureError(f"route {field} has an invalid value")
    return value


def _number(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise FixtureError(f"route {field} must be a number")
    result = float(value)
    if not math.isfinite(result):
        raise FixtureError(f"route {field} must be finite")
    return result


def _integer(value: object, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise FixtureError(f"route {field} must be an integer >= {minimum}")
    return value


def _signed_integer(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise FixtureError(f"route {field} must be an integer")
    return value


def route_values(path: Path) -> list[str]:
    try:
        raw = path.read_bytes()
        payload = json.loads(raw, object_pairs_hook=_strict_json_object)
    except (OSError, json.JSONDecodeError) as error:
        raise FixtureError(f"cannot read route {path}: {error}") from error
    route = _object(payload, "root")
    schema_version = _integer(route.get("schema_version"), "schema_version", 1)
    if schema_version not in (1, 2):
        raise FixtureError("unsupported route schema_version")
    root_keys = {
        "schema_version", "id", "fixture", "player", "dimension",
        "position", "rotation", "camera", "clock", "weather", "simulation",
        "readiness",
    }
    if schema_version == 2:
        root_keys.add("torch_epoch")
    _exact_keys(route, "root", root_keys)
    route_id = _string(route.get("id"), "id", SAFE_ID_RE)
    fixture = _object(route.get("fixture"), "fixture")
    _exact_keys(fixture, "fixture", {"id", "sha256"})
    fixture_id = _string(fixture.get("id"), "fixture.id", SAFE_ID_RE)
    fixture_sha256 = _string(fixture.get("sha256"), "fixture.sha256", SHA256_RE)
    player = _object(route.get("player"), "player")
    _exact_keys(player, "player", {"name", "uuid"})
    player_name = _string(player.get("name"), "player.name", PLAYER_RE)
    player_uuid = _string(player.get("uuid"), "player.uuid")
    try:
        player_uuid = str(uuid.UUID(player_uuid))
    except ValueError as error:
        raise FixtureError("route player.uuid is invalid") from error
    expected_player_uuid = _offline_player_uuid(player_name)
    if player_uuid != expected_player_uuid:
        raise FixtureError(
            f"route player.uuid must be offline UUID {expected_player_uuid} for {player_name}"
        )
    dimension = _string(route.get("dimension"), "dimension", DIMENSION_RE)

    position = route.get("position")
    if not isinstance(position, list) or len(position) != 3:
        raise FixtureError("route position must contain exactly three numbers")
    x, y, z = (_number(value, f"position[{index}]") for index, value in enumerate(position))
    rotation = _object(route.get("rotation"), "rotation")
    _exact_keys(rotation, "rotation", {"yaw", "pitch"})
    yaw = _number(rotation.get("yaw"), "rotation.yaw")
    pitch = _number(rotation.get("pitch"), "rotation.pitch")
    if not -90.0 <= pitch <= 90.0:
        raise FixtureError("route rotation.pitch must be between -90 and 90")
    if route.get("camera") != "FIRST_PERSON":
        raise FixtureError("static benchmark route requires FIRST_PERSON camera")

    clock = _object(route.get("clock"), "clock")
    _exact_keys(clock, "clock", {"total_ticks", "paused"})
    total_ticks = _integer(clock.get("total_ticks"), "clock.total_ticks")
    if clock.get("paused") is not True:
        raise FixtureError("static benchmark route requires a paused clock")
    weather = _object(route.get("weather"), "weather")
    _exact_keys(weather, "weather", {"mode", "frozen", "clear_duration_ticks"})
    if weather.get("mode") != "clear" or weather.get("frozen") is not True:
        raise FixtureError("static benchmark route requires clear frozen weather")
    clear_weather_ticks = _integer(
        weather.get("clear_duration_ticks"),
        "weather.clear_duration_ticks",
        1,
    )
    simulation = _object(route.get("simulation"), "simulation")
    _exact_keys(simulation, "simulation", {"frozen"})
    if simulation.get("frozen") is not True:
        raise FixtureError("static benchmark route requires frozen simulation ticks")

    readiness = _object(route.get("readiness"), "readiness")
    _exact_keys(
        readiness,
        "readiness",
        {
            "stable_frames", "timeout_frames", "position_epsilon", "angle_epsilon",
        },
    )
    stable_frames = _integer(readiness.get("stable_frames"), "readiness.stable_frames", 1)
    timeout_frames = _integer(readiness.get("timeout_frames"), "readiness.timeout_frames", 1)
    if timeout_frames <= stable_frames:
        raise FixtureError("route readiness.timeout_frames must exceed stable_frames")
    position_epsilon = _number(readiness.get("position_epsilon"), "readiness.position_epsilon")
    angle_epsilon = _number(readiness.get("angle_epsilon"), "readiness.angle_epsilon")
    if position_epsilon <= 0.0 or angle_epsilon <= 0.0:
        raise FixtureError("route readiness epsilons must be > 0")

    values = [
        route_id,
        hashlib.sha256(raw).hexdigest(),
        fixture_id,
        fixture_sha256,
        player_name,
        player_uuid,
        dimension,
        repr(x),
        repr(y),
        repr(z),
        repr(yaw),
        repr(pitch),
        str(total_ticks),
        str(clear_weather_ticks),
        "1",
        str(stable_frames),
        str(timeout_frames),
        repr(position_epsilon),
        repr(angle_epsilon),
    ]
    if schema_version == 1:
        return values

    torch_epoch = _object(route.get("torch_epoch"), "torch_epoch")
    _exact_keys(
        torch_epoch,
        "torch_epoch",
        {
            "position", "initial_block", "support_block",
            "apply_after_measured_frames", "observation_frames",
        },
    )
    torch_position = torch_epoch.get("position")
    if not isinstance(torch_position, list) or len(torch_position) != 3:
        raise FixtureError("route torch_epoch.position must contain exactly three integers")
    torch_x, torch_y, torch_z = (
        _signed_integer(value, f"torch_epoch.position[{index}]")
        for index, value in enumerate(torch_position)
    )
    if torch_x % 16 != 0 or torch_z % 16 != 0:
        raise FixtureError(
            "route torch_epoch.position x and z must lie on 16-block section boundaries"
        )
    initial_block = _string(
        torch_epoch.get("initial_block"),
        "torch_epoch.initial_block",
        BLOCK_ID_RE,
    )
    support_block = _string(
        torch_epoch.get("support_block"),
        "torch_epoch.support_block",
        BLOCK_ID_RE,
    )
    if initial_block != "minecraft:air":
        raise FixtureError("route torch_epoch.initial_block must be minecraft:air")
    if support_block != "minecraft:grass_block":
        raise FixtureError("route torch_epoch.support_block must be minecraft:grass_block")
    apply_after_measured_frames = _integer(
        torch_epoch.get("apply_after_measured_frames"),
        "torch_epoch.apply_after_measured_frames",
        1,
    )
    observation_frames = _integer(
        torch_epoch.get("observation_frames"),
        "torch_epoch.observation_frames",
        1,
    )
    if apply_after_measured_frames != 300 or observation_frames != 300:
        raise FixtureError(
            "route torch epoch must use a 300-frame baseline and 300-frame observation window"
        )
    return values + [
        "TORCH_EPOCH",
        str(torch_x),
        str(torch_y),
        str(torch_z),
        initial_block,
        support_block,
        str(apply_after_measured_frames),
        str(observation_frames),
    ]


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        source = root / "source"
        source.mkdir()
        (source / "region").mkdir()
        (source / "empty").mkdir()
        (source / "level.dat").write_bytes(b"level")
        (source / "region" / "r.0.0.mca").write_bytes(b"region-data")
        (source / "session.lock").write_bytes(b"ignored-one")
        first = tree_digest(source)
        (source / "session.lock").write_bytes(b"ignored-two")
        assert tree_digest(source) == first
        (source / "level.dat").write_bytes(b"changed")
        assert tree_digest(source) != first
        (source / "level.dat").write_bytes(b"level")

        artifact_root = root / "artifacts"
        artifact_classes = artifact_root / "build/classes/java/main"
        artifact_resources = artifact_root / "build/resources/main"
        artifact_native = artifact_root / "build/generated/metallum/natives/macos"
        artifact_classes.mkdir(parents=True)
        artifact_resources.mkdir(parents=True)
        artifact_native.mkdir(parents=True)
        (artifact_classes / "Example.class").write_bytes(b"class-data")
        (artifact_resources / "fabric.mod.json").write_bytes(b"{}")
        artifact_library = artifact_native / "libmetallum.dylib"
        artifact_library.write_bytes(b"native-data")
        artifact_paths = [
            Path("build/classes/java/main"),
            Path("build/resources/main"),
            Path("build/generated/metallum/natives/macos/libmetallum.dylib"),
        ]
        artifact_first = artifact_digest(artifact_root, artifact_paths)
        assert artifact_digest(artifact_root, list(reversed(artifact_paths))) == artifact_first
        artifact_library.write_bytes(b"changed-native-data")
        assert artifact_digest(artifact_root, artifact_paths) != artifact_first
        try:
            artifact_digest(artifact_root, [Path("build/missing")])
        except FixtureError as error:
            assert "cannot inspect build artifact" in str(error)
        else:
            raise AssertionError("missing build artifact was accepted")

        if sys.platform == "darwin":
            fixture = root / "fixture"
            assert prepare_fixture(source, fixture) == first
            verify_fixture(fixture, first)

            run = root / "run"
            run.mkdir()
            (run / OWNER_MARKER).write_text("test-token\n", encoding="utf-8")
            assert clone_run(fixture, run, first) == first
            assert tree_digest(run, allow_owner=True) == first
            assert (fixture / "level.dat").stat().st_ino != (run / "level.dat").stat().st_ino
            _make_tree_writable(fixture)
            _make_tree_writable(run)

        route = root / "route.json"
        route.write_text(json.dumps({
            "schema_version": 1,
            "id": "test-static-v1",
            "fixture": {"id": "test-v1", "sha256": first},
            "player": {
                "name": "MetallumBench",
                "uuid": "b07a402a-d8ea-354f-9398-aaf208a798b9",
            },
            "dimension": "minecraft:overworld",
            "position": [1.0, 64.0, -2.0],
            "rotation": {"yaw": 10.0, "pitch": 5.0},
            "camera": "FIRST_PERSON",
            "clock": {"total_ticks": 181406, "paused": True},
            "weather": {"mode": "clear", "frozen": True, "clear_duration_ticks": 6000},
            "simulation": {"frozen": True},
            "readiness": {
                "stable_frames": 120,
                "timeout_frames": 1200,
                "position_epsilon": 0.0001,
                "angle_epsilon": 0.001,
            },
        }), encoding="utf-8")
        values = route_values(route)
        assert values[0] == "test-static-v1" and values[2] == "test-v1"
        assert values[3] == first and values[14] == "1" and len(values) == 19

        torch_route_payload = json.loads(route.read_text(encoding="utf-8"))
        torch_route_payload["schema_version"] = 2
        torch_route_payload["id"] = "test-torch-v1"
        torch_route_payload["torch_epoch"] = {
            "position": [80, 75, -112],
            "initial_block": "minecraft:air",
            "support_block": "minecraft:grass_block",
            "apply_after_measured_frames": 300,
            "observation_frames": 300,
        }
        torch_route = root / "torch-route.json"
        torch_route.write_text(json.dumps(torch_route_payload), encoding="utf-8")
        torch_values = route_values(torch_route)
        assert torch_values[0] == "test-torch-v1" and len(torch_values) == 27
        assert torch_values[19:] == [
            "TORCH_EPOCH", "80", "75", "-112", "minecraft:air",
            "minecraft:grass_block", "300", "300",
        ]

        invalid_route = root / "invalid-route.json"

        def expect_route_error(payload: dict[str, object], expected: str) -> None:
            invalid_route.write_text(json.dumps(payload), encoding="utf-8")
            try:
                route_values(invalid_route)
            except FixtureError as error:
                assert expected in str(error)
            else:
                raise AssertionError(f"invalid route was accepted: {expected}")

        invalid_torch_route = json.loads(json.dumps(torch_route_payload))
        invalid_torch_route["torch_epoch"]["unexpected"] = True
        expect_route_error(invalid_torch_route, "torch_epoch fields are invalid")

        invalid_torch_route = json.loads(json.dumps(torch_route_payload))
        invalid_torch_route["torch_epoch"]["position"][0] = 81
        expect_route_error(invalid_torch_route, "16-block section boundaries")

        invalid_torch_route = json.loads(json.dumps(torch_route_payload))
        invalid_torch_route["torch_epoch"]["observation_frames"] = 600
        expect_route_error(invalid_torch_route, "300-frame baseline")

        options = root / "options.txt"
        options.write_text(
            "renderDistance:16\n"
            "simulationDistance:12\n"
            "graphicsPreset:\"fancy\"\n"
            "entityDistanceScaling:1.0\n"
            "particles:0\n"
            "mipmapLevels:4\n"
            "biomeBlendRadius:2\n"
            "maxFps:260\n"
            "ao:true\n"
            "renderClouds:\"true\"\n"
            "cloudRange:64\n"
            "textureFiltering:1\n"
            "maxAnisotropyBit:1\n"
            "improvedTransparency:false\n"
            "guiScale:0\n"
            "resourcePacks:[]\n"
            "incompatibleResourcePacks:[]\n",
            encoding="utf-8",
        )
        hdr = root / "hdr.properties"
        hdr.write_text(
            "# ignored timestamp\nmode=scene\nsourceEncoding=srgb\n"
            "bloomStrength=0.18\nhdrStrength=1.0\n",
            encoding="utf-8",
        )
        metalfx = root / "metalfx.properties"
        metalfx.write_text("mode=off\n", encoding="utf-8")
        sodium_options = root / "sodium-options.json"
        sodium_options.write_text(json.dumps({
            "quality": {"pixel_filtering_mode": "NEAREST"},
            "performance": {"chunk_builder_threads": 0},
            "advanced": {"enable_memory_tracing": False},
            "debug": {"terrain_sorting_enabled": True},
            "notifications": {"ignored": True},
        }), encoding="utf-8")
        sodium_mixins = root / "sodium-mixins.properties"
        sodium_mixins.write_text("# no overrides\n", encoding="utf-8")
        resourcepacks = root / "resourcepacks"
        resourcepacks.mkdir()
        fabric_default_packs = root / "fabric_default_resource_packs.json"
        fabric_default_packs.write_text('{"values":[]}\n', encoding="utf-8")
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "schema_version": 1,
            "id": "test-hdr-v1",
            "options": {
                "renderDistance": 16,
                "simulationDistance": 12,
                "graphicsPreset": "fancy",
                "entityDistanceScaling": 1.0,
                "particles": 0,
                "mipmapLevels": 4,
                "biomeBlendRadius": 2,
                "maxFps": 260,
                "ao": True,
                "renderClouds": "true",
                "cloudRange": 64,
                "textureFiltering": 1,
                "maxAnisotropyBit": 1,
                "improvedTransparency": False,
                "guiScale": 0,
                "resourcePacks": [],
                "incompatibleResourcePacks": [],
            },
            "hdr_properties": {
                "mode": "scene", "sourceEncoding": "srgb",
                "bloomStrength": "0.18", "hdrStrength": "1.0",
            },
            "metalfx_properties": {"mode": "off"},
            "sodium_options": {
                "quality": {"pixel_filtering_mode": "NEAREST"},
                "performance": {"chunk_builder_threads": 0},
                "advanced": {"enable_memory_tracing": False},
                "debug": {"terrain_sorting_enabled": True},
            },
            "sodium_mixin_properties": {},
            "fabric_default_resource_packs": {"values": []},
            "runtime": {
                "active_resource_pack_ids": ["vanilla", "metallum", "sodium"],
                "sodium_chunk_builder_threads": 4,
            },
        }), encoding="utf-8")
        settings_output = settings_values(
            settings, options, hdr, metalfx,
            sodium_options, sodium_mixins, resourcepacks, fabric_default_packs,
        )
        assert settings_output[0] == "test-hdr-v1" and len(settings_output) == 27
        changed = options.read_text(encoding="utf-8").replace(
            "renderDistance:16", "renderDistance:15"
        )
        options.write_text(changed, encoding="utf-8")
        try:
            settings_values(
                settings, options, hdr, metalfx,
                sodium_options, sodium_mixins, resourcepacks, fabric_default_packs,
            )
        except FixtureError as error:
            assert "renderDistance differs" in str(error)
        else:
            raise AssertionError("settings mismatch was accepted")
    print("metal benchmark fixture self-test passed")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="command", required=True)
    digest = sub.add_parser("digest")
    digest.add_argument("root", type=Path)
    digest.add_argument("--allow-owner", action="store_true")
    source = sub.add_parser("source-digest")
    source.add_argument("root", type=Path)
    artifact = sub.add_parser("artifact-digest")
    artifact.add_argument("root", type=Path)
    artifact.add_argument("relative_paths", nargs="+", type=Path)
    verify = sub.add_parser("verify-fixture")
    verify.add_argument("root", type=Path)
    verify.add_argument("expected")
    prepare = sub.add_parser("prepare")
    prepare.add_argument("source", type=Path)
    prepare.add_argument("destination", type=Path)
    clone = sub.add_parser("clone-run")
    clone.add_argument("source", type=Path)
    clone.add_argument("destination", type=Path)
    clone.add_argument("expected")
    route = sub.add_parser("route-values")
    route.add_argument("route", type=Path)
    settings = sub.add_parser("settings-values")
    settings.add_argument("spec", type=Path)
    settings.add_argument("options", type=Path)
    settings.add_argument("hdr", type=Path)
    settings.add_argument("metalfx", type=Path)
    settings.add_argument("sodium_options", type=Path)
    settings.add_argument("sodium_mixins", type=Path)
    settings.add_argument("resourcepacks", type=Path)
    settings.add_argument("fabric_default_packs", type=Path)
    sub.add_parser("self-test")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "digest":
            print(tree_digest(args.root, allow_owner=args.allow_owner))
        elif args.command == "source-digest":
            print(source_digest(args.root))
        elif args.command == "artifact-digest":
            print(artifact_digest(args.root, args.relative_paths))
        elif args.command == "verify-fixture":
            print(verify_fixture(args.root, args.expected))
        elif args.command == "prepare":
            print(prepare_fixture(args.source, args.destination))
        elif args.command == "clone-run":
            print(clone_run(args.source, args.destination, args.expected))
        elif args.command == "route-values":
            print("\t".join(route_values(args.route)))
        elif args.command == "settings-values":
            print("\t".join(settings_values(
                args.spec, args.options, args.hdr, args.metalfx,
                args.sodium_options, args.sodium_mixins, args.resourcepacks,
                args.fabric_default_packs,
            )))
        else:
            self_test()
    except (FixtureError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
