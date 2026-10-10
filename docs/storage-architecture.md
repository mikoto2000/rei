# Storage architecture

Phase 1 adds read-only inventory. Phase 2 adds a startup gate and verified backup
foundation. Phase 3 switches Session and Turn beans to indexed rows after verified
startup import. Phase 5 switches project Event and replay Cursor beans to rows. See
[Session and Turn storage](storage-session-turn-migration.md). No migration phase
automatically enables retention or deletes old sources.

## Database boundary

The new `storage.db` lives at the configured Rei data-directory root. It initially
contained only `storage_migrations` at version 1. Version 2 adds `sessions`,
`turns`, `storage_turn_sources` and `storage_imports`, scoped by project and
conversation, with WAL and indexed keyset paging. Version 3 adds `stored_objects`,
`object_references`, `retention_policies`, `retention_plans`,
`retention_candidates` and `retention_approvals`. See
[reference protection and retention](storage-retention.md). Version 4 adds
`agent_events`, `event_sequences`, `event_cursors` and `event_imports`. See
[Event and Cursor storage](storage-event-migration.md).

| Choice | Transaction / backup implications | Decision |
| --- | --- | --- |
| Add tables to existing Memory / consolidation DB | Reuses connections but increases write competition and couples unrelated schema failures | Preserve existing schemas and repositories |
| One DB per project | Isolates project deletion/failure; Session catalog and replay cursors cross DB boundaries | Keep project identity as row scope in the new DB |
| One DB per data directory | Session catalog and new storage metadata share one transaction boundary and backup; writers share SQLite's writer lock | Use `storage.db`; no per-session databases |

Profiles currently select settings, not separate physical directories. Physical
isolation follows `rei.data-dir` / `REI_DATA_DIR`. Explicitly exported files and
custom paths outside that directory remain outside inventory and migration.
Memory, consolidation, vector, Artifact and Checkpoint limits remain unchanged.

## Startup gate

`StorageMigrationConfiguration` exposes a static, highest-priority
`BeanFactoryPostProcessor`. It prepares storage before ordinary beans are created,
including datasource/repository constructors, `@PostConstruct` subscriptions,
Scheduler registration, Shell and Web request handling. It has no repository or
datasource bean dependencies. An `ApplicationRunner` would execute too late.

The gate holds `.storage/instance.lock` for the application context's lifetime.
Only already-prepared, same-version contexts in the same JVM share that lease;
another process cannot start on the same directory. The lease is released on
context shutdown or preparation failure. A legacy executable does not implement
this lease: stop it before upgrading. The backup verifies source fingerprints to
detect concurrent changes; a legacy writer must not be run alongside the upgrade.

Ready startup reads only the new DB's version and completed migration marker.
Newer versions or missing completion markers stop normal startup. Phase 2 does not
enable automatic retention or remove old JSON / JSONL / SQLite data.
