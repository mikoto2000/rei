# 朝・夕ミーティングの改善

## 実装前の調査（main 3d43b6f8）

`SecretaryFollowups.report` の `sections` は `SecretaryLedger.list` のみを分類していた。一方、Work Context、Scheduler、接続キャッシュ等は `SecretaryContext` から別の `context` に追加され、セクションには変換されなかった。Work Contextだけが多数あって台帳が空なら、既存セクションが空になる。プロジェクト集約での欠落や日付フィルタだけが原因ではない。

既存の Work Context adapter は項目ID・本文・種類・信頼性・根拠IDを渡すが、更新時刻とRunの関連を落としていた。また `next` はLifePolicyで利用可能時間を確認した場合だけの準備候補で、一般的な今日の優先順位とは異なる。

既存実装済み: 台帳の期限・状態別分類、依存条件の観測と定期照合、Goal完了検証、Scheduler、通知保留、全体/独立Projectの集約、JSON API、生成スナップショットの永続化、生活・祝日制約。
不足: 保存文脈から会議への変換、正規状態との読み取り照合、短い人間向け表示、利用者が確定した引き継ぎの保存。

## 共通Projectionと状態

既存の `sections` は台帳タスク型の契約と自動提案側の利用を維持する。保存文脈を架空の台帳タスクとして挿入せず、APIに追加した `meeting.sections` / `meeting.text` を通常表示に使用する。`context` / `evidence` と既存レスポンス項目は残る。

`SecretaryMeeting` が朝夕共通で作業を整理する。タイトルの類似では結合しない。同じ明示Work ID、または一意なRun関連だけを結合し、曖昧なRun関連は残す。Projectをまたいで結合しない。元の根拠と `FACT` / `USER_REGISTERED` / `INFERENCE` / `PROPOSAL` / `UNKNOWN` を保持する。

Work Context の保存宣言には、現在の作業全体の状態を検証した保証がない。過去の `BLOCKER` / `PENDING` / 完了宣言は `UNKNOWN` として表示し、ツール成功だけでは完了にしない。同じRunに結びつく正規Goalがあれば現在状態を優先する。Goalの既存完了ゲートを再実装しない。秘書台帳のDONEは利用者登録状態として扱う。明示依存参照は現在の依存ストアを読み、待機解除・阻害・状態不明をProjectionに反映する。読むだけでは台帳を更新しない。

当日の更新は設定タイムゾーンの半開区間で判定する。過去の完了履歴は当日の完了に混ぜず、古い未完了作業は継続候補に残す。朝の優先候補・夕の引き継ぎ候補は、期限、前夕の確定引き継ぎ、明示優先度、進捗の古さ、安定IDで決定し、Project全体でも最大3件にする。各見出しも最大3件、同一作業の通常表示は重複を省く。原本は従来の詳細表示で確認できる。

## 短い対話と確定

```text
/secretary briefing
/secretary review
/secretary review --project PROJECT
/secretary review --json
/secretary briefing-show GENERATION_ID --json
```

朝は予定・期限、前日の引き継ぎ、優先候補、待機・確認事項。夕は完了、進展、未完了、待機、翌日候補の順に表示する。候補がなければ質問せず終了する。通常表示ではcontextやツールログの巨大JSONを出さない。APIはJSONのままでGUI等の既存利用を壊さない。新規LLM呼び出しはない。

表示の「選択ID」を使って、残す候補を指定する。全体表示の確定はProjectを省略する。Project限定の確定では `--project` を指定する。`--key` は再送用の一意キーで、修正して再確定する場合は別キーを使う。

```text
/secretary meeting-confirm --review --key evening-20261031 --select PROJECT::run:RUN_ID
/secretary meeting-confirm --key morning-20261101 --select PROJECT::secretary-task:TASK_ID
/secretary meeting-confirm --review --key evening-end-without-selection
/secretary meeting-confirm --project PROJECT --key morning-project --select secretary-task:TASK_ID
```

選択は0〜3件。省略は「何も選ばず終了」であり、自動的に全候補を選択しない。確定後は同じ日の同じ朝夕ミーティングで質問を繰り返さない。確定は方針の保存であり、実行・予定登録・承認・タスク作成ではない。

`POST /api/v1/secretary/meetings/confirm` は既存Bearer認証下のAPI。body例:

```json
{"date":"2026-10-31","review":true,"key":"evening-20261031","selected":["PROJECT::run:RUN_ID"]}
```

`projectId` を省略すると全体、指定するとそのProjectの方針を保存する。全体確定はその時点の集約対象Projectを保存し、独立Projectや後から追加されたProjectを勝手に確定しない。選択IDはProject所有と現在の候補を照合する。取消・完了等で候補から外れたIDは新規確定できない。同キー・同内容は保存済みreceiptを返し、内容変更は競合になる。保存失敗は成功として返さない。

既存 `secretary_briefings` テーブルの会議決定専用scopeに最新100件を保持する。生成スナップショットやタスク原本とは別scope。破壊的移行も新しい実行エンジンもない。翌朝は前日の夕方の確定IDを取り出し、最新の台帳・Goal・保存文脈に照らし、既に完了/取消の作業を除外する。履歴スナップショットの閲覧は取得時点の表示を保つ。

## 予定・勤務・安全性

予定が取得できない場合と、鮮度が確認できた登録済み私用カレンダーに予定がない場合を区別する。既存のopt-in接続キャッシュと同期状態を読み、ミーティング表示のための外部通信は追加しない。Schedulerの登録済み予定も予定欄で示す。予定は作業進展や完了には数えない。

勤務中の業務予定は不明。空白を自由時間に変換せず、時間帯への配置も自動登録もしない。従来 `next` のLifePolicy・勤務・休息・祝日判定、実行承認、通知制約は維持する。`meeting.priorities` は時刻への割り当てを伴わない優先順位の提案である。

## 手動確認

1. 秘書機能を有効にした既存環境で `/secretary review` を実行する。
2. Work Contextがある場合、会議候補と状態未検証・根拠の分類を確認する。
3. `--json` で元のcontextと `meeting.sections` が共存することを確認する。
4. 上記 `meeting-confirm --review` で最大3件を確定し、保存成功後に終了と表示されることを確認する。
5. 翌日 `/secretary briefing` を実行する。テスト用環境では `--date 2026-11-01` 等で翌日を指定してもよい。現在ストアの状態を使うため、過去時点の完全再現ではない。
6. その間に完了・取消した作業が前日の引き継ぎから外れることを確認する。
7. 朝の `meeting-confirm` で確定し、同じ朝の再表示で再質問しないことを確認する。

固定時刻のテストは `SecretaryMeetingTest`、`SecretaryMeetingPersistenceTest`、`SecretaryMeetingOutputTest`。420件・2Projectの生成fixtureには個人情報・実環境IDを含めず、外部サービスや実LLM接続を必要としない。
