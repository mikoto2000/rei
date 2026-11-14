# 指摘一覧

重大度は「このリポジトリを配布・運用する立場での影響」で付けた。すべて静的読解に基づく。
実行・ビルド・通信の実測はしていない（[verification.md](verification.md) 参照）。

## 重大度サマリ

| 重大度 | 件数 |
| --- | ---: |
| HIGH | 2 |
| MEDIUM | 5 |
| LOW | 14 |
| INFO | 2 |

---

## HIGH

### F-01 既定の LLM 接続先が私設 LAN アドレス

**根拠**: `src/main/resources/application.yaml`

```yaml
base-url: ${REI_OPENAI_BASE_URL:http://192.168.1.50:11434}
model: ${REI_OPENAI_CHAT_MODEL:qwen3.5:122b}
```

**評価**: 環境変数を設定せずに起動すると、`192.168.1.50:11434` の Ollama へ接続を試みる。
README は「URL・モデル名・キーを実際の接続先に置き換えてください」と案内しており、既定値が実運用値のまま残っている。

影響は3つ。

1. 第三者が clone して起動すると、無関係な私設ホストへ接続要求が飛ぶ。
2. 社内ネットワーク構成（セグメント・ポート・モデル名）が公開リポジトリに露出する。
3. 失敗時のメッセージが「接続できない」になり、設定漏れだと分かりにくい。

**推奨**: 既定を `https://api.openai.com` にするか、空にして起動時に明示的なエラーを出す。
ローカル検証用の値は `application-local.yaml` などプロファイル側へ移す。

### F-02 sqlite-vec 自動ダウンロードの防御が不足

**根拠**: `src/main/java/dev/mikoto2000/rei/core/sqlitevec/SqliteVecInstaller.java`

```java
private final HttpClient httpClient =
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

HttpResponse<byte[]> response =
    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
```

`verifyChecksum` は同じ配信元から取得した manifest の `checksum_sha256` と比較する。
`application.yaml` では `rei.sqlite-vec.auto-download: true` が既定。

**評価**: 4点。

1. **サイズ上限なし**。`BodyHandlers.ofByteArray()` は全体をメモリに載せる。配信元が巨大な応答を返すと OOM になる。
2. **タイムアウトなし**。`HttpRequest` に connect/read timeout が設定されておらず、無応答で永久に待つ。
3. **checksum が TOFU**。manifest と本体を同一経路で取得するため、配信元が侵害されると両方すり替わる。リポジトリ内に固定値を置いていない。
4. **`followRedirects(NORMAL)`** で任意ホストへ転送され得る。

さらに、Web 取得には `PublicNetworkPolicy` / `HttpFetchPolicy` による私設網遮断があるのに、この経路だけ素の `HttpClient` を使っている。
`rei.sqlite-vec.release-base-url` は設定可能なので、ポリシー適用の一貫性がない。

**推奨**: `BodyHandlers.ofInputStream()` + 上限付き読み取り、connect/read timeout、リポジトリ同梱の固定 checksum、`PublicNetworkPolicy` の適用。

---

## MEDIUM

### F-03 Linux ジョブはレポート検証のみで、POSIX ビルド手順は未検証

**根拠**: `.github/workflows/java.yml` の `regression` は `windows-latest`。`clients.yml` と `static-analysis.yml` も `windows-latest`。
一方 `java.yml` には `ubuntu-latest` の `test` ジョブがあるが、実行内容は `./scripts/test-test-performance-report.ps1`、`./scripts/test-check-test-partition.ps1`、`check-test-partition.ps1` の3つで、いずれも PowerShell によるレポート検証。
`./mvnw test`、`sh ./build.sh`、`sh ./rei.sh`、`sh ./start.sh` は Linux で一度も実行されない。

**評価**: 前回「CI が Windows のみ」と書いたが、これは不正確だった。Linux ジョブは存在する。
ただし検証対象はテスト結果 XML の整合性であり、POSIX 環境でのビルド・起動は未検証のまま。
シェルスクリプトの改行コード、`mvnw` の実行権限、パス区切り、`REI_DATA_DIR` の既定は Windows 実行では検出できない。

**推奨**: `ubuntu-latest` で `./mvnw -B -DskipTests package` と `./mvnw -B -f terminal/pom.xml -DskipTests package`、可能なら `./mvnw -B test` のサブセットを回すジョブを追加する。

### F-04 アーキテクチャ文書が実装より古い

**根拠**: `docs/architecture/README.md` は次を主張している。

