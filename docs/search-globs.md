# Search glob patterns

`grepMultiQuery` and `searchAndRead` use the same `includeGlob` and `excludeGlob`
rules. Paths in patterns are relative to the query's `baseDir`. Returned paths
remain relative to the project working directory.

| Pattern | Selected files within `baseDir` |
| --- | --- |
| `*.java` | Java files at any depth |
| `**/*.java` | Java files at any depth, including direct children |
| `foo/*.java` | Java files directly inside `foo` |
| `foo/**/*.java` | Java files inside `foo` and all its descendants |
| `**/*.{java,xml}` | Java and XML files at any depth |

Use `/` as the separator. Backslashes are also accepted as separators; they are
not escape characters. Matching is case-sensitive on every operating system.
`*` and `?` do not cross separators; `**` can cross separators, and `**/` matches
zero or more directories. Patterns without a separator match only the file name
at every depth. Character classes (`[abc]`, `[a-z]`, `[!abc]`) and non-nested
brace alternatives (`{java,xml}`) are supported. A comma outside braces is
literal, not an alternative separator.

An omitted or blank include/exclude pattern imposes no filter. An exclusion
takes precedence over an inclusion. Malformed patterns return an error for that
query while other queries can still succeed. Existing `.gitignore` handling and
file access restrictions also apply. If `baseDir` names a concrete file, globs
are matched against its file name.

```json
{
  "queries": [{
    "pattern": "class ",
    "baseDir": "src",
    "includeGlob": "*.java",
    "excludeGlob": "generated/**"
  }]
}
```

This finds Java files throughout `src`, including its direct children, while
excluding `src/generated` and its descendants.

Previously, patterns were Java filesystem globs matched against paths relative
to the working directory. Migrate `baseDir: "src", includeGlob: "src/**"` to
`baseDir: "src", includeGlob: "**"`. Patterns such as `*.java` now include nested
files, and `**/*.java` now includes direct children. Windows matching now also
distinguishes letter case.
