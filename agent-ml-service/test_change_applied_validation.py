"""
Fast, no-network regression test for _validate_and_normalize_itinerary's
"changeApplied" handling (agent-ml-service/agent/orchestrator.py). Pure
function, no LLM/API calls - covers the bug where a missing changeApplied
on a modify_itinerary call used to pass validation silently instead of
triggering a retry.

Run: python test_change_applied_validation.py
"""
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


def test_change_applied_accepted_when_present_and_boolean_for_modification():
    existing = {"day1": dict(BASE_DAY)}
    result = _validate_and_normalize_itinerary(
        _itinerary(changeApplied=False), gathered={}, expected_num_days=1, existing_itinerary=existing
    )
    assert result["changeApplied"] is False


def main():
    tests = [
        test_change_applied_optional_for_generation,
        test_change_applied_wrong_type_rejected_for_generation,
        test_change_applied_required_for_modification,
        test_change_applied_accepted_when_present_and_boolean_for_modification,
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
