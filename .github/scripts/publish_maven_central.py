#!/usr/bin/env python3

"""Upload a signed bundle through the Maven Central Publisher Portal API."""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
from pathlib import Path
import re
import time
from typing import Any
from urllib.parse import urlencode
import uuid


CENTRAL_HOST = "central.sonatype.com"
UPLOAD_PATH = "/api/v1/publisher/upload"
STATUS_PATH = "/api/v1/publisher/status"
PENDING_STATES = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"}
TERMINAL_STATES = {"PUBLISHED", "FAILED"}


class PortalError(RuntimeError):
    """Raised for a safe-to-report Publisher Portal failure."""


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_secret(path: Path, label: str) -> str:
    if not path.is_file():
        raise PortalError(f"{label} file is missing")
    if path.stat().st_mode & 0o077:
        raise PortalError(f"{label} file must not be accessible by group or other users")
    value = path.read_text(encoding="utf-8").rstrip("\r\n")
    if not value:
        raise PortalError(f"{label} file is empty")
    return value


def authorization_header(username_file: Path, password_file: Path) -> str:
    username = read_secret(username_file, "MAVEN_CENTRAL_USERNAME")
    password = read_secret(password_file, "MAVEN_CENTRAL_PASSWORD")
    if ":" in username:
        raise PortalError("MAVEN_CENTRAL_USERNAME contains an unsupported separator")
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Bearer {token}"


def open_connection() -> http.client.HTTPSConnection:
    return http.client.HTTPSConnection(CENTRAL_HOST, timeout=300)


def upload_bundle(bundle: Path, deployment_name: str, authorization: str) -> str:
    if not bundle.is_file() or bundle.stat().st_size == 0:
        raise PortalError("Maven Central bundle is missing or empty")
    if bundle.stat().st_size >= 1024 * 1024 * 1024:
        raise PortalError("Maven Central bundle exceeds the 1 GiB Portal limit")
    if re.fullmatch(r"[0-9A-Za-z_.-]{1,200}", bundle.name) is None:
        raise PortalError("Bundle filename contains unsupported characters")
    if re.fullmatch(r"[\x20-\x7e]{1,200}", deployment_name) is None:
        raise PortalError("Deployment name must contain 1-200 printable ASCII characters")

    boundary = f"----KendiveMavenCentral{uuid.uuid4().hex}"
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="bundle"; filename="{bundle.name}"\r\n'
        "Content-Type: application/octet-stream\r\n"
        "\r\n"
    ).encode("ascii")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    content_length = len(prefix) + bundle.stat().st_size + len(suffix)
    query = urlencode(
        {
            "name": deployment_name,
            "publishingType": "AUTOMATIC",
        }
    )

    connection = open_connection()
    try:
        connection.putrequest("POST", f"{UPLOAD_PATH}?{query}")
        connection.putheader("Authorization", authorization)
        connection.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
        connection.putheader("Content-Length", str(content_length))
        connection.putheader("User-Agent", "Kendive-Maven-Central-Release/1")
        connection.endheaders()
        connection.send(prefix)
        with bundle.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                connection.send(chunk)
        connection.send(suffix)
        response = connection.getresponse()
        body = response.read()
        status = response.status
    except (OSError, http.client.HTTPException) as error:
        raise PortalError(
            "Maven Central upload ended without a definitive response; "
            "inspect the Publisher Portal before retrying"
        ) from error
    finally:
        connection.close()

    if status != 201:
        raise PortalError(f"Maven Central upload failed with HTTP {status}")
    try:
        deployment_id = str(uuid.UUID(body.decode("ascii").strip()))
    except (UnicodeDecodeError, ValueError) as error:
        raise PortalError("Maven Central returned an invalid deployment identifier") from error
    print(f"Maven Central deployment created: {deployment_id}")
    return deployment_id


def request_status(deployment_id: str, authorization: str) -> dict[str, Any]:
    query = urlencode({"id": deployment_id})
    connection = open_connection()
    try:
        connection.request(
            "POST",
            f"{STATUS_PATH}?{query}",
            body=b"",
            headers={
                "Authorization": authorization,
                "Content-Length": "0",
                "User-Agent": "Kendive-Maven-Central-Release/1",
            },
        )
        response = connection.getresponse()
        body = response.read()
        status = response.status
    except (OSError, http.client.HTTPException) as error:
        raise PortalError("Maven Central status request failed") from error
    finally:
        connection.close()
    if status != 200:
        raise PortalError(f"Maven Central status request failed with HTTP {status}")
    try:
        result = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PortalError("Maven Central returned an invalid status response") from error
    if not isinstance(result, dict):
        raise PortalError("Maven Central returned an invalid status document")
    if result.get("deploymentId") != deployment_id:
        raise PortalError("Maven Central status refers to a different deployment")
    state = result.get("deploymentState")
    if state not in PENDING_STATES | TERMINAL_STATES:
        raise PortalError(f"Maven Central returned an unsupported deployment state: {state!r}")
    purls = result.get("purls", [])
    if not isinstance(purls, list) or any(not isinstance(purl, str) for purl in purls):
        raise PortalError("Maven Central returned an invalid purl list")
    return result


