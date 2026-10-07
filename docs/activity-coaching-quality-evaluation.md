# Activity / Coaching quality evaluation

`ActivityQualityEvaluation.evaluate` runs anonymous labelled inputs through the existing
ActivityRolePolicy, ActivityPeriodAnalysis and PeriodCoaching. No activity collection,
vision/model call, user settings write or notification is performed. Runtime policies
and their defaults remain unchanged; no configuration or DB schema is added.

Fixtures cover expected project/theme, unknown, ambiguous foreground, low confidence
and a day with only one observed minute. A completed week provides the coaching
comparison boundary; unobserved seconds stay unknown rather than becoming idle/work.
Reports contain case classifications, project/theme accuracy, estimated observed
coverage, coaching decision/reason and structural violations. Expected labels are
manual evaluation labels, not independently verified facts. `truthVerified=false`.

Facts retain explicit origin: foreground process/title OBSERVED; project/theme and
observation-duration estimate INFERRED; only explicit fixture confirmations with a
nonblank confirmation reference USER_CONFIRMED. Confirmation inputs are dataset
annotations, not a runtime authentication or verification mechanism. They do not
change the classifier or promote its guesses into facts.

Coaching checks its structured decision/reason and bounded share, and rejects advice
expectations inconsistent with low coverage, insufficient observed time or unresolved
classification. It does not compare golden wording or infer productivity, concentration,
intent, task completion or activities during observation gaps. Target category/share,
coverage and prior anonymous baseline are explicit evaluation criteria in the harness.

Limits:128 unique cases,16 candidates/confirmations per case, one-day duration per
record, bounded text fields and a five-second evaluation deadline checked between cases.
Cancellation aborts the report. The fixture exercises candidate projection and coaching;
it does not measure end-to-end vision model quality or personalized inference accuracy.

```sh
./mvnw -Pfull -Dtest=ActivityQualityEvaluationTest,ActivitySemanticTest,PeriodCoachingTest,AutomaticPeriodCoachingTest test
```

Output: `target/evaluation/activity-coaching-quality.json`. Supply additional anonymous,
permitted manually labelled cases through the same explicit API to expand evaluation.
