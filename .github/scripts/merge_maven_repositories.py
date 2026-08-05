#!/usr/bin/env python3
"""Merge disjoint Maven repository shards without silently overwriting files."""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def merge(output: Path, shards: list[Path]) -> None:
    if output.exists() and any(output.iterdir()):
        raise SystemExit(f"Maven repository output is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    copied = 0
    for shard in shards:
        if not shard.is_dir():
            raise SystemExit(f"Maven repository shard is missing: {shard}")
        for source in sorted(path for path in shard.rglob("*") if path.is_file()):
            relative = source.relative_to(shard)
            if relative.name == "SHA256SUMS":
                raise SystemExit(f"Shard must not contain a final checksum manifest: {source}")
            target = output / relative
            if target.exists():
                if digest(source) != digest(target):
                    raise SystemExit(f"Conflicting Maven shard file: {relative}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            copied += 1
    if copied == 0:
        raise SystemExit("No Maven repository files were merged")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("shards", nargs="+", type=Path)
    arguments = parser.parse_args()
    merge(arguments.output, arguments.shards)


if __name__ == "__main__":
    main()
