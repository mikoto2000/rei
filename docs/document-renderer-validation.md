# Document renderer validation

The document-editor SubAgent already supports plain-text PlantUML drafts. The new
parent Tool validates an explicitly selected `.puml` or `.plantuml` file after text
review/Apply. It does not edit documents or run model-provided commands.

`validateDocumentRender({path,sourceSha256})` requires the captured canonical human
Project/root/session, EXCLUSIVE execution, READ/EXECUTE/LOCAL_WRITE permissions and
the exact source hash. `inspectDocumentRender(id)` is READ and owner scoped.

Configuration is identical in application YAML and the generated external template:

```
REI_DOCUMENT_RENDERER_ENABLED=false
REI_DOCUMENT_RENDERER_JAVA=
REI_DOCUMENT_RENDERER_PLANTUML_JAR=
```

The administrator supplies an absolute canonical trusted PlantUML jar path and,
optionally, an absolute Java executable path. Blank Java uses the running JVM's
executable. There is no automatic installation, network renderer, paid model, or
shell wrapper. Enable only with a trusted maintained jar.

Only one standalone `@startuml` / `@enduml` diagram is accepted. Text is bounded
to 64 KiB UTF-8. Preprocessor directives, functions, resource links and resource
markup are rejected. The child uses PlantUML SANDBOX, a cleared credential
environment, private TEMP/work directory, headless PNG output and no source metadata.
PlantUML documents SANDBOX's file/URL restrictions in its
[security documentation](https://plantuml.com/en/security); error reporting and
exit behavior are described in its [CLI documentation](https://plantuml.com/command-line).

The child has a 20-second process deadline with bounded output drains. stdout is
limited to 4 MiB and stderr to 8 KiB. A zero exit code alone is insufficient:
parse-error diagnostics, PNG signature and complete image decoding, positive
dimensions no larger than 4096 pixels and 16 million pixels are checked. The
receipt records source SHA, jar SHA identity, exit code, parse error, output size
and SHA. Raw source and renderer stderr are not saved in the receipt.

The source is checked again after rendering; drift becomes STALE without publishing.
With the existing ArtifactStore enabled, a verified immutable PNG is published
through its owner-scoped delivery API, and its Artifact receipt is attached. With
delivery disabled, a RENDERED receipt explicitly includes ARTIFACT_DELIVERY_DISABLED
and no fabricated downloadable artifact. Goal artifact requirements still require
an independently available ArtifactStore item.

SQLite `document_renderer_receipts` is additive to the shared data source. The claim
is written before launching the process, unique by Project/root/session/Run/request.
Exact retries return the historical receipt without launching again. STARTED,
UNKNOWN, TIMEOUT, INVALID and UNAVAILABLE do not mean validated success. Lost PID /
start identity or a vanished same-process operation becomes UNKNOWN on inspection.
History is capped at 128 per Project and 1024 overall. A new deliberate Run is needed
to re-render; no automatic retry of unknown operations.

Normal exit, deadline and cancellation stop owned descendants, close pipes and clean
the private temporary directory. OS hard kill cannot guarantee cleanup: an orphan
temporary directory may remain in OS temp; it contains renderer scratch files, not
an automatically applied source change. Artifact publication has its own existing
write-ahead receipt and recovery boundary. Inspection reports historical evidence,
not a promise that the current source still matches.

This phase adds PlantUML PNG because it has a usable local renderer and an existing
draft format. Existing Markdown remains a text preview. Mermaid needs a configured
renderer before an equivalent provider is added. General Word/Excel/PDF editing is
outside this limited renderer API.

Deterministic service tests use fixture PNGs; integration tests launch an actual
sleeping child JVM to verify deadline and cancellation. The actual PlantUML test is:

```
./mvnw -Pfull -Dtest=DocumentRendererProcessTest \
  -Drei.test.plantuml.jar=/absolute/path/plantuml.jar test
```

Absent test jar configuration produces a stated skip for that one real-renderer test.
This is separate from paid live E2E and does not claim quality for an absent renderer.
