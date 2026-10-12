# Multi tool batching policy

The three file Multi tools (`readMultiFile`, `grepMultiQuery`, and `writeMultiFile`) now explicitly instruct the model to put independent, already-known targets in one array and one call. Multiple singleton calls to the same tool in the same response are discouraged. Each description includes a two-target JSON example exercised by `ToolsTest`.

`ToolUsagePolicy` contains the short shared planning instructions. `LlmChatClientProvider` appends them to the normal system prompt, including the computer orchestration variant, and supplies only those instructions to READ_ONLY parallel clients. `SubAgentRunner` composes them with the child's own definition and output contract. Parent personality, history, tool selection and approval context are not copied by this change. Composition avoids adding the identical policy twice. CONVERSATION clients remain tool-free.

A one-item array is valid. Dependencies and documented file-count, byte, line, token and staged-write limits remain reasons to split. This change does not merge calls, retry calls, alter schemas, or change permissions, budgets or compression.

Validation uses local fixtures and mock models, not live model inference. The initial regression run failed six assertions; the first implementation run passed 59 tests. The expanded related run passed 176 tests, including existing singleton behavior and executable JSON examples. The final full-suite run (`mvn -o -Pfull test`) executed 5,362 tests: 1 failure, 1 error, and 5 skipped. The affected suites passed, including the additional mock-model READ_ONLY request with another system message. The unrelated failures were `PlanEventBoundaryTest` (published payload keys disagree with its expectation) and `DotHttpTest` (missing `DelegationToolPermissionResolver` bean in its test context). Their source and production dependencies were not changed; they were left for the separate CI repair task.

Static tests establish prompt delivery and description validity. They do not establish that a live model batches more reliably; that remains unverified.
