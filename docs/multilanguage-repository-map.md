# Multilingual Repository Map and Impact

The existing `repositoryMap` and `changeTestImpact` READ Tools now share a bounded index for Java,
TypeScript/JavaScript, Rust, Go and Python. No source, package manager, build script, compiler processor
or language server is executed. Git inventory disables optional locks and repository-configured fsmonitor.
Java retains its structural AST parse and opt-in persistent metadata cache.

## Analysis contract

Each file adds `language` and `analysisMode`:

| Mode | Meaning |
|---|---|
| JAVA_AST | Existing parse-only Java declarations/imports; no compiler binding resolution |
| HEURISTIC | Bounded lexical, line-oriented declaration and local-module candidates |
| INVENTORY_ONLY | File inventory without source-language analysis |

Heuristic results always set `partial` and warn that a semantic compiler/module resolver is absent.
`HEURISTIC_IMPORT` and `HEURISTIC_TEST_NAME_CANDIDATE` are candidate graph edges, never execution,
coverage or proof that other tests may be skipped. Impact traverses these edges through its existing
reverse graph and requests broad regression when the index is partial.
File/package/module metadata and SHA describe the bounded scan, not a complete repository dependency graph.

Supported structural candidates:

- TS/JS: named classes/interfaces/types/enums, functions, single-line arrow declarations and basic methods;
  explicit relative ES imports/re-exports, literal CommonJS require and literal import candidates.
- Rust: struct/enum/trait/type/function/module declarations and basic impl methods;
  module declarations, local crate/self/super use paths and bounded grouped use declarations.
- Go: package, type, function and receiver-method declarations; literal single/grouped imports resolved
  against observed local `go.mod` module names and package folders.
- Python: class/function/basic method declarations; explicit dotted and relative module imports,
  with candidates restricted to the source build root.

Comments and ordinary string/template literals are masked before declaration matching. Unterminated
comments/strings produce `LEXICAL_ERROR` with no invented symbols. A heuristic lexer is not a grammar
validator: macros, decorators, generators, multiline declarations, computed/dynamic imports, aliases,
TS path mappings/package exports, Rust cfg/macro expansion, Go build tags and Python runtime search
paths may require additional analysis. Ambiguous local paths do not select an arbitrary target.
TS `.js` specifiers can yield a unique observed TS source candidate; this is explicit heuristic substitution.
No external package download or remote source fetch occurs.

## Build boundaries, cache and limits

Observed `pom.xml`, `package.json`, `Cargo.toml`, `go.mod`, `pyproject.toml` and `setup.py` files identify
nearest build roots. Cargo package and Go module names are bounded metadata observations, not evaluated
configuration. Boundaries without readable metadata fall back conservatively and remain partial.
Boundary changes invalidate heuristic namespace cache keys; observed identity changes alter Map version.
Java metadata persists separately; heuristic source bodies and literal contents are not persisted.

Existing source limits remain: 1,024 inventory entries, 128 KiB per source, 16 MiB combined read budget,
ten-second scan budget, 64 symbols and 128 imports per file, two cached Project roots,
100 displayed files and 200 displayed graph edges. Build metadata is bounded to 64 files and 16 KiB
per file and charged to the combined read budget. Lexer limits: 32,768 tokens, nested Rust comments
32 levels and grouped use nesting 16. Root escapes, symlinks outside root and sensitive/generated
paths are excluded. Credential-bearing import URLs/specifiers are replaced before output.

`summary.parsedJavaFiles` keeps its Java meaning; `summary.heuristicFiles` counts successfully observed
heuristic files. Legacy Java package summaries remain available; multilingual package/namespace facts
are exposed per file. Test candidates recognize existing test directories plus TS/JS `.test`/`.spec`,
Rust `tests`, Go `_test.go` and Python `test_*.py` conventions.

No new enable flag is required for the existing READ Tools. `rei.repository-map.persistent-index-enabled`
remains false by default and persists only Java metadata. Old result fields and constructors are preserved.

## Verification

Fixtures cover all five language families, declarations/methods, local import chains and reverse Impact,
comments/strings, ambiguity, secret redaction, bounds, Python scope, module identity/version changes,
cache refresh, compiler absence and mixed Java persistence. A real Git fixture proves its fsmonitor can
execute in a control call while the Map never invokes it. No live semantic resolver quality is claimed.

Syntax references: [TypeScript modules](https://www.typescriptlang.org/docs/handbook/modules/reference.html),
[Rust use declarations](https://doc.rust-lang.org/reference/items/use-declarations.html),
[Go imports](https://go.dev/ref/spec#Import_declarations),
[Python imports](https://docs.python.org/3/reference/import.html).
