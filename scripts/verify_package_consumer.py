#!/usr/bin/env python3
"""Fail-closed policy for the private User Profile Maven package consumer."""

from __future__ import annotations

import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


MAVEN = {"m": "http://maven.apache.org/POM/4.0.0"}
SETTINGS = {"s": "http://maven.apache.org/SETTINGS/1.2.0"}
GROUP_ID = "com.jobseekercopilot.clients"
ARTIFACT_ID = "user-profile-service-client"
VERSION = "1.0.0-rev.86c8510ed319"
SERVER_ID = "github-user-profile"
REGISTRY = "https://maven.pkg.github.com/jobseekercopilot/user-profile-service"


def _text(root: ET.Element, path: str, namespace: dict[str, str]) -> str:
    element = root.find(path, namespace)
    if element is None or not element.text:
        raise ValueError(f"missing XML value: {path}")
    return element.text.strip()


def _find_dependency(pom: ET.Element) -> ET.Element:
    for dependency in pom.findall("m:dependencies/m:dependency", MAVEN):
        if (
            _text(dependency, "m:groupId", MAVEN) == GROUP_ID
            and _text(dependency, "m:artifactId", MAVEN) == ARTIFACT_ID
        ):
            return dependency
    raise ValueError("POM is missing the published User Profile client dependency")


def verify(root: Path, check_git: bool = True) -> None:
    pom = ET.parse(root / "pom.xml").getroot()
    dependency = _find_dependency(pom)
    if _text(dependency, "m:version", MAVEN) != "${user-profile-client.version}":
        raise ValueError("User Profile dependency must use the reviewed version property")
    if (
        _text(pom, "m:properties/m:user-profile-client.version", MAVEN)
        != VERSION
    ):
        raise ValueError("User Profile package version is not the reviewed immutable pin")

    repositories = pom.findall("m:repositories/m:repository", MAVEN)
    if not any(
        _text(repository, "m:id", MAVEN) == SERVER_ID
        and _text(repository, "m:url", MAVEN) == REGISTRY
        for repository in repositories
    ):
        raise ValueError("POM is missing the exact User Profile package registry")

    execution_ids = {
        _text(execution, "m:id", MAVEN)
        for execution in pom.findall(
            "m:build/m:plugins/m:plugin/m:executions/m:execution", MAVEN
        )
    }
    if "generate-user-profile-client" in execution_ids:
        raise ValueError("User Profile client is still generated inside the consumer")

    settings = ET.parse(root / ".mvn" / "github-packages-settings.xml").getroot()
    server = settings.find("s:servers/s:server", SETTINGS)
    if server is None:
        raise ValueError("Maven settings are missing the GitHub Packages server")
    if _text(server, "s:id", SETTINGS) != SERVER_ID:
        raise ValueError("Maven settings server ID does not match the POM repository")
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
        description="Verify the private User Profile package consumer policy."
    )
    parser.add_argument("root", nargs="?", type=Path, default=Path(__file__).parent.parent)
    parser.add_argument("--skip-git", action="store_true")
    args = parser.parse_args()

    try:
        verify(args.root.resolve(), check_git=not args.skip_git)
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"Package consumer policy failed: {error}", file=sys.stderr)
        return 1

    print(f"Package consumer policy passed: {GROUP_ID}:{ARTIFACT_ID}:{VERSION}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
