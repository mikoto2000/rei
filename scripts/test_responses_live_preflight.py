import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import responses_live_preflight as preflight


class ResponsesLivePreflightTest(unittest.TestCase):
    def plan(self):
        return {
            "schema_version": 1,
            "provider": "openai",
            "base_url": "https://api.openai.com/v1",
            "model": "operator-must-select-model",
            "reasoning_effort": "low",
            "reasoning_replay": "encrypted",
            "credential_env": "REI_LIVE_OPENAI_API_KEY",
            "authorization_reference": "fixture-only-not-an-approval",
            "data_scope": "synthetic-only",
            "max_api_calls": 4,
            "max_input_tokens": 8000,
            "max_output_tokens": 4000,
            "max_seconds": 120,
            "currency": "USD",
            "cost_limit": "1.00",
            "input_rate_ceiling_per_million": "20.00",
            "output_rate_ceiling_per_million": "100.00",
            "trials": [{"id": "OA-01", "repetitions": 2, "max_calls": 2,
                        "input_tokens_per_call": 2000, "output_tokens_per_call": 1000}],
        }

    def test_plan_reports_reservations_without_claiming_acceptance(self):
        result = preflight.validate_plan(self.plan())
        self.assertEqual(result["status"], "PLAN_VALIDATED")
        self.assertEqual(result["live_acceptance"], "UNVERIFIED")
        self.assertEqual(result["reserved_api_calls"], 4)
        self.assertEqual(result["reserved_input_tokens"], 8000)
        self.assertEqual(result["reserved_output_tokens"], 4000)
        self.assertEqual(result["reserved_cost_ceiling"], "0.56")

    def test_every_condition_is_required(self):
        for field in self.plan():
            with self.subTest(field=field):
                plan = self.plan()
                del plan[field]
                with self.assertRaises(preflight.PlanError):
                    preflight.validate_plan(plan)

    def test_secrets_and_unreviewed_fields_are_rejected_without_echo(self):
        for field in ("api_key", "headers", "prompt", "extra_body", "production_db"):
            plan = self.plan()
            plan[field] = "PRIVATE_CANARY"
            with self.subTest(field=field):
                with self.assertRaises(preflight.PlanError) as error:
                    preflight.validate_plan(plan)
                self.assertNotIn("PRIVATE_CANARY", str(error.exception))

    def test_destination_and_replay_are_explicit_and_provider_specific(self):
        for url in ("http://api.openai.com/v1", "https://api.openai.com.evil/v1",
                    "https://user:PRIVATE_CANARY@api.openai.com/v1",
                    "https://api.openai.com/v1?key=PRIVATE_CANARY", "https://api.deepseek.com"):
            with self.subTest(url=url):
                plan = self.plan()
                plan["base_url"] = url
                with self.assertRaises(preflight.PlanError):
                    preflight.validate_plan(plan)
        plan = self.plan()
        plan.update(provider="deepseek", base_url="https://api.deepseek.com",
                    reasoning_replay="plaintext")
        self.assertEqual(preflight.validate_plan(plan)["status"], "PLAN_VALIDATED")
        plan["reasoning_replay"] = "encrypted"
        with self.assertRaises(preflight.PlanError):
            preflight.validate_plan(plan)

    def test_budget_cannot_be_negative_nonfinite_boolean_or_fractional_count(self):
        for field in ("max_api_calls", "max_input_tokens", "max_output_tokens", "max_seconds"):
            for value in (0, -1, True, 1.5, "100"):
                with self.subTest(field=field, value=value):
                    plan = self.plan()
                    plan[field] = value
                    with self.assertRaises(preflight.PlanError):
                        preflight.validate_plan(plan)
        for field in ("cost_limit", "input_rate_ceiling_per_million",
                      "output_rate_ceiling_per_million"):
            for value in ("NaN", "Infinity", "-1", "0", True, 1.0):
                with self.subTest(field=field, value=value):
                    plan = self.plan()
                    plan[field] = value
                    with self.assertRaises(preflight.PlanError):
                        preflight.validate_plan(plan)

    def test_aggregate_reservations_must_fit_all_budgets(self):
        for field, value in (("max_api_calls", 3), ("max_input_tokens", 7999),
                             ("max_output_tokens", 3999), ("cost_limit", "0.5599")):
            with self.subTest(field=field):
                plan = self.plan()
                plan[field] = value
                with self.assertRaises(preflight.PlanError):
                    preflight.validate_plan(plan)

    def test_trial_schema_and_trial_identifiers_are_not_implicitly_accepted(self):
        for mutation in (lambda trials: trials.clear(),
                         lambda trials: trials.append(copy.deepcopy(trials[0])),
                         lambda trials: trials[0].update(max_calls=True),
                         lambda trials: trials[0].update(repetitions=0),
                         lambda trials: trials[0].update(prompt="PRIVATE_CANARY")):
            plan = self.plan()
            mutation(plan["trials"])
            with self.assertRaises(preflight.PlanError):
                preflight.validate_plan(plan)

    def test_no_implicit_data_or_approval_or_credential_discovery(self):
        for field, value in (("authorization_reference", ""), ("model", ""),
                             ("reasoning_effort", ""), ("credential_env", "bad-key=value"),
                             ("data_scope", "production"), ("currency", "JPY")):
            with self.subTest(field=field):
                plan = self.plan()
                plan[field] = value
                with self.assertRaises(preflight.PlanError):
                    preflight.validate_plan(plan)

    def test_cli_does_not_echo_malformed_or_duplicate_secret_fields(self):
        script = Path(preflight.__file__).resolve()
        for content in ('{"api_key":"PRIVATE_CANARY"}',
                        '{"model":"a","model":"PRIVATE_CANARY"}', '{PRIVATE_CANARY'):
            with tempfile.TemporaryDirectory() as folder:
                manifest = Path(folder) / "plan.json"
                manifest.write_text(content, encoding="utf-8")
                process = subprocess.run([sys.executable, str(script), str(manifest)],
                                         capture_output=True, text=True, timeout=10)
                self.assertNotEqual(process.returncode, 0)
                self.assertNotIn("PRIVATE_CANARY", process.stdout + process.stderr)
                self.assertNotIn("Traceback", process.stderr)

    def test_cli_is_offline_and_never_requires_a_credential_value(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = Path(folder) / "plan.json"
            manifest.write_text(json.dumps(self.plan()), encoding="utf-8")
            process = subprocess.run([sys.executable, str(Path(preflight.__file__).resolve()),
                                      str(manifest)], capture_output=True, text=True, timeout=10)
            self.assertEqual(process.returncode, 0, process.stderr)
            self.assertEqual(json.loads(process.stdout)["live_acceptance"], "UNVERIFIED")


if __name__ == "__main__":
    unittest.main()
