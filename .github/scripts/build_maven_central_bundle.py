#!/usr/bin/env python3

"""Build and sign a Maven Central bundle from the verified release staging repository."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import zipfile


CHECKSUM_ALGORITHMS = {
    ".md5": "md5",
    ".sha1": "sha1",
    ".sha256": "sha256",
    ".sha512": "sha512",
}
RELEASE_VERSION = re.compile(
    r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
)


class BundleError(RuntimeError):
    """Raised when release evidence or bundle contents are invalid."""


def hash_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def secure_secret_file(path: Path, label: str, *, allow_empty: bool = False) -> None:
    if not path.is_file():
        raise BundleError(f"{label} file is missing")
    if not allow_empty and path.stat().st_size == 0:
        raise BundleError(f"{label} file is empty")
    if path.stat().st_mode & 0o077:
        raise BundleError(f"{label} file must not be accessible by group or other users")


def safe_manifest_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if (
        not value
        or "\\" in value
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise BundleError(f"Unsafe path in SHA256SUMS: {value!r}")
    return path


def verify_staging_repository(repository: Path) -> dict[PurePosixPath, str]:
    if not repository.is_dir():
        raise BundleError("Release staging repository is missing")

    for path in repository.rglob("*"):
        if path.is_symlink():
            raise BundleError(
                f"Release staging repository contains a symbolic link: "
                f"{path.relative_to(repository)}"
            )

    manifest = repository / "SHA256SUMS"
    if not manifest.is_file():
        raise BundleError("Release staging SHA256SUMS is missing")

    expected: dict[PurePosixPath, str] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise BundleError("Release staging SHA256SUMS contains an invalid line")
        relative_path = safe_manifest_path(match.group(2))
        if relative_path in expected:
            raise BundleError(
                f"Release staging SHA256SUMS repeats {relative_path.as_posix()}"
            )
        expected[relative_path] = match.group(1)

    actual = {
        PurePosixPath(path.relative_to(repository).as_posix())
        for path in repository.rglob("*")
        if path.is_file() and path != manifest
    }
    expected_paths = set(expected)
    if actual != expected_paths:
        missing = sorted(path.as_posix() for path in expected_paths - actual)
        unexpected = sorted(path.as_posix() for path in actual - expected_paths)
        raise BundleError(
            "Release staging files differ from SHA256SUMS; "
            f"missing={missing}, unexpected={unexpected}"
        )

    for relative_path, expected_digest in sorted(
        expected.items(), key=lambda item: item[0].as_posix()
    ):
        actual_digest = hash_file(repository / relative_path, "sha256")
        if actual_digest != expected_digest:
            raise BundleError(
                f"Release staging SHA-256 mismatch: {relative_path.as_posix()}"
            )
    return expected


def checksum_suffix(path: PurePosixPath) -> str | None:
    return next(
        (suffix for suffix in CHECKSUM_ALGORITHMS if path.name.endswith(suffix)),
        None,
    )


def select_version_files(
    staged_files: dict[PurePosixPath, str], version: str
) -> list[PurePosixPath]:
    selected = sorted(
        (
            path
            for path in staged_files
            if len(path.parts) >= 4 and path.parts[-2] == version
        ),
        key=PurePosixPath.as_posix,
    )
    if not selected:
        raise BundleError(f"Release staging contains no files for {version}")
    if not any(path.name.endswith(".pom") for path in selected):
        raise BundleError(f"Release staging contains no POM for {version}")
    if any("maven-metadata.xml" in path.name for path in selected):
        raise BundleError("Repository-level Maven metadata reached the version bundle")
    return selected


def verify_payload_checksums(
    bundle_root: Path, selected: list[PurePosixPath]
) -> list[PurePosixPath]:
    selected_set = set(selected)
    payload = [
        path
        for path in selected
        if checksum_suffix(path) is None and not path.name.endswith(".asc")
    ]
    if not payload:
        raise BundleError("Maven Central bundle contains no payload files")

    for relative_path in payload:
        payload_file = bundle_root / relative_path
        for suffix, algorithm in CHECKSUM_ALGORITHMS.items():
            checksum_path = PurePosixPath(relative_path.as_posix() + suffix)
            if checksum_path not in selected_set:
                raise BundleError(
                    f"Missing {suffix} checksum for {relative_path.as_posix()}"
                )
            checksum = (bundle_root / checksum_path).read_text(
                encoding="ascii"
            ).strip()
            expected_length = hashlib.new(algorithm).digest_size * 2
            if re.fullmatch(rf"[0-9a-fA-F]{{{expected_length}}}", checksum) is None:
                raise BundleError(
                    f"Invalid {suffix} checksum for {relative_path.as_posix()}"
                )
            if hash_file(payload_file, algorithm) != checksum.lower():
                raise BundleError(
                    f"{suffix} checksum mismatch for {relative_path.as_posix()}"
                )

    for relative_path in selected:
        suffix = checksum_suffix(relative_path)
        if suffix is None:
            continue
        payload_path = PurePosixPath(relative_path.as_posix()[: -len(suffix)])
        if payload_path not in payload:
            raise BundleError(
                f"Checksum has no payload file: {relative_path.as_posix()}"
            )
    return payload


def run_gpg(arguments: list[str], *, capture_output: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        arguments,
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE if capture_output else subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        text=capture_output,
    )


def sign_payload(
    bundle_root: Path,
    payload: list[PurePosixPath],
    signing_key_file: Path,
    signing_password_file: Path,
    work_directory: Path,
) -> list[PurePosixPath]:
    gpg = shutil.which("gpg")
    if gpg is None:
        raise BundleError("gpg is required to sign the Maven Central bundle")

    gpg_home = work_directory / "gnupg"
    gpg_home.mkdir(mode=0o700)
    os.chmod(gpg_home, 0o700)
    common = [gpg, "--batch", "--no-tty", "--homedir", str(gpg_home)]

    imported = run_gpg([*common, "--import", str(signing_key_file)])
    if imported.returncode != 0:
        raise BundleError(
            "MAVEN_SIGNING_KEY could not be imported as an ASCII-armored private PGP key"
        )

    secret_keys = run_gpg(
        [*common, "--with-colons", "--list-secret-keys"],
        capture_output=True,
    )
    if secret_keys.returncode != 0:
        raise BundleError("The imported PGP signing key could not be inspected")
    key_count = sum(
        line.startswith("sec:") for line in (secret_keys.stdout or "").splitlines()
    )
    if key_count != 1:
        raise BundleError("MAVEN_SIGNING_KEY must contain exactly one private PGP key")

    signatures: list[PurePosixPath] = []
    for relative_path in payload:
        source = bundle_root / relative_path
        signature_path = PurePosixPath(relative_path.as_posix() + ".asc")
        signature = bundle_root / signature_path
        signed = run_gpg(
            [
                *common,
                "--yes",
                "--pinentry-mode",
                "loopback",
                "--passphrase-file",
                str(signing_password_file),
                "--armor",
                "--detach-sign",
                "--output",
                str(signature),
                str(source),
            ]
        )
        if signed.returncode != 0:
            raise BundleError(f"PGP signing failed for {relative_path.as_posix()}")
        verified = run_gpg([*common, "--verify", str(signature), str(source)])
        if verified.returncode != 0:
            raise BundleError(
                f"Generated PGP signature is invalid for {relative_path.as_posix()}"
            )
        signatures.append(signature_path)
    return signatures


def write_deterministic_zip(
    bundle_root: Path, entries: list[PurePosixPath], output: Path
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{output.name}.",
        suffix=".tmp",
        dir=output.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(
            temporary_path,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=6,
            allowZip64=True,
        ) as archive:
            for relative_path in sorted(entries, key=PurePosixPath.as_posix):
                info = zipfile.ZipInfo(
                    relative_path.as_posix(),
                    date_time=(1980, 1, 1, 0, 0, 0),
                )
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100644 & 0xFFFF) << 16
                with (bundle_root / relative_path).open("rb") as source:
                    with archive.open(info, mode="w", force_zip64=True) as target:
                        shutil.copyfileobj(source, target, length=1024 * 1024)
        os.replace(temporary_path, output)
    finally:
        temporary_path.unlink(missing_ok=True)


def build_bundle(
    repository: Path,
    version: str,
    signing_key_file: Path,
    signing_password_file: Path,
    output: Path,
) -> None:
    if RELEASE_VERSION.fullmatch(version) is None:
        raise BundleError(f"Unsupported immutable release version: {version}")
    secure_secret_file(signing_key_file, "MAVEN_SIGNING_KEY")
    secure_secret_file(
        signing_password_file,
        "MAVEN_SIGNING_PASSWORD",
        allow_empty=True,
    )
    repository = repository.resolve(strict=True)
    output = output.resolve()
    if repository == output or repository in output.parents:
        raise BundleError("Bundle output must be outside the staging repository")
    output.parent.mkdir(parents=True, exist_ok=True)

    staged_files = verify_staging_repository(repository)
    selected = select_version_files(staged_files, version)

    with tempfile.TemporaryDirectory(
        prefix="kendive-maven-central-", dir=output.parent
    ) as temporary:
        work_directory = Path(temporary)
        bundle_root = work_directory / "bundle"
        for relative_path in selected:
            source = repository / relative_path
            target = bundle_root / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            os.chmod(target, 0o644)

        payload = verify_payload_checksums(bundle_root, selected)
        signatures = sign_payload(
            bundle_root,
            payload,
            signing_key_file,
            signing_password_file,
            work_directory,
        )
        write_deterministic_zip(bundle_root, [*selected, *signatures], output)

    print(
        f"Created Maven Central bundle with {len(payload)} payload files and "
        f"{len(selected) + len(payload)} entries"
    )
    print(f"Bundle SHA-256: {hash_file(output, 'sha256')}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--signing-key-file", type=Path, required=True)
    parser.add_argument("--signing-password-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    try:
        build_bundle(
            repository=arguments.repository,
            version=arguments.version,
            signing_key_file=arguments.signing_key_file,
            signing_password_file=arguments.signing_password_file,
            output=arguments.output,
        )
    except (BundleError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"error: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
