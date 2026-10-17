"""Deterministic median/MAD comparisons; AI is never the source of metrics."""
from statistics import median


def robust_baseline(values: list[float | None], minimum: int = 7) -> dict:
    observed = [float(value) for value in values if value is not None]
    count = len(observed)
    if count < minimum:
        return {"category": "insufficient_data", "baseline": None, "mad": None,
                "count": count, "confidence": "insufficient",
                "uncertainty": f"At least {minimum} valid historical days are required; {count} are available."}
    center = median(observed)
    mad = median([abs(value - center) for value in observed])
    return {"category": "typical", "baseline": center, "mad": mad, "count": count,
            "confidence": "provisional" if count < 14 else "stronger",
            "uncertainty": "Provisional baseline from 7–13 valid days." if count < 14 else "Baseline uses at least 14 valid days."}


def compare(observed: float | None, history: list[float | None], label: str) -> dict:
    baseline = robust_baseline(history)
    result = {**baseline, "metric": label, "observed": observed, "deviation": None}
    if observed is None:
        result.update(category="insufficient_data", uncertainty="No observed value was provided.")
    elif baseline["baseline"] is not None:
        deviation = float(observed) - baseline["baseline"]
        result["deviation"] = deviation
        # A zero MAD falls back to 20% of the median, or one unit at very small scales.
        scale_threshold = max(3 * (baseline["mad"] or 0), abs(baseline["baseline"]) * 0.2, 1)
        review_threshold = max(5 * (baseline["mad"] or 0), abs(baseline["baseline"]) * 0.5, 2)
        if abs(deviation) >= review_threshold:
            result["category"] = "worth_reviewing"
        elif abs(deviation) >= scale_threshold:
            result["category"] = "changed"
        else:
            result["category"] = "typical"
    return result


def build_insights(records: list[dict], metric: str, today: str) -> list[dict]:
    ordered = sorted(records, key=lambda row: row["local_date"])
    target = next((row for row in ordered if row["local_date"] == today), None)
    history = [row.get(metric) for row in ordered if row["local_date"] < today]
    result = compare(target.get(metric) if target else None, history, metric)
    result.update(date=today, evidence=[row for row in ordered if row["local_date"] == today],
                  source="deterministic", explanation="" if result["baseline"] is None else
                  "A descriptive single-metric comparison; it does not establish a cause.", recommendation=None)
    return [result]