- 「`BackgroundCommands` から Shell の `ChatResponseNarrator` への処理本体依存が1件あります」
- 「既存SpotBugs 1,907件は保持」

しかし現状は次のとおり。

- `BackgroundCommands.java:65` は `private CompletedRunNarrator narrator;`。`CompletedRunNarrator` は `core.execution` 内のポート（`@FunctionalInterface`）で、UI 依存は解消済み。
- `config/architecture/B-execution-ui.txt` は **0 行**（B 違反なし）。`A-application-ui.txt` も 0 行。
- `.github/ci/static-analysis-gate.ps1` は `if ($total -gt 0) { throw ... }` で **合計 0 件**を要求する。「既存 1,907 件を保持」という記述と両立しない。

**評価**: 文書は Phase 4 完了前の状態を説明している。読者は「B 違反が1件残っている」「SpotBugs 負債が1,907件ある」と誤解する。
どちらが古いのか（文書か、ゲートか）は実測しないと確定できないが、少なくとも**両者は同時に正しくない**。

**推奨**: 文書を再生成し、ゲートの意味（新規のみか、絶対0か）を明記する。

### F-05 ProjectRegistry が2インスタンスで snapshot がずれる

**根拠**: `src/main/java/dev/mikoto2000/rei/web/WebApiConfiguration.java`

```java
@Bean
ProjectRegistry webProjectRegistry(@Value("${rei.data-dir}") String directory) {
  return new ProjectRegistry(Path.of(directory).resolve("projects.json"));
}
```

`ProjectRegistry` 側のコメントも「Shell and HTTP use separate registry instances in the same Backend」と認めている。
`completionSnapshot` は `volatile` なインスタンスフィールドで、`read()` / `save()` のたびに更新される。

**評価**: ファイル書き込みは静的ロック（64 ストライプ）で直列化されているので破損は防げる。
しかし **snapshot の一貫性は保証されない**。Shell 側でプロジェクトを登録しても、Web 側インスタンスの `completionSnapshot()` は自分が read/save するまで古いまま。

**推奨**: 単一 Bean を共有するか、`completionSnapshot()` を毎回 `read()` に基づかせる。

### F-06 ConcurrentModificationException を業務エラーに読み替えている

**根拠**: `src/main/java/dev/mikoto2000/rei/web/ApiExceptionHandler.java`

```java
@ExceptionHandler(java.util.ConcurrentModificationException.class)
public ResponseEntity<Error> workContextConflict() {
  return ResponseEntity.status(409).body(new Error("Work Context revision changed"));
}
```

**評価**: `ConcurrentModificationException` は JDK の汎用例外で、コレクション反復中の変更全般で発生する。
これを Work Context の楽観ロック競合として 409 に変換すると、**無関係な実装バグが「リビジョン競合」として隠れる**。

**推奨**: Work Context 専用の例外型を定義し、それだけを 409 に変換する。

### F-07 依存脆弱性スキャンがない

**根拠**: `.github/workflows/` は `java.yml`、`clients.yml`、`static-analysis.yml` の3つのみ。Dependabot 設定なし。

**評価**: 依存は多い（Spring Boot 4.1.1、Spring AI 2.0.1、Google API、Slack SDK、PDFBox、Tika、sqlite-jdbc、JNA ほか）。
Checkstyle / SpotBugs はコード品質を見るが、既知 CVE は検出しない。

**推奨**: Dependabot（Maven / npm / cargo）と、OWASP dependency-check などの定期スキャンを追加する。

---

## LOW

### F-08 想定外例外がログされない

