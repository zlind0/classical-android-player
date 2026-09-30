#!/usr/bin/env python3
"""Bundle AutoEq ParametricEQ presets into APK assets for on-demand reads.

Reads every results/<source>/<form>/<model>/<model> ParametricEQ.txt from the
third_party/AutoEq submodule checkout and writes into <out-dir>:

- autoeq_index.aeq: searchable index, fully loaded into RAM at runtime.
  Layout (all integers little-endian):
      magic  4 bytes "AEQ2"
      u32    entry count
      per entry:
          u32 id
          u32 + bytes  name   (utf-8)
          u32 + bytes  source (utf-8)
          u32 + bytes  form   (utf-8, e.g. "in-ear", "711 in-ear")
          u32 + bytes  path   (utf-8, relative to the submodule root)
          u64          blob offset in autoeq_blobs.aeq
          u32          blob length
- autoeq_blobs.aeq: concatenated utf-8 Equalizer APO parametric texts.
- autoeq_rev.txt: submodule commit SHA.

The blobs file must be packaged UNCOMPRESSED (see noCompress in
app/build.gradle.kts) so the app can read single presets on demand with
positioned reads straight from the APK: no duplicate copy on disk, and only
the requested preset ever enters memory. No network access at runtime.
"""
import argparse
import os
import struct
import subprocess
import sys
from pathlib import Path

MAGIC = b"AEQ2"


def submodule_rev(root: Path) -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=30,
        )
        sha = out.stdout.strip()
        if len(sha) == 40:
            return sha
    except Exception:
        pass
    return "unknown"


def put(buf: bytearray, s: str) -> None:
    b = s.encode("utf-8")
    buf += struct.pack("<I", len(b))
    buf += b


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--autoeq-root", required=True, help="third_party/AutoEq checkout dir")
    ap.add_argument("--out-dir", required=True, help="destination dir for autoeq assets")
    args = ap.parse_args()

    root = Path(args.autoeq_root)
    results = root / "results"
    if not results.is_dir():
        print(f"error: {results} is not a directory", file=sys.stderr)
        return 1

    files = sorted(results.rglob("*ParametricEQ.txt"))
    if not files:
        print(f"error: no ParametricEQ.txt under {results}", file=sys.stderr)
        return 1

    index = bytearray()
    index += MAGIC
    index += struct.pack("<I", 0)  # count placeholder
    blobs = bytearray()
    count = 0
    skipped = 0
    for i, f in enumerate(files):
        try:
            rel = f.relative_to(root).as_posix()
            parts = rel.split("/")
            # results/<source>/<form>/<model>/<file>
            source, form = parts[1], parts[2]
            name = f.parent.name
            text = f.read_text(encoding="utf-8")
        except Exception as e:
            print(f"warn: skip {f}: {e}", file=sys.stderr)
            skipped += 1
            continue
        if not text.strip():
            skipped += 1
            continue
        blob = text.encode("utf-8")
        index += struct.pack("<I", i)
        put(index, name)
        put(index, source)
        put(index, form)
        put(index, rel)
        index += struct.pack("<QI", len(blobs), len(blob))
        blobs += blob
        count += 1

    struct.pack_into("<I", index, len(MAGIC), count)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    # Drop artifacts from previous packaging formats so stale files never ship.
    for legacy in ("autoeq.db", "autoeq.pack"):
        try:
            (out_dir / legacy).unlink()
        except FileNotFoundError:
            pass
    for name, data in (("autoeq_index.aeq", bytes(index)), ("autoeq_blobs.aeq", bytes(blobs))):
        tmp = out_dir / (name + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, out_dir / name)

    rev = submodule_rev(root)
    (out_dir / "autoeq_rev.txt").write_text(rev + "\n", encoding="utf-8")
    print(f"AutoEq: {count} presets ({skipped} skipped) -> {out_dir} "
          f"(index {len(index)} bytes, blobs {len(blobs)} bytes, rev {rev})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
