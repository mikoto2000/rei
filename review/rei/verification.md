# 確認できたこと／できなかったこと

## 重要な前提

このレビューは**網羅レビューではない**。次の2点を先に明示する。

最初のスナップショット `0a67a085` は `SCANNING` で停止し、評価提出は0件だった。
再実行の `c02a75a8` は3,923ファイルの列挙を終え、`SEALED` に到達した。
提出した assessment の対象は README に記載した範囲に限られる。
座標付き指摘の検証と、残りの作業単位のレビューは未完了。

## 実際に読んだ範囲

| 区分 | ファイル |
| --- | --- |
| ビルド | `pom.xml`、`build.sh`、`build.bat` |
| CI | `.github/workflows/java.yml`、`clients.yml`、`static-analysis.yml`、`.github/ci/static-analysis-gate.ps1`、`full-test.ps1`、`static-analysis-summary.ps1`、`test-static-analysis-gate.ps1`、`scripts/check-test-partition.ps1`、`config/test-expected-skips.json` |
| アーキテクチャ | `docs/architecture/README.md`、`config/architecture/*.txt`（行数のみ）、`ArchitectureBaseline.java`、`ArchitectureModel.java`、`PackageArchitectureTest.java` |
| Web / 認証 | `WebApplication.java`、`WebApiConfiguration.java`、`ApiKeyProperties.java`、`ApiExceptionHandler.java`、`SseBridge.java`、`SecurityConfigTest.java` |
| GitHub Webhook | `GitHubWebhookDecoder.java`、`GitHubWebhookProperties.java`、`GitHubWebhookService.java` |
| 設定 | `application.yaml`（全体）、`ExternalConfigSupport.java`、`ReiDataDirectory.java`、`ReiPaths.java`、`docs/configuration.md` |
| 供給網 | `SqliteVecInstaller.java`、`SqliteVecAssetResolver.java`、`SqliteVecProperties.java` |
| 実行基盤 | `ReiApplication.java`、`BackgroundCommands.java`、`CompletedRunNarrator.java`、`ProjectRegistry.java`、`ChatSubmitService.java`、`SessionLifecycle.java`、`AgentRunConfiguration.java` |
| クライアント | `client/package.json`、`clients.yml` |
| 文書 | `README.md`、`DEVELOP.md` |

## 確認できたこと（根拠あり）

- `application.yaml` の既定接続先が `http://192.168.1.50:11434`、既定モデルが `qwen3.5:122b` であること（F-01）。
- `SqliteVecInstaller.HttpDownloadClient` が `BodyHandlers.ofByteArray()` と `followRedirects(NORMAL)` を使い、`HttpRequest` に timeout がないこと（F-02）。
- `verifyChecksum` の期待値が同一配信元の manifest 由来であること（F-02）。
- Java 回帰、クライアント、静的解析は Windows で実行し、Ubuntu ジョブはレポートとテスト分割の検証を実行すること（F-03）。
- `B-execution-ui.txt` が0行、`A-application-ui.txt` が0行であること（F-04）。
- `static-analysis-gate.ps1` が `if ($total -gt 0) { throw ... }` で合計0件を要求すること（F-04）。
- `BackgroundCommands` の narrator が `CompletedRunNarrator`（`core.execution` 内のポート）であること（F-04）。
- `WebApiConfiguration` が `rei.data-dir` から別インスタンスの `ProjectRegistry` を作ること（F-05）。
- `ApiExceptionHandler` が `ConcurrentModificationException` を409へ、`Exception` をログなしで500へ変換すること（F-06、F-08）。
- `ProjectRegistry` の起動時 `read()` が `RuntimeException` を握りつぶすこと（F-09）。
- `remove` が `toAbsolutePath().normalize()` のみで `canonical()` を使わないこと（F-10）。
- `pom.xml` の `name`/`description`/`license`/`developer`/`scm` が空要素であること（F-11）。
- `logging.level` で bluesky だけ `DEBUG` であること（F-12）。
- `client/package.json` に `format:check` と `test:e2e` があるが `clients.yml` に該当ステップがないこと（F-13）。
- `ReiDataDirectory.resolve` と `application.yaml` の `rei.data-dir` 既定値が食い違うこと（F-16）。
- `ReiApplication.main` が `rei.data-dir` を System プロパティとして設定すること（F-16 の実害限定の根拠）。

