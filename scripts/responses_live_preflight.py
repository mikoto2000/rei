"""Offline planning check only. Never opens a DB, discovers keys or calls an API."""

from decimal import Decimal, InvalidOperation, localcontext
import json
from pathlib import Path
import re
import sys


FIELDS = {
    "schema_version", "provider", "base_url", "model", "reasoning_effort",
    "reasoning_replay", "credential_env", "authorization_reference", "data_scope",
    "max_api_calls", "max_input_tokens", "max_output_tokens", "max_seconds",
    "currency", "cost_limit", "input_rate_ceiling_per_million",
    "output_rate_ceiling_per_million", "trials",
}
TRIAL_FIELDS = {
    "id", "repetitions", "max_calls", "input_tokens_per_call", "output_tokens_per_call",
}
ENDPOINTS = {
    "openai": ({"https://api.openai.com/v1"}, "encrypted"),
    "deepseek": ({"https://api.deepseek.com", "https://api.deepseek.com/v1"}, "plaintext"),
}


class PlanError(ValueError):
    """Safe plan validation diagnostic."""


def require(condition):
    if not condition:
        raise PlanError("Live trial plan rejected; check the documented schema and bounds")


def positive_integer(value):
    require(type(value) is int and 0 < value <= 1_000_000_000)
    return value


def money(value):
    # Strings keep JSON floats, NaN, scientific exponent abuse and rounding out of budgets.
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{1,12}(\.[0-9]{1,12})?", value))
    try:
        result = Decimal(value)
    except InvalidOperation:
        raise PlanError("Invalid monetary bound") from None
    require(result.is_finite() and result > 0)
    return result


def validate_plan(plan):
    require(isinstance(plan, dict) and set(plan) == FIELDS)
    require(type(plan["schema_version"]) is int and plan["schema_version"] == 1)
    provider = plan["provider"]
    require(isinstance(provider, str) and provider in ENDPOINTS)
    urls, replay = ENDPOINTS[provider]
    require(isinstance(plan["base_url"], str) and plan["base_url"] in urls)
    require(plan["reasoning_replay"] == replay)
    for field in ("model", "reasoning_effort", "authorization_reference"):
        value = plan[field]
        require(isinstance(value, str) and 0 < len(value) <= 256 and value == value.strip())
        require(all(ord(character) >= 32 for character in value))
    credential = plan["credential_env"]
    require(isinstance(credential, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", credential))
    require(plan["data_scope"] == "synthetic-only" and plan["currency"] == "USD")
    limits = [positive_integer(plan[field]) for field in
              ("max_api_calls", "max_input_tokens", "max_output_tokens", "max_seconds")]
    cost_limit = money(plan["cost_limit"])
    input_rate = money(plan["input_rate_ceiling_per_million"])
    output_rate = money(plan["output_rate_ceiling_per_million"])
    trials = plan["trials"]
    require(isinstance(trials, list) and 0 < len(trials) <= 1000)
    identifiers = set()
    calls = inputs = outputs = 0
    for trial in trials:
        require(isinstance(trial, dict) and set(trial) == TRIAL_FIELDS)
        identifier = trial["id"]
        require(isinstance(identifier, str) and re.fullmatch(r"[A-Z][A-Z0-9-]{0,63}", identifier))
        require(identifier not in identifiers)
        identifiers.add(identifier)
        count = positive_integer(trial["repetitions"]) * positive_integer(trial["max_calls"])
        calls += count
        inputs += count * positive_integer(trial["input_tokens_per_call"])
        outputs += count * positive_integer(trial["output_tokens_per_call"])
    require(calls <= limits[0] and inputs <= limits[1] and outputs <= limits[2])
    with localcontext() as context:
        context.prec = 80
        cost = (inputs * input_rate + outputs * output_rate) / Decimal(1_000_000)
    require(cost <= cost_limit)
    # No supplied text, environment values or credential references appear in the result.
    return {"status": "PLAN_VALIDATED", "live_acceptance": "UNVERIFIED",
            "reserved_api_calls": calls, "reserved_input_tokens": inputs,
            "reserved_output_tokens": outputs, "reserved_cost_ceiling": format(cost, "f")}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def main(arguments):
    if len(arguments) != 1:
        print("Usage: responses_live_preflight.py PLAN.json", file=sys.stderr)
        return 2
    try:
        path = Path(arguments[0])
        require(path.stat().st_size <= 1_048_576)
        plan = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        result = validate_plan(plan)
    except (OSError, ValueError, RecursionError):
        print("Live trial plan rejected; no API request was made", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
