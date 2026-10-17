from app.services.analysis import compare, robust_baseline


def test_mad_baseline_ignores_single_outlier_for_center():
    result = robust_baseline([10, 10, 11, 10, 12, 11, 500])
    assert result["baseline"] == 11
    assert result["mad"] == 1


def test_constant_and_zero_mad_series_have_safe_threshold():
    result = compare(130, [100] * 7, "screen_time")
    assert result["baseline"] == 100
    assert result["mad"] == 0
    assert result["category"] == "changed"


def test_missing_values_are_not_treated_as_zero():
    assert robust_baseline([None, 1, None, 2, None, 3, None])["count"] == 3
    assert compare(None, [1, 2, 3, 4, 5, 6, 7], "mood")["category"] == "insufficient_data"


def test_seven_is_provisional_and_fourteen_is_stronger():
    assert "Provisional" in robust_baseline([1] * 7)["uncertainty"]
    assert "at least 14" in robust_baseline([1] * 14)["uncertainty"]


def test_high_deviation_is_worth_reviewing_and_missing_values_are_ignored():
    result = compare(200, [100] * 7 + [None], "screen_time")
    assert result["category"] == "worth_reviewing"
    assert result["count"] == 7
    assert result["baseline"] == 100
