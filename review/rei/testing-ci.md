# テストと CI の評価

## テスト規模

| 区分 | ファイル数 |
| --- | ---: |
| main Java | 1713 |
| test Java | 1314 |

テスト/本番比は約 0.77。パッケージ別では core 306、web 87、activity 82、memory 49、storage 43、subagent 43、externalagent 41、voice 40。主要パッケージに満遍なくテストがある。

## テスト分類

pom.xml の Surefire 設定で JUnit タグによる分類を行う。

| プロファイル | test.groups | test.excludedGroups |
| --- | --- | --- |
| 既定 | 空 | integration / e2e / live |
| integration と full | 空 | live |
| integration-only | integration / e2e | live |
| live-e2e | live | 空 |

failIfNoTests=true、forkCount=1、reuseForks=true、並列実行は無効。REI_DATA_DIR を target/test-performance/data に固定し、実データを汚さない。

評価: 分類が明示的で、既定実行が外部依存を踏まない。妥当。

## CI の実行環境

| ワークフロー | ジョブ | ランナー | 実行内容 |
| --- | --- | --- | --- |
| `java.yml` | `regression` | `windows-latest` | アーキテクチャゲート、shard A/B の全回帰、XML 証跡 |
| `java.yml` | `test` | `ubuntu-latest` | PowerShell によるレポート・分割検証のみ |
| `clients.yml` | `terminal` | `windows-latest` | `-f terminal/pom.xml test` |
| `clients.yml` | `frontend` | `windows-latest` | `typecheck` / `lint` / `test` / `build` |
| `clients.yml` | `native` | `windows-latest` | `cargo test --locked` |
| `static-analysis.yml` | `analysis` | `windows-latest` | Checkstyle / SpotBugs レポートと 0 件ゲート |

**訂正**: 前回「CI が Windows のみ」と書いたが、`java.yml` には `ubuntu-latest` の `test` ジョブが存在する。
ただし実行内容は `test-test-performance-report.ps1`、`test-check-test-partition.ps1`、`check-test-partition.ps1` の3つで、いずれもテスト結果 XML の整合性検証。
`./mvnw test`、`sh ./build.sh`、`sh ./rei.sh` は Linux で実行されない。

**評価**: テスト分割の検証は Linux でも回っているが、POSIX 環境でのビルド・起動は未検証。
シェルスクリプトの改行コード、`mvnw` の実行権限、パス区切りは Windows 実行では検出できない。

## テスト分割の検証

`check-test-partition.ps1` は次を強制する。

- shard A のクラス割当と実際に報告された suite が完全一致（欠落・余剰の両方を検出）
- shard A/B のテストケース識別子が重複しない
- shard B が shard A 割当クラスを実行していない
- 期待 skip が両 shard のどちらかに存在し、実際に skip されている
- fast + integration の和集合が full と一致し、結果と理由も一致
- 既存の pass が skip に変わっていない

`config/test-expected-skips.json` に理由付き skip を列挙し、`Suite::Method` の完全一致で照合する。
**評価**: 「テストを減らして緑にする」抜け道を塞いでいる。良い設計。
