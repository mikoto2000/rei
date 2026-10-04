# Repository Map 実装レポート

C18のGit/Java構造索引を実装。既存ToolsのProject/Run rootと汎用process runnerを再利用した。
標準JDK ASTでpackage/型/method/import/main、module path候補、テスト名候補を返す。
内容ハッシュで更新し、削除・Project切替を反映。読み取りToolとして公開する。

TDDで未実装時のcompile失敗を確認した。comment/string誤検出防止、更新/削除/root切替、
秘密名/escape/巨大ソース/構文エラー、cancel、実Git ignore、Tool登録/READ分類を検証する。
全体回帰とmerge後の対象テストも実施。実LLMや外部Codexの起動は不要。

多言語AST、完全な型解決、module依存、coverageベースのtest対応、永続index、watcherは未実装。
上限と不完全性は [利用説明](repository-map.md) に記載した。

全体回帰で既存ExternalAgentCommandTestのTool登録順依存が再現したため、requestCodexReviewを名前で選ぶテストへ修正した。

検証結果: full profile 2806 tests / 539 suites、failure/error/skip 0。
