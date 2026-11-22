#!/usr/bin/env python3
"""Prepare the locked glslang and SPIRV-Cross checkouts used for RetroArch .slang shaders.

Only the source folders the Android build needs are checked out (sparse checkout): the
upstream test fixtures are large and some exceed Windows path limits.
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party" / "shader-tools.lock"
DEST = ROOT / "third_party" / "shader-tools"

# Sparse-checkout patterns per project (gitignore syntax, non-cone mode).
SPARSE = {
    "glslang": ["/*", "!/*/", "/glslang/", "/SPIRV/", "/StandAlone/DirStackFileIncluder.h", "/External/", "/cmake/", "/build_overrides/", "/LICENSES/"],
    "SPIRV-Cross": ["/*", "!/*/", "/include/", "/cmake/"],
}


def read_lock() -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in LOCK.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep:
            raise SystemExit(f"Invalid lock line: {raw}")
        result[key.strip()] = value.strip()
    return result


def run(*args: str, cwd: pathlib.Path | None = None) -> str:
    proc = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode:
        sys.stderr.write(proc.stderr)
        raise SystemExit(proc.returncode)
    return proc.stdout.strip()


def prepare(git: str, name: str, repo: str, revision: str) -> None:
    dest = DEST / name
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        run(git, "clone", "--filter=blob:none", "--no-checkout", repo, str(dest))
    elif not (dest / ".git").exists():
        raise SystemExit(f"{dest} exists but is not a Git checkout; move it aside and rerun")
    run(git, "sparse-checkout", "set", "--no-cone", *SPARSE[name], cwd=dest)
    run(git, "fetch", "--depth=1", "origin", revision, cwd=dest)
    run(git, "-c", "advice.detachedHead=false", "checkout", "--detach", revision, cwd=dest)
    actual = run(git, "rev-parse", "HEAD", cwd=dest).lower()
    if actual != revision:
        raise SystemExit(f"{name} revision mismatch: expected {revision}, got {actual}")
    if run(git, "status", "--porcelain", "--untracked-files=no", cwd=dest):
        raise SystemExit(f"{name} checkout has tracked local modifications")
    print(f"{name} ready at {revision}")


def main() -> None:
    git = shutil.which("git")
    if not git:
        raise SystemExit("Git is required to prepare the shader tool checkouts")
    lock = read_lock()
    for name in SPARSE:
        repo = lock.get(f"{name}.repository")
        revision = lock.get(f"{name}.revision", "").lower()
        if not repo or not revision:
            raise SystemExit(f"third_party/shader-tools.lock must define {name}.repository and {name}.revision")
        prepare(git, name, repo, revision)


if __name__ == "__main__":
    main()
