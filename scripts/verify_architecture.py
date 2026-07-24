#!/usr/bin/env python3
"""Verify the DOCGEN-01 ownership decision and deterministic synthetic trace."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
OWNERSHIP_PATH = ROOT / "docs/architecture/document-generation-ownership.json"
TRACE_PATH = ROOT / "docs/architecture/synthetic-document-generation-trace.json"

EXPECTED_DECISION = {
    "repository": "jobseekercopilot/document-store-service",
    "revision": "fedcdbdec63795269c4e4c4f43fc32f38c6327b1",
    "path": "docs/adr/0001-document-architecture-and-ownership.md",
}
REQUIRED_EVIDENCE_KINDS = {"source", "openapi", "compose", "synthetic-trace"}
REQUIRED_BASELINE_REPOSITORIES = {
    "jobseekercopilot/document-generation-gateway",
    "jobseekercopilot/cv-cover-letter-service",
    "jobseekercopilot/document-store-service",
    "jobseekercopilot/document-export-service",
    "jobseekercopilot/application-tracker-service",
    "jobseekercopilot/payment-service",
    "jobseekercopilot/llm-gateway",
    "jobseekercopilot/user-profile-service",
    "jobseekercopilot/job-service",
    "jobseekercopilot/job-seeker-copilot-client",
    "jobseekercopilot/infrastructure",
}
REQUIRED_REPOSITORY_DECISIONS = {
    "Application Tracker": ("jobseekercopilot/application-tracker-service", True),
    "Build Tools": ("jobseekercopilot/infrastructure", False),
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise AssertionError(f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def state_owner_index(ownership: dict[str, Any]) -> dict[tuple[str, str], str]:
    index: dict[tuple[str, str], str] = {}
    for state in ownership.get("states", []):
        resource = state.get("resource")
        name = state.get("state")
        owner = state.get("owner")
        if not all(isinstance(value, str) and value.strip() for value in (resource, name, owner)):
            raise AssertionError("Every architecture state must have one nonblank string owner")
        key = (resource, name)
        if key in index:
            raise AssertionError(f"Duplicate state ownership entry: {resource}/{name}")
        index[key] = owner
    return index


def verify_decision(ownership: dict[str, Any]) -> None:
    decision = ownership.get("decision", {})
    for field, expected in EXPECTED_DECISION.items():
        if decision.get(field) != expected:
            raise AssertionError(f"Canonical decision {field} must remain pinned to {expected}")
    if decision.get("status") != "accepted":
        raise AssertionError("The canonical architecture decision must be accepted")
    if EXPECTED_DECISION["revision"] not in decision.get("url", ""):
        raise AssertionError("Canonical architecture URL must be immutable")

    evidence_kinds = set(ownership.get("evidenceKinds", []))
    if evidence_kinds != REQUIRED_EVIDENCE_KINDS:
        raise AssertionError(
            f"Evidence kinds must be exactly {sorted(REQUIRED_EVIDENCE_KINDS)}"
        )
    baseline = ownership.get("baseline", [])
    repositories = [item.get("repository") for item in baseline]
    if len(repositories) != len(set(repositories)):
        raise AssertionError("Architecture baseline repositories must be unique")
    if set(repositories) != REQUIRED_BASELINE_REPOSITORIES:
        raise AssertionError("Architecture baseline must cover the complete reviewed flow")
    for item in baseline:
        if not re.fullmatch(r"[0-9a-f]{40}", item.get("revision", "")):
            raise AssertionError(f"Invalid baseline revision for {item.get('repository')}")
        evidence = item.get("evidence")
        if not isinstance(evidence, list) or not evidence or not all(
            isinstance(path, str) and path.strip() for path in evidence
        ):
            raise AssertionError(
                f"Missing evidence paths for {item.get('repository')}"
            )


def verify_repository_decisions(ownership: dict[str, Any]) -> None:
    decisions = {
        item.get("component"): item for item in ownership.get("repositories", [])
    }
    for component, (repository, runtime) in REQUIRED_REPOSITORY_DECISIONS.items():
        item = decisions.get(component)
        if item is None:
            raise AssertionError(f"Missing repository decision for {component}")
        if item.get("repository") != repository or item.get("runtime") is not runtime:
            raise AssertionError(f"Unexpected repository decision for {component}")
        if not isinstance(item.get("decision"), str) or not item["decision"].strip():
            raise AssertionError(f"Repository decision for {component} must be explained")


def verify_required_lifecycle_owners(
    ownership: dict[str, Any], owners: dict[tuple[str, str], str]
) -> None:
    required = ownership.get("requiredLifecycleOwners", {})
    expected_resources = {
        "DRAFT": "document-version",
        "APPROVED": "document-version",
        "FINAL": "document-version",
        "EXPORTED": "document-binary",
        "APPLICATION_LINKED": "application-document-reference",
    }
    if set(required) != set(expected_resources):
        raise AssertionError("The required lifecycle owner set changed")
    for state, resource in expected_resources.items():
        owner = owners.get((resource, state))
        if owner is None or required[state] != owner:
            raise AssertionError(f"{state} must have exactly one matching state owner")
    unknown_owners = set(owners.values()) - REQUIRED_BASELINE_REPOSITORIES
    if unknown_owners:
        raise AssertionError(f"State owners missing from baseline: {sorted(unknown_owners)}")


def transition_set(ownership: dict[str, Any], resource: str) -> set[tuple[Any, str]]:
    transitions = ownership.get("allowedTransitions", {}).get(resource)
    if not isinstance(transitions, list) or not transitions:
        raise AssertionError(f"Missing allowed transitions for {resource}")
    result: set[tuple[Any, str]] = set()
    for transition in transitions:
        if not isinstance(transition, list) or len(transition) != 2:
            raise AssertionError(f"Invalid transition declaration for {resource}")
        result.add((transition[0], transition[1]))
    return result


def verify_trace(
    ownership: dict[str, Any],
    trace: dict[str, Any],
    owners: dict[tuple[str, str], str],
) -> None:
    if trace.get("synthetic") is not True or trace.get("containsPersonalData") is not False:
        raise AssertionError("Architecture trace must be synthetic and contain no personal data")

    events = trace.get("events", [])
    sequences = [event.get("sequence") for event in events]
    if sequences != list(range(1, len(events) + 1)):
        raise AssertionError("Synthetic trace sequence numbers must be contiguous")

    instances: dict[str, str] = {}
    seen_states: set[tuple[str, str]] = set()
    for event in events:
        resource = event.get("resource")
        instance = event.get("instance")
        before = event.get("from")
        after = event.get("to")
        actor = event.get("actor")
        if not all(isinstance(value, str) and value.strip() for value in (resource, instance, after, actor)):
            raise AssertionError("Each trace event requires resource, instance, target state and actor")
        current = instances.get(instance)
        if current != before:
            raise AssertionError(
                f"Trace instance {instance} expected {current!r}, event declared {before!r}"
            )
        if (before, after) not in transition_set(ownership, resource):
            raise AssertionError(f"Disallowed transition for {resource}: {before!r} -> {after}")
        owner = owners.get((resource, after))
        if owner != actor:
            raise AssertionError(
                f"{resource}/{after} must be recorded by owner {owner}, not {actor}"
            )
        instances[instance] = after
        seen_states.add((resource, after))

    for state, resource in {
        "DRAFT": "document-version",
        "APPROVED": "document-version",
        "FINAL": "document-version",
        "EXPORTED": "document-binary",
        "APPLICATION_LINKED": "application-document-reference",
    }.items():
        if (resource, state) not in seen_states:
            raise AssertionError(f"Synthetic trace does not exercise {state}")
    if ("generation-operation", "COMPLETED") not in seen_states:
        raise AssertionError("Synthetic trace must complete the generation operation")


def main() -> None:
    ownership = load_json(OWNERSHIP_PATH)
    trace = load_json(TRACE_PATH)
    verify_decision(ownership)
    verify_repository_decisions(ownership)
    owners = state_owner_index(ownership)
    verify_required_lifecycle_owners(ownership, owners)
    verify_trace(ownership, trace, owners)
    print(
        "Architecture verification passed: canonical decision, 5 lifecycle owners "
        f"and {len(trace['events'])} synthetic events."
    )


if __name__ == "__main__":
    main()
