# Coverage / Test Impact

`changeTestImpact` keeps structural reverse dependencies and test candidates and adds a separate
`coverage` observation. Optional `coverageReports` selects up to eight project-relative JaCoCo XML,
Cobertura XML or LCOV files. Optional `changedLines` contains `{path, first, last}` inclusive ranges;
select at most 64 ranges and 256 distinct lines. Ranges must belong to `changedFiles`.
When changedFiles and changedLines are omitted with coverageReports present, bounded read-only Git
HEAD-to-worktree hunks supply current changed lines. Staged-only, untracked, binary and deleted lines
may lack hunks: this absence never proves coverage. Existing path inventory remains authoritative for
structural candidates. Git paths, hunks, source index and reports are separate observations.

Example arguments:

```json
{"changedFiles":["src/helper.ts"],"changedLines":[{"path":"src/helper.ts","first":4,"last":6}],"coverageReports":["coverage/lcov.info"]}
```

Line status is `REPORTED_COVERED`, `REPORTED_UNCOVERED`, `UNMEASURED` or `STALE_REPORT`.
No report is `NO_REPORTS`; missing, malformed or unsupported reports yield `NO_USABLE_REPORTS`.
Receipt SHA-256 and modification time identify the bytes read. Source SHA identifies the current
index observation. Modification time only rejects obviously old or future reports; it does not prove
the report was generated from that source revision. Coverage is therefore always partial and never
permits omitting broad regression. This tool reads reports and never runs tests or generators.

JaCoCo package/sourcefile must resolve uniquely, with module scope where available. Cobertura
filename and source roots and LCOV SF must resolve within the project to an indexed source; ambiguous,
outside or missing sources are unmapped. LCOV TN contributes a reported test name only when explicitly
present on a positive fresh line. Aggregate JaCoCo/Cobertura reports never invent covering tests.
Credentials and control characters in TN are suppressed. A report cannot establish test success,
branch coverage completeness or current Goal completion.

Reports are strict UTF-8, regular files, at most 2 MiB each, with root/symlink and change checks.
Parsing is bounded by ten seconds, 10,000 rows, 32 XML depth and 32,768 XML elements. External XML
entities and network access are disabled. Standard JaCoCo/Cobertura DOCTYPE declarations are removed
before secure parsing; custom or internal declarations are rejected. Invalid documents retain no
partial coverage facts. Cancellation propagates. READ permission and captured project ownership apply.

Format references: [JaCoCo report DTD](https://github.com/jacoco/jacoco/blob/master/org.jacoco.report/src/org/jacoco/report/xml/report.dtd),
[LCOV tracefile specification](https://github.com/linux-test-project/lcov/blob/master/docs/man/geninfo.rst),
[Cobertura schema used by gcovr](https://github.com/gcovr/gcovr/blob/main/tests/cobertura.coverage-04.dtd).
