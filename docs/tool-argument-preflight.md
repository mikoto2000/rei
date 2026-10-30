# ツール引数の事前検証と補正

`proposeTextChangeSet` の `request.path` 欠落は、以前は native callback が
`IllegalArgumentException` を送出し、実行全体が `invalid_arguments` で終了していた。
現在は callback の実行・承認要求・提案保存より前に入力を検証し、
`INVALID_TOOL_ARGUMENTS` / `executed:false` と修正対象のフィールドをモデルへ返す。
パスを推測して埋めることはせず、モデルが修正した呼び出しだけを通常の権限制御へ戻す。

## 横展開の範囲

| 対象 | 事前検証 |
| --- | --- |
| 単一・複数文書の提案 | request 内のパス、編集方式、SHA-256、hunk、操作種別とその組み合わせ |
| 基準読み取り・保存済み提案の照会/差分/適用/破棄/復旧 | パスまたは ID、必要な保存済みハッシュ、差分ページ範囲 |
| writeMultiFile / applyTextDiff | パス、本文、件数・文字数上限、文字コード、空の置換文、任意項目の既定値 |
| createDirectories / writeBinaryFile / deleteFile / copyFile / moveFile | パス、明示的な boolean、バイナリ入力形式 |
| その他の登録済み callback | 自身の JSON Schema に宣言された必須項目と型、入れ子の必須項目 |
| 既存の検索・ファイル読み取り・待機・委譲・タスク状態ツール等 | 従来の専用検証も継続 |

共通検証は登録された契約だけを参照し、リモートの schema を取得しない。
任意の null 値や不透明な schema、union の解釈は既存ツールへ残す。
全文書き込みの append / charset と applyTextDiff の charset は、既存の既定値に合わせ
schema 上でも任意項目として宣言する。

## 継続と停止の境界

補正は既存の run 共通上限を消費し、ツール変更や再開で回数を補充しない。
同じ応答で先に成功した兄弟呼び出しは保持し、再実行しない。
補正応答は通常のツール結果として返し、本文読み取りの完了証跡にはしない。

実行済み callback の例外を一括で補正扱いにはしない。
I/O 障害、権限拒否、所有者・保存状態・対象範囲の不一致、結果不明の Apply は
従来の停止・明示復旧経路を維持する。変更対象や保存 ID / ハッシュを捏造しない。
共通検証は完全な JSON Schema validator ではなく、全ツールの意味的な失敗が
自動修復されることを保証しない。

## 回帰テスト

`EditToolArgumentRepairTest` は、実際の Method callback、イベント・配信 wrapper、
モデル補正ループと隔離した SQLite を使う。パスの欠落/null/空白/型違い、複数文書の
操作種別誤りから正しい提案へ続行し、不正呼び出しが保存・実行されないことを検証する。
ガード有無、兄弟呼び出し保持、他ツール countFileLines の補正、共通上限の枯渇、
実行中例外と書き込み拒否の維持も検証する。
さらに native callback 全体の必須契約について空の引数を検査する。

`TextEditArgumentsTest` と `ToolSchemaArgumentsTest` は編集方式、既定値、バイナリ形式、
入れ子・配列・ローカル参照、入力本文をエラーへ漏らさないことを検証する。

2026-10-30 の検証では関連 1,808 件を実行し、模擬入力の修正後に影響範囲の
389 件を再実行した。クラスごとの最終結果は失敗・エラー・スキップとも 0 件。
アーキテクチャ検査は通過し、Checkstyle の違反も 0 件だった。
