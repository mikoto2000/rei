# SubAgent Structural Validation Phase 1 実装報告

## 調査結果

- 既存フローは `SubAgentTools.delegateTask` → `SubAgentRunner` → `BoundedToolLoop` → 自由文の `SubAgentResult.output`。実行状態と開始・終了時刻を持つ返却型が既にあったため、その役割を維持した。
- `SubAgentDefinition` は Java record。YAML に ID、名前、説明、systemPrompt、要求 Tool、任意 model、maxSteps、timeout を記載し、Loader が安全性を検査、Registry が全件成功時のみ snapshot を置換する。
- 実際の基盤は Java 25、Spring Boot 4.0.4、Spring AI 2.0.0-M3。computer-use には OpenAI `ResponseFormat.JSON_SCHEMA` の利用があるが、SubAgent は provider 共通の Tool loop と fallback を使う。対応モデルを前提にしないため、今回は JSON 指示と必須ローカル検証を採用した。
- `com.networknt:json-schema-validator:3.0.0` は既に Spring AI → MCP Jackson 3 の推移依存として存在した。Draft 2020-12 と Jackson 3 を使える既存ライブラリを再利用し、直接利用する API の依存関係を pom に明記した。異なる validator や追加バージョンへの変更はない。[ライブラリの公式リポジトリ](https://github.com/networknt/json-schema-validator)。

## 設計

| 型 | 責務 |
| --- | --- |
| `SubAgentResult` | 既存の実行状態・raw 検証済み JSON・時刻。`structuredOutput` と `validationErrors` を追加 |
| `SubAgentOutput` | 共通 envelope。SUCCESS / FAILURE / PARTIAL、summary、agent 固有 object、warnings。リストと JSON の防御的コピー |
| `SubAgentResultParser` | 文字列を厳密に JSON parse。Schema 判定・補正・抽出をしない |
| `SubAgentResultValidator` | 共通 envelope、続いて個別 result Schema を検証 |
| `ValidationResult` | valid と immutable な errors。両者の整合性を保証 |
| `ValidationError` | JSON Pointer path と値を含まない診断メッセージ |
| `SubAgentValidationException` | parse／検証エラーを構造化して伝播。raw output やライブラリ例外を保持しない |
| `SubAgentResultSchema` | 安全なリソース読み込み、meta-schema 検証、コンパイル、定義の存続期間中の保持 |
| `SubAgentOutputPrompt` | ローカル検証と同じ Schema を使って JSON 出力指示を組み立て |

Runner の Tool loop 終了後、正常完了の確定前に parse → common validation → agent-specific validation を実行する。
検証失敗は既存の `FAILED` と `subagent.failed` に接続し、正常な output として渡さない。
エラー情報は親の Tool 応答にも JSON として残る。ログには agent ID、検証失敗、エラー件数のみを追加した。
構造が正しい envelope の FAILURE / PARTIAL は子のタスク状態であり、外側の実行状態は COMPLETED とする。
timeout の対象には検証処理も含め、キャンセル済み実行の結果は採用しない。

Schema のロードは YAML の `resultSchema` で指定する。相対 JSON ファイルは定義ディレクトリ内に実体がある通常ファイル、
classpath は `/subagents/schemas/` 配下のみを許可する。外部参照をロードする機能は無効化し、同一文書内の `$defs` / `$ref` は利用できる。
Schema 自体の不備は runtime output failure ではなく definition failure。reload が失敗しても既存 snapshot を維持する。
エラーを無制限にキャッシュせず、正常なコンパイル済み Schema を定義に保持する。

サイズ上限は raw JSON 1,048,576 UTF-16 code units、ネスト 100、数値トークン 1,000 文字、Schema 256 KiB。
診断は最大 100 件、path は最大 256 文字＋省略記号。出力全文や enum の不正値を診断メッセージへコピーしない。
既存ストリーム集約後に parse 上限を適用するため、ストリーム集約そのものに新たなメモリ上限を設けたわけではない。

## 互換性・移行

`resultSchema` は任意で、省略時は共通 envelope のみを検証する。既存 Java コンストラクタも残した。
Runner が全定義へ JSON 指示を追加し、同梱 reviewer / researcher はそれぞれの Schema に適合する prompt へ移行した。
既存 YAML を一律に設定エラーにせず、かつ検証を迂回させないため、この方式を採用した。
カスタム prompt に自由文や Markdown を強制する指示があれば更新が必要。自由文出力を正常扱いする opt-out はない。
詳細は [利用手順](subagents.md) を参照。

## TDD と検証

先に Parser / Schema のテストを作成し、Parser 未実装による Red を確認した。
Runner は新規テスト 2 件の Red（不正 JSON を COMPLETED として返す／JSON 指示がない）を確認してから統合した。
Green 後、出力指示の責務を Validator から分離し、既存と新規の関連テストを再実行した。

追加した実行ケースは 67 件。既存の自由文を使う成功テストは JSON envelope fixture に移行し、履歴分離・Tool 制限・イベント・キャンセルの検査を維持した。

| 対象 | 新規ケース | 主な検証 |
| --- | ---: | --- |
| Parser | 13 | 正常 JSON、非 object は parse のみ成功、不正 JSON、前後の自然言語、code fence、複数 JSON、重複キー、サイズ・深さ制限 |
| Validator / Schema load | 50 | 全必須項目、型、enum、空 summary、未知項目、配列要素、個別 Schema、path、機密値非露出、診断上限、Schema 不備、参照拒否、reload 保持 |
| Runner | 3 | 不正 JSON／共通 Schema 違反の failure、正常 delegation、個別 Schema 違反、JSON 指示、再試行なし |
| Tool callback | 1 | typed envelope と structured errors の JSON シリアライズ |

実行コマンド:

```text
./mvnw.cmd -q -Dtest=SubAgent*Test test
./mvnw.cmd -q test
cd client && npm test -- --reporter=dot
cargo test --manifest-path client/src-tauri/Cargo.toml --locked
cd client && npm run test:e2e
```

Java 全体は 399 スイート・1,908 件、失敗 0・エラー 0・スキップ 0、Maven 終了コード 0。
うち SubAgent 関連は 91 件。クライアント単体テスト 39 件、Rust テスト 64 件もすべて成功した。
ブラウザ E2E は desktop / mobile 合計 10 件成功。Windows のテスト用 Vite サーバー終了待ちが残ったため、
当該テストが起動したプロセスであることを確認して終了した。E2E コマンドも終了コード 0 を確認した。
再試行、自動修復、semantic validation、reviewer agent、confidence score は実装していない。

## Git

専用ブランチ: `codex/subagent-result-validation`。
コミットメッセージ: `feat(subagent): validate structured results before delegation returns`。
commit hash と最終 working tree 状態はコミット完了後に報告する。
