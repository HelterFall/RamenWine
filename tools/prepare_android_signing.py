#!/usr/bin/env python3
"""Validate and stage the permanent RamenWine Android signing bundle for CI.

The private PKCS#12 keystore is supplied only through the
RAMENWINE_SIGNING_BUNDLE_BASE64 GitHub Actions secret. It is never stored in
the repository or in a workflow artifact.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"Android signing bundle error: {message}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--github-env", type=Path)
    args = parser.parse_args()

    encoded = os.environ.get("RAMENWINE_SIGNING_BUNDLE_BASE64", "").strip()
    if not encoded:
        fail("GitHub Actions secret RAMENWINE_SIGNING_BUNDLE_BASE64 is not set.")
    if len(encoded) > 48_000:
        fail("Signing bundle exceeds GitHub's 48 KB secret limit.")

    try:
        document = json.loads(base64.b64decode(encoded, validate=True))
        if document.get("schemaVersion") != 1:
            fail("Unsupported signing bundle schema.")
        alias = document["keyAlias"]
        store_password = document["storePassword"]
        key_password = document["keyPassword"]
        keystore_bytes = base64.b64decode(document["keystoreBase64"], validate=True)
        expected_sha256 = re.sub(r"[^0-9a-f]", "", document["certificateSha256"].lower())
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        fail(f"Malformed signing bundle ({error}).")

    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", alias):
        fail("Keystore alias contains unsupported characters.")
    if len(store_password) < 8 or len(key_password) < 8:
        fail("Keystore passwords are too short.")
    if not keystore_bytes or len(keystore_bytes) > 1_000_000:
        fail("Keystore payload is missing or unexpectedly large.")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        fail("Certificate SHA-256 fingerprint is invalid.")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    keystore_path = output_dir / "ramenwine-signing.p12"
    keystore_path.write_bytes(keystore_bytes)
    keystore_path.chmod(0o600)

    keytool = shutil.which("keytool")
    openssl = shutil.which("openssl")
    if not keytool or not openssl:
        fail("The CI runner must provide keytool and openssl.")

    try:
        subprocess.run(
            [
                keytool, "-list", "-keystore", str(keystore_path),
                "-storetype", "PKCS12", "-storepass", store_password,
                "-alias", alias,
            ],
            check=True, capture_output=True, text=True,
        )
        certificate_pem = subprocess.run(
            [
                openssl, "pkcs12", "-in", str(keystore_path),
                "-passin", f"pass:{store_password}", "-clcerts", "-nokeys",
            ],
            check=True, capture_output=True, text=True,
        ).stdout
        fingerprint_output = subprocess.run(
            [openssl, "x509", "-noout", "-fingerprint", "-sha256"],
            input=certificate_pem, check=True, capture_output=True, text=True,
        ).stdout.strip()
        actual_sha256 = fingerprint_output.split("=", 1)[-1].replace(":", "").lower()
        # Confirm the encrypted private-key entry can also be opened.
        subprocess.run(
            [
                openssl, "pkcs12", "-in", str(keystore_path),
                "-passin", f"pass:{store_password}", "-nocerts", "-noout",
            ],
            check=True, capture_output=True, text=True,
        )
    except subprocess.CalledProcessError as error:
        fail("The PKCS#12 keystore, password, or alias is invalid.")

    if actual_sha256 != expected_sha256:
        fail("The keystore certificate does not match the bundle fingerprint.")

    if args.github_env:
        if store_password != key_password:
            # Gradle accepts separate values; the workflow exports both.
            pass
        environment = {
            "RAMENWINE_SIGNING_KEYSTORE": str(keystore_path),
            "RAMENWINE_SIGNING_STORE_PASSWORD": store_password,
            "RAMENWINE_SIGNING_KEY_ALIAS": alias,
            "RAMENWINE_SIGNING_KEY_PASSWORD": key_password,
            "RAMENWINE_SIGNING_CERT_SHA256": expected_sha256,
        }
        with args.github_env.open("a", encoding="utf-8") as output:
            for name, value in environment.items():
                if "\n" in value or "\r" in value:
                    fail(f"Invalid newline in {name}.")
                output.write(f"{name}={value}\n")
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::add-mask::{store_password}")
            print(f"::add-mask::{key_password}")

    formatted = ":".join(expected_sha256[index:index + 2] for index in range(0, 64, 2)).upper()
    print(f"Validated persistent RamenWine signing identity: SHA-256 {formatted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
