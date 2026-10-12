#!/usr/bin/env python3
"""Offline, deterministic native N9Ezz addon build; never connects to Minecraft."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
LOADER_SHA256 = "bd6c983ac22f436e914f5510faa598a18c218f08e10c8b5c36504ebf31e40100"
VERSION = "0.2.0-experimental"
JAR_NAME = f"N9Ezz-CF-Assistant-{VERSION}.jar"
SOURCE_NAME = f"N9Ezz-CF-Assistant-{VERSION}-sources.zip"
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def java_tool(name: str) -> Path:
    configured = os.environ.get("JAVA_HOME")
    candidate = Path(configured) / "bin" / name if configured else None
    if candidate is not None:
        if not candidate.is_file():
            raise RuntimeError(f"JAVA_HOME has no bin/{name}; activate a complete JDK 21")
        return candidate.resolve()
    located = shutil.which(name)
    if not located:
        raise RuntimeError(f"Missing {name}; activate a complete JDK 21")
    return Path(located).resolve()


def run(command: list[str | Path], cwd: Path, timeout: int = 120) -> str:
    # Argument arrays deliberately avoid a shell. No downloads or external services
    # are part of this recipe; --run below is the loader's local API-only runtime.
    result = subprocess.run(
        [str(part) for part in command], cwd=cwd, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
        encoding="utf-8", errors="replace",
    )
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} failed ({result.returncode}):\n{result.stdout}")
    return result.stdout.replace("\r\n", "\n")


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, FIXED_TIMESTAMP)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def source_snapshot() -> dict[str, bytes]:
    # Explicit source roots/extensions exclude generated reports, user state,
    # pending-wager markers, chat recordings, credentials and build products.
    selected = [ROOT / name for name in ("build.py", ".gitignore", "README.md")]
    for directory, suffixes in (
        (ROOT / "src/main/java", {".java"}),
        (ROOT / "src/test/java", {".java"}),
        (ROOT / "src/main/resources", {".json", ".properties", ".txt"}),
        (ROOT / "src/test/resources", {".json", ".properties", ".txt"}),
        (ROOT / "docs", {".md"}),
    ):
        if directory.exists():
            selected.extend(path for path in directory.rglob("*") if path.suffix in suffixes)
    result: dict[str, bytes] = {}
    for path in sorted(set(selected)):
        if not path.exists():
            continue
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(ROOT):
            raise RuntimeError(f"Source must be a regular file inside the project: {path}")
        result[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    required = {
        "build.py", "src/main/resources/n9ezz.mod.json",
        "src/main/java/dev/n9ezz/cf/CfAssistant.java",
    }
    missing = required - result.keys()
    if missing:
        raise RuntimeError("Incomplete source tree: " + ", ".join(sorted(missing)))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", type=Path, default=ROOT.parent / "packet-update/dist/N9Ezz-Universal-Loader-0.4.1-network.1-experimental.jar")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist", help="Absent or empty destination; existing artifacts are never overwritten")
    args = parser.parse_args()
    loader = args.loader.resolve()
    output = args.output_dir.resolve()
    if output == ROOT or output.is_relative_to(ROOT / "src") or output.is_relative_to(ROOT / "docs"):
        raise RuntimeError("Output must be outside source/documentation directories")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise RuntimeError(f"Output directory must be absent or empty: {output}")
    if not loader.is_file() or digest(loader) != LOADER_SHA256:
        raise RuntimeError(f"Expected verified N9Ezz network.1 loader SHA-256 {LOADER_SHA256}; pass its path with --loader")
    java, javac = java_tool("java"), java_tool("javac")
    java_version = run([java, "-version"], ROOT)
    javac_version = run([javac, "-version"], ROOT)
    if not re.search(r'version "21(?:[.\"+\-])', java_version) or not re.search(r"javac 21(?:[.\s+\-]|$)", javac_version):
        raise RuntimeError("This pinned loader requires a complete Java 21 JDK")
    sources = source_snapshot()

    with tempfile.TemporaryDirectory(prefix="n9ezz-cf-build-") as temporary:
        work = Path(temporary)
        snapshot, classes, tests, products = (work / name for name in ("source", "classes", "test-classes", "products"))
        for directory in (snapshot, classes, tests, products):
            directory.mkdir()
        for name, data in sources.items():
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        main_files = sorted((snapshot / "src/main/java").rglob("*.java"))
        test_files = sorted((snapshot / "src/test/java").rglob("*.java"))
        if not test_files:
            raise RuntimeError("No test sources; refusing an untested build")
        run([javac, "--release", "21", "-encoding", "UTF-8", "-cp", loader, "-d", classes, *main_files], work)
        for prefix, destination in (("src/main/resources/", classes), ("src/test/resources/", tests)):
            for name, data in sources.items():
                if name.startswith(prefix):
                    resource = destination / name[len(prefix):]
                    resource.parent.mkdir(parents=True, exist_ok=True)
                    resource.write_bytes(data)
        classpath = os.pathsep.join(map(str, (loader, classes)))
        run([javac, "--release", "21", "-encoding", "UTF-8", "-cp", classpath, "-d", tests, *test_files], work)

        def normalized(text: str) -> str:
            return text.replace(str(work), "<BUILD>").replace(str(ROOT), "<PROJECT>").replace(str(loader), "<LOADER>")

        test_classpath = os.pathsep.join(map(str, (tests, classes, loader)))
        test_names, test_log = [], []
        for source in test_files:
            if not source.name.endswith("Test.java"):
                continue
            match = re.search(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", source.read_text(encoding="utf-8"), re.MULTILINE)
            name = (match.group(1) + "." if match else "") + source.stem
            test_names.append(name)
            text = run([java, "-ea", "-cp", test_classpath, name], work)
            test_log.append(f"[{name}]\n{normalized(text).rstrip()}\n")
            print(f"PASS {name}")
        if not test_names:
            raise RuntimeError("No *Test.java main classes discovered")
        (products / "test-report.txt").write_text("\n".join(test_log), encoding="utf-8")

        jar_entries = {path.relative_to(classes).as_posix(): path.read_bytes() for path in sorted(classes.rglob("*.class"))}
        resource_prefix = "src/main/resources/"
        jar_entries.update({name[len(resource_prefix):]: data for name, data in sources.items() if name.startswith(resource_prefix)})
        jar_entries["META-INF/MANIFEST.MF"] = f"Manifest-Version: 1.0\r\nImplementation-Title: N9Ezz CF Assistant\r\nImplementation-Version: {VERSION}\r\n\r\n".encode("ascii")
        jar_path = products / JAR_NAME
        write_zip(jar_path, jar_entries)
        write_zip(products / SOURCE_NAME, {"cf-assistant/" + name: data for name, data in sources.items()})

        mods = work / "mods"
        mods.mkdir()
        shutil.copyfile(jar_path, mods / JAR_NAME)
        for operation in ("verify", "run"):
            text = run([java, "-jar", loader, "--" + operation, "--game-dir", work / (operation + "-game"), "--mods-dir", mods], work)
            if f"cf_assistant@{VERSION}" not in text:
                raise RuntimeError(f"API-only --{operation} did not report the addon")
            if operation == "run" and "ENTRYPOINT:cf_assistant:client" not in text:
                raise RuntimeError("API-only --run did not execute the client initializer")
            (products / f"api-only-{operation}.log").write_text(normalized(text), encoding="utf-8")
            print(f"PASS native API-only --{operation}")

        report = {
            "addon": "cf_assistant", "version": VERSION,
            "loader_sha256": LOADER_SHA256,
            "java": java_version.strip().splitlines(), "javac": javac_version.strip(),
            "build": "PASS", "offline": True,
            "test_classes": test_names,
            "native_verify": "PASS", "native_api_only_run": "PASS",
            "minecraft_gameplay": "UNRUN", "live_server_commands": "UNRUN",
            "500_game_training": "UNRUN",
            "neural_model": "Local 12-8-1 MLP; synthetic prequential evaluation only; no server-trained weights bundled",
            "goal_currency": "Exact own coins balance from configured /bal reply; raids and koins excluded",
            "server_templates": "USER_CONFIGURATION_REQUIRED",
            "limitations": "API-only checks do not launch Minecraft, exercise real Pulse hooks, or play server games.",
            "source_files": sorted(sources),
            "artifacts": {name: {"sha256": digest(products / name), "bytes": (products / name).stat().st_size} for name in (JAR_NAME, SOURCE_NAME)},
        }
        (products / "build-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        hashes = "".join(f"{digest(path)}  {path.name}\n" for path in sorted(products.iterdir()))
        (products / "SHA256SUMS").write_text(hashes, encoding="utf-8")
        output.mkdir(parents=True, exist_ok=True)
        # Check again immediately before writing, so an unrelated concurrent build
        # or user's existing deliverable is never silently replaced.
        if any(output.iterdir()):
            raise RuntimeError(f"Output changed during build; refusing overwrite: {output}")
        for product in sorted(products.iterdir()):
            with product.open("rb") as source, (output / product.name).open("xb") as destination:
                shutil.copyfileobj(source, destination)
    print(f"Built {output / JAR_NAME}")
    print("Minecraft gameplay and server training: UNRUN")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
