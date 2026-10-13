# 起動時・プロジェクト選択時の再開候補

Shellと`terminal/cli`は、最初の接続時にプロジェクト別の再開候補件数を表示し、選択中プロジェクトの最新1件を表示する。プロジェクト選択でも同じ表示を使う。候補がなければ表示しない。Backendのヘッドレス起動は利用者向けの案内を出さない。

```text
前回からの再開候補:
  rei  3 件
Project: rei
最新の再開候補（全3件）

  依頼: ファイル読み取りの効率化
  保存: 2026-10-13 18:42 JST
  状態（保存時）: INTERRUPTED
  完了: 重複読み取りの削減
  残り: 性能テスト・回帰テスト
  現在: 再開前の照合が必要

  詳細: /resume show 550e8400-e29b-41d4-a716-446655440000
  再開: /resume 550e8400-e29b-41d4-a716-446655440000
ほか2件: /resume list
```

上のIDと内容は表示形式の例。実際には保存済みtaskIdを使用する。`Switched project: rei`の後にも同じ詳細が続く。前回表示と同じプロジェクトへ戻ると、件数と`/resume list`だけを表示する。同じプロジェクトの再選択やSession変更では詳細を繰り返さない。checkpointのrevision、候補件数、状態、破損・フォールバックなどが変われば詳細を再表示する。`/resume overview`は現在のプロジェクトを強制再表示する。

## 判定と安全性

主データは既存checkpointだけ。DBテーブル・checkpoint形式・既存コマンドの動作は変更しない。新たな永続表示履歴は作らず、各Shell/CLIインスタンスが独立したメモリ上の履歴を持つ。CLI再接続は新しい履歴を使う。HTTPで選択プロジェクトを共有しない。

- INTERRUPTED、WAITING、WAITING_APPROVALを対象とする。
- FAILEDは最後の保存から30日以内を自動表示する。UNKNOWN操作や破損がある場合は日数で隠さない。
- 有効な実行所有権を持つRUNNINGは再開候補にしない。所有権を失ったRUNNINGは保存時の状態を維持したまま「中断候補」と説明する。STARTED操作も結果未確認と扱う。
- COMPLETEDはUNKNOWN操作がある場合だけ要確認候補とする。CANCELLEDとABANDONEDは除外する。
- 破損・非対応の最新revisionは既存のdecoderで過去の正常revisionを探し、参考情報と明示する。正常revisionが一つもなければ保存状態を未確認と表示する。保存日時はDBの保存記録を使い、依頼・作業内容を推測しない。

完了作業は保存されたplanのDONE工程を使う。工程がなければASSISTANT_CLAIMを「保存された申告」と明記して使う。TOOL_CONFIRMEDなどの生出力は自動表示しない。残作業は保存されたnextActionを使い、欠けた情報は「未記録」とする。LLM要約・照合・再開・承認・ツール実行・checkpoint更新は行わない。既存Resumeの安全機構は変更しない。

文字列はANSI/OSC・制御文字を除去し、CredentialRedactorによるマスキング後にUnicodeコードポイント数で短縮する。依頼80、完了/残作業120文字。端末制御や命令として解釈しない。taskIdは端末・コマンドに安全なASCII識別子だけを受け入れる。CLIはさらに接続APIキーをマスクし、既存のJLine `printAbove`を使用する。

共有CredentialRedactorのJSON秘密文字列パターンは、長い文字列やエスケープの連続で再帰的バックトラックしない形を使用する。10万文字の秘密文字列も、短縮前にマスキングできることを回帰テストで確認している。

Work Contextは補助的な保存背景情報としてcheckpointの後に表示する。Shellの自動表示は`presentSaved`を使い、Gitを取得しない。checkpointからWork Contextへの複製保存や状態の上書きは行わない。手動のWork Context経路は変更しない。

## APIと設定

既存のBearer認証で保護された読み取りAPI:

- `GET /api/v1/resume-overview`: 自動表示設定、明示設定されたタイムゾーン、登録プロジェクトの件数。
- `GET /api/v1/projects/{projectId}/resume-overview`: 正確な件数、最新1件の安全なDTO、SHA-256フィンガープリント。未知のプロジェクトは404。

選択状態はクライアント内で持ち、APIはprojectIdを明示して呼ぶ。savedAtはISO Instant文字列。利用者が既存`rei.activity.zone`を明示設定していればそれを優先し、それ以外はShell/CLIのシステムタイムゾーンで表示する。JST固定ではない。

```yaml
rei:
  resume:
    overview:
      enabled: true
      failed-max-age-days: 30
```

環境変数は`REI_RESUME_OVERVIEW_ENABLED`と`REI_RESUME_OVERVIEW_FAILED_MAX_AGE_DAYS`。件数は1件固定。無効化は自動表示だけに作用し、`/resume overview`や既存Resumeは利用できる。読取失敗は自動表示を省略して起動・切り替えを継続し、手動コマンドには失敗を知らせる。

## SQLite取得経路

checkpoint_headsのtask単位で最新revisionへ主キー結合する。正常headの対象判定、COUNTウィンドウ集計、保存日時（ナノ秒を含む）降順・同時刻taskId順、最新1件の選択をSQLで行う。通常のJava側には1件の小さな表示項目しか返さない。全revisionのJavaデコードは行わず、破損headだけ既存fallback decoderを呼ぶ。所有権確認は既存のPIDと開始日時判定を再利用する。

実SQLiteのEXPLAIN QUERY PLANでは`checkpoint_heads(project)`と`checkpoint_revisions(project,task,revision)`の既存主キーインデックスを使用する。全revisionテーブルの走査はない。正確な件数・JSON状態判定のためプロジェクト内のheadを調べ、並べ替えには一時B-treeを使う。LIMIT 1で走査やソートがなくなる設計ではない。追加インデックスや手動DB移行は不要。

破損headが大量にある場合は過去revisionのdecoderによる調査が増える。自動案内は参考表示であり、厳密な整合性確認は既存`/resume show`とResume実行に委ねる。
