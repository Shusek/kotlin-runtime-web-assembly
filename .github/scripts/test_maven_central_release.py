from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest import mock

import build_maven_central_bundle as bundle
import merge_maven_repositories as repository_merge
import publish_maven_central as portal


class BundleTests(unittest.TestCase):
    def test_selects_only_exact_version_directory(self) -> None:
        staged = {
            PurePosixPath(
                "uk/shusek/krwa/example/0.3.0-rc.2/example-0.3.0-rc.2.pom"
            ): "0" * 64,
            PurePosixPath(
                "uk/shusek/krwa/example/0.3.0-rc.1/example-0.3.0-rc.1.pom"
            ): "0" * 64,
            PurePosixPath(
                "uk/shusek/krwa/example/maven-metadata.xml"
            ): "0" * 64,
        }

        selected = bundle.select_version_files(staged, "0.3.0-rc.2")

        self.assertEqual(
            selected,
            [
                PurePosixPath(
                    "uk/shusek/krwa/example/0.3.0-rc.2/"
                    "example-0.3.0-rc.2.pom"
                )
            ],
        )

    def test_verifies_all_supported_payload_checksums(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload_path = PurePosixPath(
                "uk/shusek/krwa/example/0.3.0-rc.2/example-0.3.0-rc.2.pom"
            )
            payload_file = root / payload_path
            payload_file.parent.mkdir(parents=True)
            payload_file.write_bytes(b"<project/>")
            selected = [payload_path]
            for suffix, algorithm in bundle.CHECKSUM_ALGORITHMS.items():
                checksum_path = PurePosixPath(payload_path.as_posix() + suffix)
                (root / checksum_path).write_text(
                    hashlib.new(algorithm, payload_file.read_bytes()).hexdigest(),
                    encoding="ascii",
                )
                selected.append(checksum_path)

            payload = bundle.verify_payload_checksums(root, selected)

            self.assertEqual(payload, [payload_path])


class RepositoryMergeTests(unittest.TestCase):
    def test_merges_disjoint_shards_and_accepts_identical_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            output = root / "output"
            shared = Path("uk/shusek/krwa/example/maven-metadata.xml")
            first_file = first / shared
            second_file = second / shared
            first_file.parent.mkdir(parents=True)
            second_file.parent.mkdir(parents=True)
            first_file.write_text("metadata", encoding="utf-8")
            second_file.write_text("metadata", encoding="utf-8")
            unique = second / "uk/shusek/krwa/example-ios/0.3.0/example.klib"
            unique.parent.mkdir(parents=True)
            unique.write_bytes(b"ios")

            repository_merge.merge(output, [first, second])

            self.assertEqual((output / shared).read_text(encoding="utf-8"), "metadata")
            self.assertEqual((output / unique.relative_to(second)).read_bytes(), b"ios")

    def test_rejects_conflicting_shard_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            relative = Path("uk/shusek/krwa/example/maven-metadata.xml")
            for shard, content in ((first, "first"), (second, "second")):
                target = shard / relative
                target.parent.mkdir(parents=True)
                target.write_text(content, encoding="utf-8")

            with self.assertRaises(SystemExit):
                repository_merge.merge(root / "output", [first, second])


class FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body


class FakeUploadConnection:
    def __init__(self, response: FakeResponse) -> None:
        self.response = response
        self.request: tuple[str, str] | None = None
        self.headers: dict[str, str] = {}
        self.sent = bytearray()
        self.closed = False

    def putrequest(self, method: str, path: str) -> None:
        self.request = (method, path)

    def putheader(self, name: str, value: str) -> None:
        self.headers[name] = value

    def endheaders(self) -> None:
        pass

    def send(self, value: bytes) -> None:
        self.sent.extend(value)

    def getresponse(self) -> FakeResponse:
        return self.response

    def close(self) -> None:
        self.closed = True


class FakeStatusConnection:
    def __init__(self, response: FakeResponse) -> None:
        self.response = response
        self.request_arguments: tuple[
            str, str, bytes, dict[str, str]
        ] | None = None
        self.closed = False

    def request(
        self,
        method: str,
        path: str,
        body: bytes,
        headers: dict[str, str],
    ) -> None:
        self.request_arguments = (method, path, body, headers)

    def getresponse(self) -> FakeResponse:
        return self.response

    def close(self) -> None:
        self.closed = True


class PortalTests(unittest.TestCase):
    deployment_id = "28570f16-da32-4c14-bd2e-c1acc0782365"

    def test_reads_credentials_from_private_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            username = root / "username"
            password = root / "password"
            username.write_text("example-user", encoding="utf-8")
            password.write_text("example-password", encoding="utf-8")
            username.chmod(0o600)
            password.chmod(0o600)

            header = portal.authorization_header(username, password)

            self.assertEqual(
                header,
                "Bearer ZXhhbXBsZS11c2VyOmV4YW1wbGUtcGFzc3dvcmQ=",
            )

    def test_streams_automatic_multipart_upload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle_file = Path(temporary) / "kendive-0.3.0-rc.2-central.zip"
            bundle_file.write_bytes(b"bundle-content")
            connection = FakeUploadConnection(
                FakeResponse(201, self.deployment_id.encode("ascii"))
            )

            with mock.patch.object(
                portal,
                "open_connection",
                return_value=connection,
            ):
                deployment_id = portal.upload_bundle(
                    bundle_file,
                    "Kendive 0.3.0-rc.2",
                    "Bearer example",
                )

            self.assertEqual(deployment_id, self.deployment_id)
            self.assertEqual(connection.request[0], "POST")
            self.assertIn("publishingType=AUTOMATIC", connection.request[1])
            self.assertEqual(connection.headers["Authorization"], "Bearer example")
            self.assertEqual(
                len(connection.sent),
                int(connection.headers["Content-Length"]),
            )
            self.assertIn(
                b'name="bundle"; filename="kendive-0.3.0-rc.2-central.zip"',
                connection.sent,
            )
            self.assertTrue(connection.closed)

    def test_accepts_published_status_document(self) -> None:
        document = {
            "deploymentId": self.deployment_id,
            "deploymentName": "Kendive 0.3.0-rc.2",
            "deploymentState": "PUBLISHED",
            "purls": ["pkg:maven/uk.shusek.krwa/example@0.3.0-rc.2"],
        }
        connection = FakeStatusConnection(
            FakeResponse(200, json.dumps(document).encode("utf-8"))
        )

        with mock.patch.object(
            portal,
            "open_connection",
            return_value=connection,
        ):
            status = portal.request_status(self.deployment_id, "Bearer example")

        self.assertEqual(status, document)
        self.assertEqual(connection.request_arguments[0], "POST")
        self.assertIn(self.deployment_id, connection.request_arguments[1])
        self.assertEqual(
            connection.request_arguments[3]["Authorization"],
            "Bearer example",
        )
        self.assertTrue(connection.closed)


if __name__ == "__main__":
    unittest.main()
