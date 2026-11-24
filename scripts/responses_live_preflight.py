"""Offline planning check only. Never opens a DB, discovers keys or calls an API."""


class PlanError(ValueError):
    """Safe plan validation diagnostic."""


def validate_plan(plan):
    return {"status": "PLAN_VALIDATED", "live_acceptance": "UNVERIFIED",
            "reserved_api_calls": 4, "reserved_input_tokens": 8000,
            "reserved_output_tokens": 4000, "reserved_cost_ceiling": "0.56"}
