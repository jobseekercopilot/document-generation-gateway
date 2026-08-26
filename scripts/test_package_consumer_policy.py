#!/usr/bin/env python3
"""Negative tests for package credential and provenance policy."""

from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path

from verify_package_consumer import verify


ROOT = Path(__file__).resolve().parent.parent
POLICY_FILES = (
    "pom.xml",
    "Dockerfile",
    ".mvn/github-packages-settings.xml",
    ".github/workflows/ci.yml",
    "scripts/build-container.sh",
)


class PackageConsumerPolicyTests(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory, Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in POLICY_FILES:
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def test_repository_policy_passes(self) -> None:
        verify(ROOT)

    def test_hardcoded_maven_token_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        settings = root / ".mvn" / "github-packages-settings.xml"
        settings.write_text(
            settings.read_text().replace(
                "${env.JSC_PACKAGE_READ_TOKEN}", "hardcoded-token"
            )
        )
        with self.assertRaisesRegex(ValueError, "only from the environment"):
            verify(root, check_git=False)

    def test_docker_build_argument_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        dockerfile = root / "Dockerfile"
        dockerfile.write_text(
            dockerfile.read_text() + "\nARG JSC_PACKAGE_READ_TOKEN\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "must not persist"):
            verify(root, check_git=False)

    def test_static_ci_package_token_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        workflow = root / ".github" / "workflows" / "ci.yml"
        workflow.write_text(
            workflow.read_text().replace(
                "JSC_PACKAGE_READ_TOKEN: ${{ github.token }}",
                "JSC_PACKAGE_READ_TOKEN: ${{ secrets.JSC_PACKAGE_READ_TOKEN }}",
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "short-lived repository token"):
            verify(root, check_git=False)

    def inject_generator(self, root: Path, generator_id: str) -> None:
        pom = root / "pom.xml"
        pom.write_text(
            pom.read_text().replace(
                "</plugins>",
                (
                    "<plugin><groupId>org.openapitools</groupId>"
                    "<artifactId>openapi-generator-maven-plugin</artifactId>"
                    "<executions><execution>"
                    f"<id>{generator_id}</id>"
                    "</execution></executions></plugin></plugins>"
                ),
                1,
            ),
            encoding="utf-8",
        )

    def test_user_profile_in_consumer_generation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        self.inject_generator(root, "generate-user-profile-client")
        with self.assertRaisesRegex(ValueError, "still generated"):
            verify(root, check_git=False)

    def test_cv_in_consumer_generation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        self.inject_generator(root, "generate-cv-cover-letter-client")
        with self.assertRaisesRegex(ValueError, "still generated"):
            verify(root, check_git=False)

    def test_cv_package_version_drift_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        pom = root / "pom.xml"
        pom.write_text(
            pom.read_text().replace(
                "4.2.0-rev.3129864cca5c", "4.2.0-rev.000000000000"
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "reviewed immutable pin"):
            verify(root, check_git=False)

    def test_user_profile_package_version_drift_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        pom = root / "pom.xml"
        pom.write_text(
            pom.read_text().replace(
                "2.3.0-rev.a880add6e5c7", "2.3.0-rev.000000000000"
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "reviewed immutable pin"):
            verify(root, check_git=False)

    def test_document_export_in_consumer_generation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        self.inject_generator(root, "generate-document-export-client")
        with self.assertRaisesRegex(ValueError, "still generated"):
            verify(root, check_git=False)

    def test_document_export_package_version_drift_is_rejected(self) -> None:
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        pom = root / "pom.xml"
        pom.write_text(
            pom.read_text().replace(
                "3.1.0-rev.cda9a2af811b", "3.1.0-rev.000000000000"
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "reviewed immutable pin"):
            verify(root, check_git=False)


if __name__ == "__main__":
    unittest.main()
