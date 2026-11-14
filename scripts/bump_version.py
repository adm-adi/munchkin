import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = REPO_ROOT / "app" / "build.gradle.kts"
PACKAGE_JSON = REPO_ROOT / "server" / "package.json"
PACKAGE_LOCK_JSON = REPO_ROOT / "server" / "package-lock.json"

CODE_PATTERN = re.compile(r"versionCode\s*=\s*(\d+)")
NAME_PATTERN = re.compile(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"')


def read_gradle_version():
    content = GRADLE_FILE.read_text(encoding="utf-8")
    code_match = CODE_PATTERN.search(content)
    name_match = NAME_PATTERN.search(content)
    if not code_match:
        raise RuntimeError(f"versionCode not found in {GRADLE_FILE}")
    if not name_match:
        raise RuntimeError(f"versionName not found in {GRADLE_FILE}")
    return content, int(code_match.group(1)), name_match.group(1)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def check_versions():
    _, _, gradle_version = read_gradle_version()
    pkg = read_json(PACKAGE_JSON)
    lock = read_json(PACKAGE_LOCK_JSON)

    pkg_version = pkg.get("version")
    lock_version = lock.get("version")
    lock_root_version = lock.get("packages", {}).get("", {}).get("version")

    mismatches = []
    if pkg_version != gradle_version:
        mismatches.append(f"server/package.json version ({pkg_version}) != gradle ({gradle_version})")
    if lock_version != gradle_version:
        mismatches.append(f"server/package-lock.json version ({lock_version}) != gradle ({gradle_version})")
    if lock_root_version != gradle_version:
        mismatches.append(
            f"server/package-lock.json packages[''].version ({lock_root_version}) != gradle ({gradle_version})"
        )

    if mismatches:
        for item in mismatches:
            print(f"Version mismatch: {item}", file=sys.stderr)
        return 1

    print(f"Versions synchronized: {gradle_version}")
    return 0


def sync_versions(target_version: str):
    pkg = read_json(PACKAGE_JSON)
    pkg["version"] = target_version
    write_json(PACKAGE_JSON, pkg)

    lock = read_json(PACKAGE_LOCK_JSON)
    lock["version"] = target_version
    if isinstance(lock.get("packages"), dict) and isinstance(lock["packages"].get(""), dict):
        lock["packages"][""]["version"] = target_version
    write_json(PACKAGE_LOCK_JSON, lock)


def bump_version():
    content, current_code, current_name = read_gradle_version()
    new_code = current_code + 1

    parts = current_name.split(".")
    parts[-1] = str(int(parts[-1]) + 1)
    new_name = ".".join(parts)

    updated = CODE_PATTERN.sub(f"versionCode = {new_code}", content, count=1)
    updated = NAME_PATTERN.sub(f'versionName = "{new_name}"', updated, count=1)

    with open(GRADLE_FILE, "w", encoding="utf-8", newline="") as f:
        f.write(updated)

    sync_versions(new_name)
    print(new_name)
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Manage and verify synchronized Munchkin versions across Android and server."
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="Verify Android versionName, server/package.json, and server/package-lock.json match.",
    )
    mode.add_argument(
        "--sync",
        action="store_true",
        help="Sync server/package.json and server/package-lock.json to current Android versionName.",
    )
    args = parser.parse_args()

    if args.check:
        sys.exit(check_versions())
    if args.sync:
        _, _, current_name = read_gradle_version()
        sync_versions(current_name)
        print(current_name)
        sys.exit(0)
    sys.exit(bump_version())


if __name__ == "__main__":
    main()
