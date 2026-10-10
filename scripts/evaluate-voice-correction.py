"""Offline evaluation of explicitly consented, manually annotated three-arm results. No audio/network IO."""
import argparse
import json
import math
from pathlib import Path


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        next_row = [i]
        for j, cb in enumerate(b, 1):
            next_row.append(min(row[j] + 1, next_row[-1] + 1, row[j - 1] + (ca != cb)))
        row = next_row
    return row[-1]


def evaluate(rows):
    if not rows or any(row.get("consent") is not True for row in rows):
        raise ValueError("Every sample must explicitly declare consent=true; empty datasets are not evaluated")
    report = {"samples": len(rows), "methods": {}}
    for method in ("raw", "dictionary", "llm"):
        errors = chars = terms = correct_terms = 0
        rates = {key: 0 for key in ("overcorrection", "intent_changed", "critical_argument_broken", "failed", "timeout")}
        latencies = []
        for row in rows:
            reference = row["reference"]
            result = row["results"][method]
            text = result["text"]
            if not isinstance(reference, str) or not reference or not isinstance(text, str):
                raise ValueError("A nonempty reference and text hypothesis are required")
            errors += distance(reference, text)
            chars += len(reference)
            expected_terms = row.get("terms", [])
            terms += len(expected_terms)
            correct_terms += sum(term in text for term in expected_terms)
            for key in rates:
                if type(result[key]) is not bool:
                    raise ValueError("Every diagnostic/semantic annotation must be a boolean")
                rates[key] += result[key]
            latency = result["additional_ms"]
            if not isinstance(latency, (int, float)) or not math.isfinite(latency) or latency < 0:
                raise ValueError("Latency must be finite and nonnegative")
            latencies.append(latency)
        latencies.sort()
        report["methods"][method] = {
            "cer": errors / chars,
            "term_accuracy": correct_terms / terms if terms else None,
            **{key + "_rate": count / len(rows) for key, count in rates.items()},
            "additional_ms_p50": latencies[math.ceil(len(rows) * .50) - 1],
            "additional_ms_p95": latencies[math.ceil(len(rows) * .95) - 1],
        }
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.dataset.read_text(encoding="utf-8").splitlines() if line.strip()]
    print(json.dumps(evaluate(rows), ensure_ascii=False, indent=2))
