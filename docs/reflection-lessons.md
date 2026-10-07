# Reflection lesson review

`rei.reflection.lessons-enabled` / `REI_REFLECTION_LESSONS_ENABLED` is false by default.
The existing independently verified `PROJECT_STATE` promotion remains available and unchanged.
Lessons are a separate SQLite review ledger: they are never automatically inserted into long-term memory
or supplied to a model as established knowledge. Human reviewers supply the proposed generalization.
A validated lesson is a reviewed recommendation within its recorded scope, not proof of universal validity.

## Human Shell operations

All operations use the currently selected Project, canonical root and Session. They are not model Tools.

```text
/reflection lesson-observe REFLECTION_ID --source-kind RUN
/reflection lesson-observe REFLECTION_ID --source-kind GOAL
/reflection lesson-propose --kind FAILURE_PATTERN --note "Proposed scoped recommendation" --evidence OBSERVATION_1,OBSERVATION_2,OBSERVATION_3
/reflection lessons
/reflection lesson LESSON_ID
/reflection lesson-validate LESSON_ID --revision 0 --evidence-hash SHA256 --note "Counterexamples and scope reviewed"
/reflection lesson-counterexample LESSON_ID --revision 1 --observation OBSERVATION_ID --note "Observed exception"
/reflection lesson-correct LESSON_ID --revision 2 --note "User correction"
/reflection lesson-forget LESSON_ID --revision 3
/reflection lesson-observe-correction STABLE_CORRECTION_ID --observation OBSERVATION_ID --note "Actual human correction"
```

Kinds are `FAILURE_PATTERN`, `SUCCESSFUL_STRATEGY`, `REPEATED_CORRECTION`.
Stable correction identities deduplicate human corrections; changed content cannot replace the saved source.
Three corrections concerning the same execution do not become three independent occurrences.
`--revision` and `--evidence-hash` require review of the current saved receipt.

## Evidence and states

`OBSERVATION` preserves the expected outcome, actual recorded status/gap, source identity, snapshot hash,
time and completeness. Run observations record declared expectations where available; undeclared Run
criteria remain explicitly undeclared. Failure patterns reflect recorded failures, without inferring causes.
Success strategies require completed Goal criteria, matching source ownership and current independent
file verification. A reported Run completion cannot establish successful strategy evidence.

`CANDIDATE_LESSON` references 1–16 distinct observations. `VALIDATED_LESSON` requires at least three
distinct execution/Goal origins, supporting evidence for the chosen kind, complete records, freshness
within 30 days, unchanged source snapshots and an explicit counterexample review. Future timestamps,
known counterexamples, user corrections, stale revisions and cancellation prevent validation.
Current independent verification is repeated before promoting successful strategies.

Counterexamples and user corrections produce `REJECTED`; they do not silently rewrite a validated lesson.
Forget records a durable normalized statement tombstone. Equivalent proposals, including already saved
candidates and proposals with new sources after restart, cannot be validated or regenerated in that scope.
This ledger does not fabricate conversation turns or modify the existing memory forgetting mechanism.

Limits: 4,096 observations, 1,024 lessons, 8 MiB of documents per ledger, 64 KiB per record,
2,048 characters per statement/note, 16 counterexamples/corrections, 128 returned list entries.
Credentials are redacted from human notes and source details. Database updates use revision compare-and-set;
forgetting and its tombstone are committed together. No external model or service is invoked.

The test suite uses real SQLite restart, source events, independently verified temporary files,
changed-file rejection, explicit Shell commands, redaction, scope, revision and forgetting checks.
Semantic quality beyond these gates remains a human review decision.