def safe_errors(status: dict[str, Any]) -> str:
    errors = status.get("errors")
    if errors is None:
        return "No validation details were returned."
    rendered = json.dumps(errors, ensure_ascii=True, sort_keys=True)
    rendered = "".join(character if character.isprintable() else " " for character in rendered)
    return rendered[:4000]


def write_receipt(
    path: Path,
    *,
    deployment_id: str,
    deployment_name: str,
    deployment_state: str,
    bundle_sha256: str,
    purls: list[str],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    receipt = {
        "bundleSha256": bundle_sha256,
        "deploymentId": deployment_id,
        "deploymentName": deployment_name,
        "deploymentState": deployment_state,
        "purls": sorted(purls),
    }
    path.write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def append_summary(
    path: Path | None,
    *,
    deployment_id: str,
    deployment_state: str,
    bundle_sha256: str,
    purls: list[str],
) -> None:
    if path is None:
        return
    lines = [
        "## Maven Central release",
        "",
        f"- Deployment: `{deployment_id}`",
        f"- State: `{deployment_state}`",
        f"- Bundle SHA-256: `{bundle_sha256}`",
    ]
    if purls:
        lines.extend(["- Published coordinates:"])
        lines.extend(f"  - `{purl}`" for purl in sorted(purls))
    lines.append("")
    with path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines))


def wait_for_publication(
    *,
    deployment_id: str,
    deployment_name: str,
    authorization: str,
    bundle_sha256: str,
    receipt: Path,
    summary: Path | None,
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_state: str | None = None
    transient_failures = 0
    while time.monotonic() < deadline:
        try:
            status = request_status(deployment_id, authorization)
            transient_failures = 0
        except PortalError:
            transient_failures += 1
            if transient_failures >= 5:
                raise
            time.sleep(poll_interval_seconds)
            continue

        state = status["deploymentState"]
        purls = status.get("purls", [])
        write_receipt(
            receipt,
            deployment_id=deployment_id,
            deployment_name=deployment_name,
            deployment_state=state,
            bundle_sha256=bundle_sha256,
            purls=purls,
        )
        if state != last_state:
            print(f"Maven Central deployment state: {state}")
            last_state = state
        if state == "PUBLISHED":
            append_summary(
                summary,
                deployment_id=deployment_id,
                deployment_state=state,
                bundle_sha256=bundle_sha256,
                purls=purls,
            )
            for purl in sorted(purls):
                print(f"Published: {purl}")
            return
        if state == "FAILED":
            append_summary(
                summary,
                deployment_id=deployment_id,
                deployment_state=state,
                bundle_sha256=bundle_sha256,
                purls=purls,
            )
            raise PortalError(
                f"Maven Central validation failed: {safe_errors(status)}"
            )
        time.sleep(poll_interval_seconds)
    raise PortalError(
        f"Maven Central deployment {deployment_id} did not finish within "
        f"{timeout_seconds} seconds"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--username-file", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--deployment-name", required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=5400)
    parser.add_argument("--poll-interval-seconds", type=int, default=20)
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    if arguments.timeout_seconds <= 0 or arguments.poll_interval_seconds <= 0:
        print("error: publication timeout and poll interval must be positive")
        return 1
    try:
        authorization = authorization_header(
            arguments.username_file,
            arguments.password_file,
        )
        bundle_sha256 = hash_file(arguments.bundle)
        deployment_id = upload_bundle(
            arguments.bundle,
            arguments.deployment_name,
            authorization,
        )
        write_receipt(
            arguments.receipt,
            deployment_id=deployment_id,
            deployment_name=arguments.deployment_name,
            deployment_state="UPLOADED",
            bundle_sha256=bundle_sha256,
            purls=[],
        )
        wait_for_publication(
            deployment_id=deployment_id,
            deployment_name=arguments.deployment_name,
            authorization=authorization,
            bundle_sha256=bundle_sha256,
            receipt=arguments.receipt,
            summary=arguments.summary,
            timeout_seconds=arguments.timeout_seconds,
            poll_interval_seconds=arguments.poll_interval_seconds,
        )
    except (OSError, PortalError) as error:
        print(f"error: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