## 確認できなかったこと

- **テスト実行**: `./mvnw test`、`-Pfull`、`terminal/pom.xml verify`、`cargo test`、`npm test`、Playwright はいずれも未実行。
- **ビルド**: `package`、`build.sh` / `build.bat` は未実行。
- **実起動・実通信**: Web API の認証挙動、SSE 再接続、Webhook 署名検証、sqlite-vec の実ダウンロードは未検証。
- **依存 CVE**: 実際の脆弱性スキャンは未実施（F-07 は「スキャン機構がない」という指摘であり、既知 CVE の有無ではない）。
- **`.m2` 配下**: jar が `UNSUPPORTED_ENCODING` で除外されており、内容は未確認。
- **`.kiro/specs` の一部**: `interest-discovery/requirements.md`、`model-management/requirements.md`、`sound-notification/*` が `UNSUPPORTED_ENCODING` で読めていない。

## 手法と限界

- 指摘は「コードと設定の静的読解」に基づく。実行時の挙動、性能、実環境依存の不具合は対象外。
- 重大度は「配布・運用する立場での影響」で付けた。開発者個人のローカル利用では影響が小さいものも含む。
- アーキテクチャ予算の数値は `countFileLines` による行数であり、辺の意味的な妥当性は評価していない。
- F-04 の Phase 0 資料は対象 SHA 時点の記録。現在のソースとゲートは別時点の根拠として扱う。

## 除外

- `review/codes`: 指示により未参照。
- `review/rei`: 本出力先。
- `.git`: Git メタデータ。
- ビルド生成物、`.m2` のバイナリ。

## 今回の再開で追加で読んだ範囲

| 区分 | ファイル |
| --- | --- |
| terminal | `terminal/pom.xml`、`terminal/cli/pom.xml`、`terminal/contract/pom.xml`、`terminal/completion/pom.xml`、`terminal/launcher/pom.xml`、`Launcher.java`、`DetachedBackend.java` |
| Native Client | `client/package.json`、`client/src-tauri/Cargo.toml`、`client/src-tauri/tauri.conf.json`、`client/src-tauri/src/main.rs`、`client/src-tauri/src/lib.rs`、`client/src-tauri/src` のファイル一覧 |
| CI | `.github/workflows/java.yml`（再読）、`clients.yml`（再読）、`static-analysis.yml`、`.github/ci/full-test.ps1`、`static-analysis-gate.ps1`、`scripts/check-test-partition.ps1` |
| 設定 | `application.yaml`（再読）、`ReiDataDirectory.java`、`BackendEndpointStore.java`、`docs/configuration.md` |
| 文書 | `docs/architecture/README.md`（再読）、`docs/lightweight-cli-client.md`、`README.md`（該当箇所） |
| アーキテクチャ予算 | `config/architecture/*.txt` の行数（`C-wiring-cycles.txt` を含む） |

## 今回の再開で確定した訂正

- **F-03**: `java.yml` には `ubuntu-latest` の `test` ジョブが存在する。「CI が Windows のみ」は誤り。ただし実行内容は PowerShell によるレポート検証のみで、POSIX ビルド・起動は未検証。
- **F-04**: `B-execution-ui.txt` が 0 行、`A-application-ui.txt` が 0 行であることを再確認。予算は C-business 2851、C-wiring 793、D 390、E 1052 で、文書の 2837 / 799 / 372 / 1034 と4つとも異なる。文書の分析対象 SHA は `bb4f4924`、現在の HEAD は `b4b0c257`。
- **F-16**: `ReiDataDirectory.resolve` と `application.yaml` の `rei.data-dir` 既定値の食い違いを再確認。README の保存先表は `ReiDataDirectory` と一致しており、README 側は正しい。
