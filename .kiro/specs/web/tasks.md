# Web API Phase 2〜4

## Phase 2

- [x] 仕様・Phase 1・既存コマンドの責務調査
- [x] feature/web-api-phase2 を main から作成
- [x] Red → Green → 共通 query service のリファクタリング
- [x] DTO／共通エラー／実 HTTP 認証・非公開操作・契約テスト
- [x] Java 全2589テスト成功（failure/error/skip=0）
- [x] Client 全42テスト成功
- [x] Native Client cargo test 全テスト成功
- [x] 仕様・phase2-report.md 更新、対象変更のみコミット

## Phase 3

- [x] 共通 Run API に summary/image を統合して TDD と全テスト
- [x] Java 全2593テスト成功（failure/error/skip=0）、Client 全42成功、Native Client 全66成功
- [x] 仕様・報告・feature/web-api-phase3 コミット

## Phase 4

- [x] 既存ドメインの操作単位で write API を TDD と全テスト
- [x] SQLite 永続化・実 HTTP・未知フィールド拒否・所有不明 scope 拒否・冪等性を検証
- [x] Java 全2596テスト成功（failure/error/skip=0）、Client 全42成功、Native Client 全66成功
- [x] V1DtoContractTest で Phase 2〜4 response DTO の JSON field 名を固定
- [x] 仕様・報告・feature/web-api-phase4 コミット

検証コマンド: Java は JAVA_HOME=C:\Java\jdk-25 で mvnw.cmd test、Client は npm test、Native Client は cargo test。外部 LLM 実呼出、ブラウザ E2E、native GUI E2E は未実行。各 Phase の検証は開始時の6ファイルの未コミット変更を保持した作業ツリー上で実行した。
