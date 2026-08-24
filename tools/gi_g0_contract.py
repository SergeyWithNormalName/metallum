#!/usr/bin/env python3
"""Verify the immutable Stage G0 fixture, route, and GI_OFF settings contract."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


REQUIRED_SCENARIOS = {
    "white-room-no-source", "red-emitter", "green-emitter",
    "skylight-aperture", "lava-landmark", "thin-geometry",
    "chunk-boundary", "cascade-boundary", "scroll", "teleport",
}
EXPECTED_ROUTE_DIMENSIONS = {
    "overworld": "minecraft:overworld",
    "sealed_cave": "minecraft:overworld",
    "nether": "minecraft:the_nether",
}
SETTINGS = (
    "benchmark/settings/native-hdr-fancy-v1.json",
    "benchmark/settings/native-sdr-fancy-v1.json",
    "benchmark/settings/nether-lava-stress-v1.json",
)


def _strict_object(path: Path) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ContractError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs)
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain a JSON object")
    return value


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise ContractError(f"cannot hash {path}: {error}") from error


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        missing = sorted(expected - set(value))
        extra = sorted(set(value) - expected)
        raise ContractError(f"{label} keys differ; missing={missing}, extra={extra}")


def verify(root: Path, manifest_path: Path) -> None:
    root = root.resolve()
    manifest_file = (root / manifest_path).resolve()
    manifest = _strict_object(manifest_file)
    _exact_keys(manifest, {
        "schema_version", "id", "coordinate_space", "authoring_contract",
        "baseline_world", "scenarios", "baseline_routes",
    }, "manifest")
    if manifest["schema_version"] != 1 or manifest["id"] != "gi-g0-fixtures-v1":
        raise ContractError("unsupported G0 fixture manifest identity")
    authoring = manifest["authoring_contract"]
    if not isinstance(authoring, dict):
        raise ContractError("authoring_contract must be an object")
    _exact_keys(authoring, {
        "kind", "deterministic", "random_commands_forbidden", "source_root",
    }, "authoring_contract")
    if authoring != {
        "kind": "tracked_mcfunction_sources",
        "deterministic": True,
        "random_commands_forbidden": True,
        "source_root": "benchmark/gi/fixtures/g0-v1",
    }:
        raise ContractError("G0 authoring contract is not deterministic and immutable")

    baseline_world = manifest["baseline_world"]
    if not isinstance(baseline_world, dict):
        raise ContractError("baseline_world must be an object")
    _exact_keys(baseline_world, {"fixture_id", "fixture_sha256"}, "baseline_world")
    fixture_sha = baseline_world["fixture_sha256"]
    if not isinstance(fixture_sha, str) or len(fixture_sha) != 64:
        raise ContractError("baseline fixture digest is not SHA-256")

    scenarios = manifest["scenarios"]
    if not isinstance(scenarios, list):
        raise ContractError("scenarios must be an array")
    ids = {scenario.get("id") for scenario in scenarios if isinstance(scenario, dict)}
    if ids != REQUIRED_SCENARIOS or len(scenarios) != len(REQUIRED_SCENARIOS):
        raise ContractError(f"G0 scenarios differ: {sorted(str(value) for value in ids)}")
    source_root = root / authoring["source_root"]
    for scenario in scenarios:
        if not isinstance(scenario, dict):
            raise ContractError("scenario must be an object")
        required = {"id", "dimension", "anchor", "source", "source_sha256", "invariant"}
        if scenario["id"] in {"scroll", "teleport"}:
            required.add("target")
        _exact_keys(scenario, required, f"scenario {scenario.get('id')}")
        source = source_root / scenario["source"]
        if source.parent.resolve() != source_root.resolve() or source.suffix != ".mcfunction":
            raise ContractError(f"unsafe fixture source path: {source}")
        if _sha256(source) != scenario["source_sha256"]:
            raise ContractError(f"fixture source digest mismatch: {source}")
        text = source.read_text(encoding="utf-8")
        if any(token in text.lower() for token in ("random", "spreadplayers", "summon", " loot ")):
            raise ContractError(f"non-deterministic fixture command in {source}")

    by_id = {scenario["id"]: scenario for scenario in scenarios}
    if by_id["chunk-boundary"]["anchor"][0] % 16 != 0:
        raise ContractError("chunk boundary anchor is not on a 16-block boundary")
    if by_id["cascade-boundary"]["anchor"][0] % 64 != 0:
        raise ContractError("cascade boundary anchor is not on the G0 test lattice")
    scroll = by_id["scroll"]
    if abs(scroll["target"][0] - scroll["anchor"][0]) != 64:
        raise ContractError("scroll fixture is not one test interval")
    teleport = by_id["teleport"]
    if abs(teleport["target"][0] - teleport["anchor"][0]) <= 256:
        raise ContractError("teleport target remains inside proposed outer coverage")

    routes = manifest["baseline_routes"]
    if not isinstance(routes, list) or {route.get("role") for route in routes} \
            != set(EXPECTED_ROUTE_DIMENSIONS):
        raise ContractError("baseline route roles differ from G0")
    for route_entry in routes:
        if not isinstance(route_entry, dict):
            raise ContractError("baseline route entry must be an object")
        _exact_keys(route_entry, {"role", "path", "route_sha256"}, "baseline route")
        route_path = root / route_entry["path"]
        if _sha256(route_path) != route_entry["route_sha256"]:
            raise ContractError(f"route digest mismatch: {route_path}")
        route = _strict_object(route_path)
        if route.get("dimension") != EXPECTED_ROUTE_DIMENSIONS[route_entry["role"]]:
            raise ContractError(f"route dimension mismatch: {route_path}")
        if route.get("fixture") != {
            "id": baseline_world["fixture_id"], "sha256": fixture_sha,
        }:
            raise ContractError(f"route fixture mismatch: {route_path}")
        if route_entry["role"] == "sealed_cave" and route.get("position") == [0.0, 32.0, 0.0]:
            raise ContractError("sealed cave aliases the historical Nether route")

    for relative in SETTINGS:
        settings = _strict_object(root / relative)
        if settings.get("schema_version") != 3:
            raise ContractError(f"{relative} does not bind renderer properties")
        renderer = settings.get("renderer_properties")
        if not isinstance(renderer, dict) or renderer.get("globalIllumination") != "off":
            raise ContractError(f"{relative} does not attest GI_OFF")

    fixture_path = root / "run/benchmark-fixtures" / baseline_world["fixture_id"] / "world"
    if fixture_path.is_dir():
        # The canonical helper's digest includes paths, modes and bytes. Import
        # lazily so this verifier remains dependency-free.
        import sys
        sys.path.insert(0, str(root / "tools"))
        from metal_benchmark_fixture import tree_digest  # type: ignore
        if tree_digest(fixture_path) != fixture_sha:
            raise ContractError("provisioned immutable baseline fixture digest changed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--manifest", type=Path, default=Path("benchmark/gi/g0-fixtures-v1.json")
    )
    args = parser.parse_args()
    try:
        verify(args.root, args.manifest)
    except ContractError as error:
        parser.error(str(error))
    print("GI G0 fixture/settings contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