**根拠**: `src/main/java/dev/mikoto2000/rei/web/ApiExceptionHandler.java`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Error> unexpected(Exception exception) {
  if (exception instanceof org.springframework.web.ErrorResponse response)
    return ResponseEntity.status(response.getStatusCode()).body(new Error("Request rejected"));
  return ResponseEntity.status(500).body(new Error("Internal server error"));
}
```

**評価**: `exception` を一切ログしない。クライアントには 500 だけが返り、サーバー側にも痕跡が残らない。
`logback-spring.xml` でファイル出力を設定しているのに、ここだけ握りつぶしている。

**推奨**: `ErrorResponse` 以外は `log.error` でスタックトレースを残す。

### F-09 ProjectRegistry の起動時読み込み失敗を握りつぶす

**根拠**: `src/main/java/dev/mikoto2000/rei/core/project/ProjectRegistry.java`

```java
synchronized (fileLock) {
  try {
    read();
  } catch (RuntimeException ignored) {
    /* Optional startup completion snapshot. */
  }
}
```

**評価**: `projects.json` が壊れていても起動は成功する。最初の `resolve` / `list` で初めて `IllegalStateException` になる。
原因（破損ファイル）と症状（操作時の失敗）が離れるため、調査が遅れる。

**推奨**: 起動時に警告ログを出し、破損を明示する。

### F-10 remove だけ canonical 化していない

**根拠**: 同ファイル

```java
public synchronized void remove(Path path) {
  synchronized (fileLock) {
    var entries = read();
    entries.removeIf(e -> Path.of(e.path()).equals(path.toAbsolutePath().normalize()));
```

`resolve` は `canonical(path)`（`toRealPath()`）で登録する。

**評価**: Windows のジャンクション、シンボリックリンク、ドライブレターの大文字小文字差があると、`remove` が一致せず登録が残る。

**推奨**: `remove` も `canonical(path)` を使う。

### F-11 pom.xml の配布メタデータが空

**根拠**: `pom.xml`

```xml
<name/>
<description/>
<licenses><license/></licenses>
<developers><developer/></developers>
<scm><connection/><developerConnection/><tag/><url/></scm>
```

**評価**: ルートに `LICENSE` があるのに POM 側は空要素。`mvn install` した成果物のメタデータが不完全になる。

**推奨**: 実際の値で埋めるか、要素ごと削除する。

### F-12 bluesky だけ DEBUG ログが既定

**根拠**: `src/main/resources/application.yaml`

```yaml
logging:
  level:
    com.slack.api: "OFF"
    org.java_websocket: "OFF"
    dev.mikoto2000.rei.bluesky: DEBUG
```

**評価**: Slack / WebSocket は認証情報や生ペイロードを理由に OFF にしているのに、Bluesky だけ DEBUG。
投稿本文や API 応答がログに残る可能性がある。

**推奨**: 既定を INFO にし、必要時のみ環境変数で上げる。

### F-13 フロントエンドの format / e2e が CI 未実行

**根拠**: `client/package.json` に `format:check` と `test:e2e` があるが、`.github/workflows/clients.yml` は `typecheck` / `lint` / `test` / `build` のみ。

**評価**: 整形規約と Playwright E2E が回帰検出に入っていない。

**推奨**: `format:check` を追加。E2E は実行時間との相談になるが、少なくとも手動実行手順を CI 外で明文化する。

### F-16 `rei.data-dir` の既定値が二重定義で食い違う

**根拠**: `src/main/java/dev/mikoto2000/rei/core/datasource/ReiDataDirectory.java` と `src/main/resources/application.yaml`

```java
} else if (os.toLowerCase(Locale.ROOT).contains("mac"))
  path = home.resolve("Library/Application Support/Rei");
else
  path =
      (present(env, "XDG_DATA_HOME")
              ? Path.of(env.get("XDG_DATA_HOME"))
              : home.resolve(".local/share"))
          .resolve("rei");
```

```yaml
rei:
  data-dir: ${REI_DATA_DIR:${LOCALAPPDATA:${user.home}/.local/share}/Rei}
```

**評価**: 同じ概念を2箇所が別々に決めている。

| 決定箇所 | Windows | macOS | Linux |
| --- | --- | --- | --- |
| `ReiDataDirectory.resolve` | `%LOCALAPPDATA%\Rei` | `~/Library/Application Support/Rei` | `$XDG_DATA_HOME/rei` または `~/.local/share/rei` |
| `application.yaml` | `${LOCALAPPDATA}/Rei` | `~/.local/share/Rei` | `~/.local/share/Rei` |

yaml 側は OS 分岐を持たないため、macOS の `Library/Application Support` を再現できず、Linux では大文字始まりの `Rei` になる。

実害は限定的だ。`ReiApplication.main` が `System.setProperty("rei.data-dir", ReiDataDirectory.current().toString())` を実行するので、通常起動では System プロパティが優先され yaml の既定値は使われない。

残る問題は2点。

1. `main` を経由しない起動（テスト用 `ApplicationContext`、将来の別エントリポイント）では yaml の値が有効になり、OS ごとに誤ったパスへ書く。
2. 同じ既定値が2箇所にあるため、片方だけ直す変更が起きやすい。

**推奨**: yaml 側を `rei.data-dir: ${REI_DATA_DIR:}` のように空既定にし、解決を `ReiDataDirectory` に一本化する。

### F-17 `terminal/contract` と `terminal/completion` が Backend ソースを再コンパイルしている

**根拠**: `terminal/contract/pom.xml` と `terminal/completion/pom.xml`

```xml
<sourceDirectory>../../src/main/java</sourceDirectory>
<includes>
  <include>dev/mikoto2000/rei/core/datasource/ReiDataDirectory.java</include>
  ...
</includes>
```

**評価**: `docs/lightweight-cli-client.md` は「`terminal/pom.xml` は既存ルートビルドとは独立した reactor」「既存 Backend クラスの大量移動は行っていない」と説明する。
依存関係としては独立しているが、**ソースは共有**している。`contract` は `ReiDataDirectory`、`CredentialRedactor`、`HistoryFormatter`、`CliCatalog` など約20ファイルを include リストで取り込む。

影響は2つ。

1. Backend 側のクラスが新しい型や依存を導入すると、CLI reactor のコンパイルが壊れる。include リストは手動管理なので、追加漏れも起きやすい。
2. 「CLI は Backend のコードに依存しない」という説明は、Maven 依存の意味では正しいが、ソース共有の意味では正しくない。

**推奨**: include リストを生成するか、共有部分を独立モジュールへ切り出す。少なくとも文書に「ソースを再コンパイルしている」と明記する。

### F-18 CLI 履歴の保存先が `user.home` 直書き

**根拠**: `terminal/launcher/src/main/java/dev/mikoto2000/rei/terminal/Launcher.java`

```java
Path clientData = Path.of(System.getProperty("user.home"), ".rei-cli/history");
```

**評価**: Backend のデータ保存先は `ReiDataDirectory` が OS 別に決め、`REI_DATA_DIR` / `-Drei.data-dir` で上書きできる。
CLI の履歴だけは `user.home` 固定で、この解決を通らない。
`docs/lightweight-cli-client.md` も `${user.home}/.rei-cli/history` と書いており、実装と文書は一致している。
つまり**設計上の選択**であり、不整合ではない。ただし `REI_DATA_DIR` を別ボリュームへ移しても履歴だけ home に残る点は、バックアップ・移行の説明で触れておく価値がある。

**推奨**: 現状維持でよいが、`docs/storage-backup-restore.md` に CLI 履歴が対象外であることを明記する。

### F-19 macOS では既定 `auto` の detached 起動が失敗する

**根拠**: `terminal/launcher/src/main/java/dev/mikoto2000/rei/terminal/DetachedBackend.java`

```java
Path setsid = Path.of("/usr/bin/setsid");
if (!Files.isExecutable(setsid))
  throw new IOException(
      "Detached backend requires /usr/bin/setsid on this platform; use an OS service");
```

**評価**: macOS に `/usr/bin/setsid` は標準搭載されていない。`auto` は Backend が無ければ detached 起動を試みるため、macOS では既定モードがエラーになる。
`docs/lightweight-cli-client.md` は「Unix detached 起動は `/usr/bin/setsid` が必要で、macOS の service 管理は未実装」と明記しており、**既知の制約**。
ただし README の「Linux では `sh ./rei.sh`」という案内には macOS の代替手順がなく、macOS 利用者は `--mode=legacy-shell` に辿り着きにくい。

**推奨**: README の macOS 向けに `--mode=legacy-shell` または手動 Backend 起動を明記する。

### F-20 Native Client の vault にファイルロックと権限制限がない

**根拠**: `client/src-tauri/src/infrastructure/storage.rs`

```rust
fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().ok_or(AppError::Storage)?;
    std::fs::create_dir_all(parent).map_err(|_| AppError::Storage)?;
    let mut file = tempfile::NamedTempFile::new_in(parent).map_err(|_| AppError::Storage)?;
    file.write_all(bytes).map_err(|_| AppError::Storage)?;
    file.as_file().sync_all().map_err(|_| AppError::Storage)?;
    file.persist(path).map_err(|_| AppError::Storage)?;
    Ok(())
}
```

**評価**: 暗号化自体は妥当。XChaCha20-Poly1305、Argon2 による鍵導出、`Zeroizing` による平文の消去、`Secret` の `Debug` が `[REDACTED]` を返す点は良い。
問題は周辺の3点。

1. **ファイルロックがない**。`EncryptedVault::open` は読み込み→復号→`persist()` を排他なしで行う。2つのプロセスが同時に unlock すると、後勝ちで相手の `set()` 結果が消える。
   Java 側の `ProtectedCredentialStore` は JVM の `ReentrantLock` と OS の `FileLock` で refresh/read/atomic replace 全体を保護している。**同じ問題に対する防御が非対称**。
2. **権限を制限していない**。`atomic_write` は `tempfile` の既定権限で作成し、`persist` する。Unix では umask 次第で 0644 になり得る。
   Java 側の `BackendEndpointStore.restrict()` は ACL または `rw-------` を明示している。vault には同等の処理がない。
   暗号文自体は読めても平文にはならないが、salt・nonce・暗号文が読めると**オフラインでのパスワード総当たりが可能**になる。
3. **読み込みにサイズ上限がない**。`std::fs::read(&path)` は全体をメモリに載せる。Java 側は `Files.size(file) > 1048576` で拒否している。

**推奨**: `atomic_write` で 0600 を設定し、`open` / `set` / `delete` をファイルロックで直列化し、読み込みに上限を設ける。

### F-21 `EncryptedVault::open` が unlock のたびに vault を書き換える

**根拠**: 同ファイル

```rust
let vault = Self { path, key, salt, secrets };
vault.persist()?;
Ok(vault)
```

**評価**: 復号に成功した場合も、ファイルが存在しなかった場合も、無条件に `persist()` する。
つまり**パスワードを入れて unlock するだけで、新しい nonce で暗号文が再生成される**。

影響は2つ。

1. 内容が変わっていないのに書き込みが発生する。読み取り専用の場所や容量制限のある環境で unlock が失敗し得る。
2. F-20 のロック欠如と組み合わさると、unlock 中の書き換えが他プロセスの `set()` を上書きする窓が広がる。

**推奨**: 復号のみの場合は書き換えない。salt の生成が必要な初回作成時だけ `persist()` する。

### F-22 `run_path` が turn 側で受け付ける Run ID を拒否する

**根拠**: `client/src-tauri/src/api/mod.rs` と `client/src-tauri/src/api/history.rs`

```rust
fn run_path(run: &str, suffix: &str) -> Result<String> {
    if run.is_empty() || !run.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(AppError::InvalidInput);
    }
    Ok(format!("/api/v1/runs/{run}{suffix}"))
}
```

一方 `history.rs` のテストは `turnId` / `runId` に `behavior:b1` を受け入れ、`into_domain` は `turn.run_id == turn.turn_id` だけを検証する。

**評価**: `:` を含む Run ID は turn としては表示できるが、`run()`、`input()`、`cancel_receipt()`、`events()` はすべて `run_path` を通るため `InvalidInput` になる。
通知由来の Run を追跡・停止しようとすると失敗する。

ただし UI が実際にそのような Run ID で `run_get` / `run_subscribe` を呼ぶかは未確認。**潜在的な不整合**として扱う。

**推奨**: `run_path` の許容文字を turn 側と揃えるか、`behavior:` 形式を明示的に扱う。

### F-23 Tauri ランタイム失敗が panic になる

**根拠**: `client/src-tauri/src/native.rs`

```rust
.run(tauri::generate_context!())
.expect("Rei Client runtime failed");
```

**評価**: 起動失敗が panic になり、ユーザーには汎用のクラッシュとして見える。
`setup` 内の `Application::open` 失敗は `std::io::Error::other("Rei app data could not be opened")` に潰されており、原因（vault 破損、権限不足、容量不足）が区別できない。

**推奨**: 起動失敗をログに残し、GUI に理由を表示する経路を作る。

---

## INFO

### F-14 アーキテクチャ基準は「既存負債の凍結」であり削減を強制しない

**根拠**: `config/architecture/` の行数は C-business 2851、C-wiring 793、D 390、E 1052、A/B は 0。合計 5086 行。

**評価**: `ArchitectureBaseline.check` は「追加があれば失敗」「削除があれば明示 shrink を要求」する。
つまり新規の依存悪化は確実に止めるが、既存 5086 辺の削減は人間の判断に委ねられる。
これは設計意図どおり（`docs/architecture/README.md` に明記）で、問題ではない。
ただし「ゲートがあるから依存は健全」と読み替えないよう注意が必要。

### F-15 テスト分割の検証が丁寧

**根拠**: `.github/ci/full-test.ps1` は shard A/B を `surefire.includesFile` / `excludesFile` で分割し、`scripts/check-test-partition.ps1` が「全テストケースがどちらかに属する」「期待外の skip がない」ことを XML から検証する。`config/test-expected-skips.json` に 12 件の理由付き skip を列挙。

**評価**: 「テストを減らして緑にする」抜け道を塞いでいる。良い設計。
