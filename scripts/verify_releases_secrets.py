import argparse
import base64
import hashlib
import os
import shutil
import subprocess
import sys


def get_property(name, props_path="local.properties"):
    if not os.path.exists(props_path):
        return None
    with open(props_path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            if "=" in line:
                key, value = line.split("=", 1)
                if key.strip() == name:
                    return value.strip()
    return None


def find_keytool():
    exe_name = "keytool.exe" if os.name == "nt" else "keytool"
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", exe_name)
        if os.path.isfile(candidate):
            return candidate

    from_path = shutil.which("keytool")
    if from_path:
        return from_path

    fallback = r"D:\Program Files\Android\jbr\bin\keytool.exe"
    if os.path.isfile(fallback):
        return fallback

    return None


def verify_and_generate(show_secret=False):
    keystore_path = "munchkin.keystore"
    if not os.path.exists(keystore_path):
        print(f"Error: {keystore_path} not found.", file=sys.stderr)
        return 1

    password = get_property("KEYSTORE_STORE_PASSWORD") or os.environ.get("KEYSTORE_STORE_PASSWORD")
    if not password:
        print("Error: KEYSTORE_STORE_PASSWORD not found in local.properties or environment.", file=sys.stderr)
        return 1

    keytool_path = find_keytool()
    if not keytool_path:
        print("Error: keytool executable not found (checked JAVA_HOME, PATH, and JBR fallback).", file=sys.stderr)
        return 1

    print("Verifying keystore password with keytool...")
    env = os.environ.copy()
    env["MUNCHKIN_KEYSTORE_PASS"] = password
    cmd = [
        keytool_path,
        "-list",
        "-keystore",
        keystore_path,
        "-storepass:env",
        "MUNCHKIN_KEYSTORE_PASS",
    ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, env=env)
        if result.returncode != 0:
            print("FAILURE: Keystore verification failed.", file=sys.stderr)
            if result.stderr:
                print(result.stderr.strip(), file=sys.stderr)
            elif result.stdout:
                print(result.stdout.strip(), file=sys.stderr)
            return 1

        with open(keystore_path, "rb") as kf:
            raw_bytes = kf.read()
        encoded = base64.b64encode(raw_bytes).decode("ascii")
        digest = hashlib.sha256(raw_bytes).hexdigest()

        print("SUCCESS: Keystore password is valid.")
        print(f"Keystore SHA-256: {digest}")
        print(f"KEYSTORE_BASE64 length: {len(encoded)} chars")

        if show_secret:
            print("\nKEYSTORE_BASE64:")
            print("==================================================")
            print(encoded)
            print("==================================================")
        else:
            print("Raw base64 secret redacted. Re-run with --show-secret to print KEYSTORE_BASE64.")

        return 0
    except Exception as exc:
        print(f"Error verifying keystore: {exc}", file=sys.stderr)
        return 1


def main():
    parser = argparse.ArgumentParser(
        description="Verify munchkin.keystore credentials and optionally output KEYSTORE_BASE64."
    )
    parser.add_argument(
        "--show-secret",
        action="store_true",
        help="Print raw KEYSTORE_BASE64 secret to stdout (omitted by default).",
    )
    args = parser.parse_args()
    sys.exit(verify_and_generate(show_secret=args.show_secret))


if __name__ == "__main__":
    main()
