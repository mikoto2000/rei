# File dependency watcher

Branch: `codex/file-dependency-watcher`

`waitForFile(relativeFile, expectedSha256?, timeoutSeconds?)` waits for existence of a regular file or exact SHA-256 content inside the captured owning Project. Default timeout is 10 seconds, maximum 60 seconds. It uses the existing monotonic bounded DependencyAwaiter and independent FileGoalVerifier, with cancellation before and after probes and no writes or extra LLM calls. Mismatched digest/missing file returns WAITING; rejected paths, symlinks, relocated root or files over 1 MiB return BLOCKED. No file contents enter the model response.

The new READ tool is registered in both Chat configuration paths. Hidden execution context reports verified WAITING into the existing stagnation detector, avoiding a false stagnation count. Optional parameters are optional in the generated schema too.

Goal verification still requires valid non-null SHA-256 criteria; adding an existence watcher cannot weaken Goal completion. Shared waiting now detects thread interruption during a probe before returning a terminal observation.

TDD: missing watcher API failed compilation; a Goal without digest exposed the existence/Goal boundary and failed its regression test; a probe-time interrupt also failed before the shared awaiter fix. Focused tests passed after fixes. Real-file tests cover creation during polling, digest match/mismatch, absence, size cap, traversal/absolute path refusal and cancellation. Method callback tests cover context hiding, optional schema fields and stagnation integration.

Remaining: dependency persistence/graph restoration, Git/API/user-answer watchers and other open audit entries. This watcher is not overall completion.

Validation: full suite 2,893 tests / 552 suites; failures 0, errors 0, skipped 0.
