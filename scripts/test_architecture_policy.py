#!/usr/bin/env python3
"""Negative tests for the DOCGEN-01 architecture policy."""

from __future__ import annotations

import copy
import unittest

import verify_architecture as policy


class ArchitecturePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ownership = policy.load_json(policy.OWNERSHIP_PATH)
        self.trace = policy.load_json(policy.TRACE_PATH)

    def test_rejects_canonical_decision_drift(self) -> None:
        candidate = copy.deepcopy(self.ownership)
        candidate["decision"]["revision"] = "0" * 40

        with self.assertRaisesRegex(AssertionError, "must remain pinned"):
            policy.verify_decision(candidate)

    def test_rejects_duplicate_state_ownership(self) -> None:
        candidate = copy.deepcopy(self.ownership)
        candidate["states"].append(copy.deepcopy(candidate["states"][0]))

        with self.assertRaisesRegex(AssertionError, "Duplicate state ownership"):
            policy.state_owner_index(candidate)

    def test_rejects_incomplete_source_baseline(self) -> None:
        candidate = copy.deepcopy(self.ownership)
        candidate["baseline"].pop()

        with self.assertRaisesRegex(AssertionError, "complete reviewed flow"):
            policy.verify_decision(candidate)

    def test_rejects_build_tools_as_a_runtime_repository(self) -> None:
        candidate = copy.deepcopy(self.ownership)
        build_tools = next(
            item for item in candidate["repositories"]
            if item["component"] == "Build Tools"
        )
        build_tools["runtime"] = True

        with self.assertRaisesRegex(AssertionError, "Unexpected repository decision"):
            policy.verify_repository_decisions(candidate)

    def test_rejects_state_change_by_a_non_owner(self) -> None:
        candidate = copy.deepcopy(self.trace)
        draft_event = next(
            event for event in candidate["events"]
            if event["resource"] == "document-version" and event["to"] == "DRAFT"
        )
        draft_event["actor"] = "jobseekercopilot/document-generation-gateway"
        owners = policy.state_owner_index(self.ownership)

        with self.assertRaisesRegex(AssertionError, "must be recorded by owner"):
            policy.verify_trace(self.ownership, candidate, owners)


if __name__ == "__main__":
    unittest.main()
