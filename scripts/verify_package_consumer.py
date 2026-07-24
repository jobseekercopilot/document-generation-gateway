#!/usr/bin/env python3
"""Fail-closed policy for producer-owned private Maven package consumers."""

from __future__ import annotations

import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


MAVEN = {"m": "http://maven.apache.org/POM/4.0.0"}
SETTINGS = {"s": "http://maven.apache.org/SETTINGS/1.2.0"}
GROUP_ID = "com.jobseekercopilot.clients"
PACKAGES = (
    {
        "label": "User Profile",
        "artifact_id": "user-profile-service-client",
        "version_property": "user-profile-client.version",
        "version": "1.0.0-rev.86c8510ed319",
        "server_id": "github-user-profile",
        "registry": "https://maven.pkg.github.com/jobseekercopilot/user-profile-service",
        "generator_id": "generate-user-profile-client",
    },
    {
        "label": "CV and Cover Letter",
        "artifact_id": "cv-cover-letter-service-client",
        "version_property": "cv-cover-letter-client.version",
        "version": "2.0.0-rev.87fc2393309a",
        "server_id": "github-cv-cover-letter",
        "registry": "https://maven.pkg.github.com/jobseekercopilot/cv-cover-letter-service",
        "generator_id": "generate-cv-cover-letter-client",
    },
    {
        "label": "Document Export",
        "artifact_id": "document-export-service-client",
        "version_property": "document-export-client.version",
        "version": "2.0.0-rev.a35fff34f86b",
        "server_id": "github-document-export",
        "registry": "https://maven.pkg.github.com/jobseekercopilot/document-export-service",
        "generator_id": "generate-document-export-client",
    },
)


def _text(root: ET.Element, path: str, namespace: dict[str, str]) -> str:
    element = root.find(path, namespace)
    if element is None or not element.text:
        raise ValueError(f"missing XML value: {path}")
    return element.text.strip()


def _find_dependency(pom: ET.Element, artifact_id: str) -> ET.Element:
    for dependency in pom.findall("m:dependencies/m:dependency", MAVEN):
        if (
            _text(dependency, "m:groupId", MAVEN) == GROUP_ID
            and _text(dependency, "m:artifactId", MAVEN) == artifact_id
        ):
            return dependency
    raise ValueError(f"POM is missing the published client dependency: {artifact_id}")


def verify(root: Path, check_git: bool = True) -> None:
    pom = ET.parse(root / "pom.xml").getroot()
    repositories = pom.findall("m:repositories/m:repository", MAVEN)
    execution_ids = {
        _text(execution, "m:id", MAVEN)
        for execution in pom.findall(
            "m:build/m:plugins/m:plugin/m:executions/m:execution", MAVEN
        )
    }

    for package in PACKAGES:
        dependency = _find_dependency(pom, package["artifact_id"])
        version_property = package["version_property"]
        if _text(dependency, "m:version", MAVEN) != f"${{{version_property}}}":
            raise ValueError(
                f"{package['label']} dependency must use the reviewed version property"
            )
        if (
            _text(pom, f"m:properties/m:{version_property}", MAVEN)
            != package["version"]
        ):
            raise ValueError(
                f"{package['label']} package version is not the reviewed immutable pin"
            )
        if not any(
            _text(repository, "m:id", MAVEN) == package["server_id"]
            and _text(repository, "m:url", MAVEN) == package["registry"]
            for repository in repositories
        ):
            raise ValueError(
                f"POM is missing the exact {package['label']} package registry"
            )
        if package["generator_id"] in execution_ids:
            raise ValueError(
                f"{package['label']} client is still generated inside the consumer"
            )

    settings = ET.parse(root / ".mvn" / "github-packages-settings.xml").getroot()
    servers = {
        _text(server, "s:id", SETTINGS): server
        for server in settings.findall("s:servers/s:server", SETTINGS)
    }
    for package in PACKAGES:
        server = servers.get(package["server_id"])
        if server is None:
            raise ValueError(
                f"Maven settings are missing the {package['label']} Packages server"
            )
        if _text(server, "s:username", SETTINGS) != "jobseekercopilot":
            raise ValueError("Maven settings use an unexpected package account")
        if _text(server, "s:password", SETTINGS) != "${env.JSC_PACKAGE_READ_TOKEN}":
            raise ValueError("Maven settings must read the token only from the environment")

    dockerfile = (root / "Dockerfile").read_text(encoding="utf-8")
    required_docker_fragments = (
        "--mount=type=secret,id=maven_settings",
        "--mount=type=secret,id=package_token",
        'JSC_PACKAGE_READ_TOKEN="$(cat /run/secrets/package_token)"',
    )
    for fragment in required_docker_fragments:
        if fragment not in dockerfile:
            raise ValueError(f"Dockerfile is missing package secret handling: {fragment}")
    if "ARG JSC_PACKAGE" in dockerfile or "ENV JSC_PACKAGE" in dockerfile:
        raise ValueError("Dockerfile must not persist the package token as ARG or ENV")

    build_script = (root / "scripts" / "build-container.sh").read_text(encoding="utf-8")
    if (
        "docker buildx build --load" not in build_script
        or "--secret" not in build_script
        or "mktemp" not in build_script
    ):
        raise ValueError("container helper must use an ephemeral BuildKit secret")

    workflow = (root / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    if "secrets.JSC_PACKAGE_READ_TOKEN" not in workflow:
        raise ValueError("CI does not receive the dedicated package-read secret")
    if ".mvn/github-packages-settings.xml" not in workflow:
        raise ValueError("CI Maven build does not use the reviewed settings template")

    if check_git:
        tracked = subprocess.run(
            [
                "git",
                "-C",
                str(root),
                "ls-files",
                "*.jar",
                "*.class",
                "settings-security.xml",
                "*package-token*",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if tracked:
            raise ValueError(f"forbidden package credential or binary is tracked: {tracked}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify private producer-package consumer policy."
    )
    parser.add_argument("root", nargs="?", type=Path, default=Path(__file__).parent.parent)
    parser.add_argument("--skip-git", action="store_true")
    args = parser.parse_args()

    try:
        verify(args.root.resolve(), check_git=not args.skip_git)
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"Package consumer policy failed: {error}", file=sys.stderr)
        return 1

    coordinates = ", ".join(
        f"{GROUP_ID}:{package['artifact_id']}:{package['version']}"
        for package in PACKAGES
    )
    print(f"Package consumer policy passed: {coordinates}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
