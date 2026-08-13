"""
Fast, no-network regression test for _validate_and_normalize_itinerary's
"changeApplied" handling (agent-ml-service/agent/orchestrator.py). Pure
function, no LLM/API calls. Covers two bugs: a missing changeApplied on a
modify_itinerary call used to pass validation silently instead of triggering
a retry; and changeApplied=false with an empty warnings list also used to
pass silently, leaving the traveler with no explanation for why nothing
changed (verified live: when it IS present, the Agent's own reason - e.g.
"already the most expensive option available" - is genuinely more specific
than Java's generic "no changes were needed" fallback).

Run (from agent-ml-service/): python manual_tests/test_change_applied_validation.py
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.orchestrator import _validate_and_normalize_itinerary

BASE_DAY = {"date": "2026-09-10"}


def _itinerary(**overrides):
    itinerary = {"day1": dict(BASE_DAY), "totalCost": 100.0, "warnings": []}
    itinerary.update(overrides)
    return itinerary


def test_change_applied_optional_for_generation():
    # existing_itinerary=None => generation flow; absent changeApplied is fine.
    result = _validate_and_normalize_itinerary(_itinerary(), gathered={}, expected_num_days=1)
    assert "changeApplied" not in result


def test_change_applied_wrong_type_rejected_for_generation():
    try:
        _validate_and_normalize_itinerary(_itinerary(changeApplied="yes"), gathered={}, expected_num_days=1)
        raise AssertionError("Expected ValueError for non-bool changeApplied")
    except ValueError:
        pass


def test_change_applied_required_for_modification():
    existing = {"day1": dict(BASE_DAY)}
    try:
        _validate_and_normalize_itinerary(
            _itinerary(), gathered={}, expected_num_days=1, existing_itinerary=existing
        )
        raise AssertionError("Expected ValueError: changeApplied is required when modifying an itinerary")
    except ValueError as exc:
        assert "changeApplied" in str(exc), f"Unexpected error message: {exc}"


def test_change_applied_true_accepted_even_with_empty_warnings_for_modification():
    # changeApplied=true needs no explanation - only the false case (nothing
    # changed) requires one, so the traveler isn't left wondering why.
    existing = {"day1": dict(BASE_DAY)}
    result = _validate_and_normalize_itinerary(
        _itinerary(changeApplied=True), gathered={}, expected_num_days=1, existing_itinerary=existing
    )
    assert result["changeApplied"] is True


def test_change_applied_false_requires_nonempty_warnings():
    # Covers a real gap: changeApplied=false with an empty warnings list used
    # to pass validation silently, so the traveler only ever saw Java's
    # generic "no changes were needed" fallback instead of the Agent's own
    # specific reason (e.g. "already the most expensive option available").
    existing = {"day1": dict(BASE_DAY)}
    try:
        _validate_and_normalize_itinerary(
            _itinerary(changeApplied=False, warnings=[]), gathered={}, expected_num_days=1,
            existing_itinerary=existing,
        )
        raise AssertionError("Expected ValueError: changeApplied=false requires a non-empty warnings explanation")
    except ValueError as exc:
        assert "warnings" in str(exc), f"Unexpected error message: {exc}"


def test_change_applied_false_accepted_when_warnings_explain_why():
    existing = {"day1": dict(BASE_DAY)}
    result = _validate_and_normalize_itinerary(
        _itinerary(changeApplied=False, warnings=["No pricier hotel was found in range."]),
        gathered={}, expected_num_days=1, existing_itinerary=existing,
    )
    assert result["changeApplied"] is False
    assert result["warnings"] == ["No pricier hotel was found in range."]


def main():
    tests = [
        test_change_applied_optional_for_generation,
        test_change_applied_wrong_type_rejected_for_generation,
        test_change_applied_required_for_modification,
        test_change_applied_true_accepted_even_with_empty_warnings_for_modification,
        test_change_applied_false_requires_nonempty_warnings,
        test_change_applied_false_accepted_when_warnings_explain_why,
    ]
    failed = []
    for test in tests:
        try:
            test()
            print(f"PASS: {test.__name__}")
        except AssertionError as exc:
            failed.append(test.__name__)
            print(f"FAIL: {test.__name__}: {exc}")

    if failed:
        raise SystemExit(f"\n{len(failed)} test(s) failed: {failed}")
    print(f"\nAll {len(tests)} tests passed.")


if __name__ == "__main__":
    main()
