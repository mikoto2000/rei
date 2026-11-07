# Java static analysis

JDK 25 and the Maven Wrapper (3.9.14) are required. The pinned analyzers are
Checkstyle 14.3.0 (Maven plugin 3.6.0) and SpotBugs 4.10.5 (Maven plugin
4.10.5.0). Ordinary build/test commands retain their existing behavior.

## Local commands

Run from the repository root (PowerShell):

```powershell
# Generate inventories without failing merely because findings exist.
.\mvnw.cmd -B checkstyle:checkstyle
.\mvnw.cmd -B -DskipTests compile spotbugs:spotbugs
.\.github\ci\static-analysis-summary.ps1
.\.github\ci\static-analysis-gate.ps1

# Verify the static analysis gate's regression fixtures.
.\.github\ci\test-static-analysis-gate.ps1

# Strict gates: nonzero exit status if findings exist.
.\mvnw.cmd -B checkstyle:check
.\mvnw.cmd -B -DskipTests compile spotbugs:check

# Existing fast tests plus package and strict gates.
.\mvnw.cmd -B -Pquality verify
# Include existing integration/e2e tests, excluding live external-service tests.
.\mvnw.cmd -B '-Pfull,quality' verify

# Existing CI regression command (with its process deadline).
.\.github\ci\full-test.ps1
```

On Linux/macOS use `./mvnw` for Maven commands. The summary script requires
PowerShell 7. Checkstyle output: `target/checkstyle-result.xml` and
`target/reports/checkstyle.html`. SpotBugs output: `target/spotbugsXml.xml`.
The summaries are `target/static-analysis-summary.md` and
`target/static-analysis-gate.md`. Inventory success means
analysis completed; it does not mean zero findings. A missing/unreadable report
is an error, never a zero count.

## Scope and rules

Checkstyle includes main and test Java sources. Its explicit rules cover wildcard,
unused and redundant imports, one statement per line, equals/hashCode pairing,
switch fall-through, modifier order and outer type filenames. Broad indentation,
Javadoc and naming conventions are deferred because the current codebase uses
multiple styles; introducing them would require unrelated bulk rewrites.
Resource files are excluded because these are Java source rules.

SpotBugs analyzes main bytecode at Max effort and Low threshold. Test bytecode
is outside the initial bug gate: assertion-heavy test fixtures have different
intent from application code. No detector, application class or finding is
suppressed by analyzer configuration. No exclusion filter is used.

## GitHub Actions

`Java static analysis` runs on main pushes and pull requests targeting main,
using Temurin 25 on Windows as the existing regression workflow does. Each tool
runs independently so a Checkstyle failure does not prevent SpotBugs analysis.
The final step fails the job if either analysis or the zero-findings gate failed. `continue-on-error`
on the analysis steps only allows both reports to be collected; it does not
turn the job green. Summary generation, the zero-findings gate and report/log artifact upload use
`always()` and therefore also run after failures. A cancelled job or runner
loss can still prevent upload; compilation failure may leave no SpotBugs report.
Artifacts are retained for 14 days.

Only `contents: read` is requested. Results appear in the Actions job summary and
downloadable artifact, without PR comments, SARIF upload, Security tab integration
or paid code-scanning features. Existing `Java regression` remains separate.

## Initial inventory and policy decision

Measured at main `66bf59be` on 2026-10-14, without changing application sources:

| Analyzer | Findings | Breakdown |
| --- | ---: | --- |
| Checkstyle | 27,015 | OneStatementPerLine 21,904; AvoidStarImport 5,042; UnusedImports 69 |
| SpotBugs | 1,942 | priority 1: 14; priority 2: 1,516; priority 3: 412 |

Largest SpotBugs types: EI_EXPOSE_REP2 840, EI_EXPOSE_REP 308,
NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE 199. These are analyzer findings, not
confirmed defects; Spring/Lombok ownership conventions require review.

The initial rollout used a baseline to tolerate existing findings while they
were being addressed. Both reports reached zero on main in
[the November 6 run](https://github.com/mikoto2000/rei/actions/runs/42305351834).
The CI gate now requires zero findings from both analyzers. Missing or malformed
reports and SpotBugs analysis errors or missing classes also fail the gate.

## Java 25 evidence

- [Checkstyle documentation](https://checkstyle.org/index.html) states that 14.x
  runs on JDK 21+ and parses Java language features through Java 25.
- [Maven Checkstyle runtime override](https://maven.apache.org/plugins/maven-checkstyle-plugin/examples/upgrading-checkstyle.html)
  documents overriding the plugin's older bundled engine via a plugin dependency.
- [SpotBugs 4.10.5 changelog](https://github.com/spotbugs/spotbugs/blob/4.10.5/CHANGELOG.md)
  includes Java 25 fixes in the 4.10 series. The pinned release uses
  [ASM 9.11](https://github.com/spotbugs/spotbugs/blob/4.10.5/gradle/libs.versions.toml),
  newer than ASM 9.8 which added Java 25 class-file support
  (see [ASM versions](https://asm.ow2.io/versions.html)).
- [SpotBugs Maven usage](https://spotbugs.github.io/spotbugs-maven-plugin/usage.html)
  documents version 4.10.5.0 and the check goal. Local compilation and analysis
  on OpenJDK 25 succeeded; future preview features still require revalidation.
