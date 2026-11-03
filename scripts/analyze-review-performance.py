"""Summarize selected review runs without reading source or conversation bodies."""
import argparse
import collections
import json
import re
import statistics


def summarize(events, review_lines, runs):
    started, completed, failed, first_responses = {}, {}, {}, {}
    for line in events:
        try:
            event = json.loads(line)
        except (ValueError, TypeError):
            continue
        if event.get("runId") not in runs:
            continue
        payload = event.get("payload") or {}
        key = (event.get("runId"), payload.get("requestId"))
        if not key[1]:
            continue
        kind = event.get("type")
        kind = {"LLM_REQUEST_STARTED": "llm.request.started",
                "LLM_RESPONSE_COMPLETED": "llm.response.completed",
                "LLM_REQUEST_FAILED": "llm.request.failed",
                "LLM_RESPONSE_FIRST_TOKEN": "llm.response.first_token"}.get(kind, kind)
        if kind == "llm.request.started":
            started[key] = payload
        elif kind == "llm.response.completed":
            completed[key] = payload
        elif kind == "llm.request.failed":
            failed[key] = payload
        elif kind == "llm.response.first_token":
            first_responses[key] = payload.get("durationMs")

    # Older logs already have a first-chunk event but no field on completion.
    for key, payload in completed.items():
        if payload.get("firstResponseMillis") is None and first_responses.get(key) is not None:
            payload["firstResponseMillis"] = first_responses[key]

    reads, accepted, rejected = [], {}, []
    for line in review_lines:
        match = re.search(r"\breview_(read|submit)\s+(.*)", line)
        if not match:
            continue
        fields = dict(re.findall(r"(\w+)=([^\s]+)", match[2]))
        if fields.get("run") not in runs:
            continue
        if match[1] == "read":
            reads.append(fields)
        elif fields.get("outcome") == "accepted":
            accepted[fields.get("work")] = fields  # idempotent replay is not new coverage
        elif fields.get("outcome") == "rejected":
            rejected.append(fields)

    def numbers(field):
        return [p[field] for p in completed.values() if isinstance(p.get(field), (int, float))]

    durations = numbers("durationMs")
    first = numbers("firstResponseMillis")
    unique_reads = {p.get("work"): p for p in reads}
    reviewed_chars = sum(int(p.get("chars", 0)) for p in accepted.values())
    return {
        "runs": sorted(runs),
        "llmStarted": len(started), "llmCompleted": len(completed), "llmFailed": len(failed),
        "llmDurationSumMs": sum(durations),
        "llmDurationMedianMs": statistics.median(durations) if durations else None,
        "firstResponseMedianMs": statistics.median(first) if first else None,
        "streamAfterFirstResponseSumMs": sum(
            max(0, p["durationMs"] - p["firstResponseMillis"])
            for p in completed.values()
            if p.get("firstResponseMillis") is not None and p.get("durationMs") is not None),
        "promptTokensReported": sum(numbers("promptTokens")),
        "completionTokensReported": sum(numbers("completionTokens")),
        "requestsWithPromptUsage": len(numbers("promptTokens")),
        "requestsWithCompletionUsage": len(numbers("completionTokens")),
        "reviewReadCalls": len(reads), "uniqueReadUnits": len(unique_reads),
        "reviewReadCharsIncludingRereads": sum(int(p.get("chars", 0)) for p in reads),
        "reviewUniqueReadChars": sum(int(p.get("chars", 0)) for p in unique_reads.values()),
        "reviewedUniqueUnits": len(accepted), "reviewedChars": reviewed_chars,
        "reviewRejectedSubmissions": len(rejected),
        "rejectionReasons": dict(collections.Counter(p.get("reason") for p in rejected)),
        "llmRequestsPer10kReviewedChars": len(started) * 10000 / reviewed_chars if reviewed_chars else None,
        "notes": [
            "Duration sums are request work, not wall time; concurrent requests may overlap.",
            "First response measures client-observed first chunk, including empty/metadata chunks; it does not separate server queue and prefill.",
            "Missing usage is unknown, not zero; compare usage-reporting counts with completed requests.",
            "Review unit metrics require the new rei.review.metrics log lines; old logs may lack them.",
            "Unique accepted work IDs deduplicate retries and resumed runs; source UTF-16 chars are not tokens.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", required=True, help="Profile/event JSONL log")
    parser.add_argument("--review-log", required=True, help="Rei application log")
    parser.add_argument("--run", action="append", required=True, help="Run ID; repeat to include resumes")
    args = parser.parse_args()
    with open(args.events, encoding="utf-8-sig") as events, open(args.review_log, encoding="utf-8-sig") as reviews:
        print(json.dumps(summarize(events, reviews, set(args.run)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
