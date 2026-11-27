# 自発的行動の実装状況

Phase 1 は、Bluesky 投稿案を検討・保存・管理する Backend 基盤です。
LLM による生成、GUI・Shell の操作、通知、定期実行、承認付き公開は後続 Phase です。
この段階では設定を有効にしても、LLM 呼び出しや外部公開は開始しません。

## 設定

```yaml
rei:
  autonomous-action:
    enabled: false
    bluesky:
      enabled: false
      cron: "0 0 12 * * *"
      zone: "" # 空なら既存 Clock の zone を継承
      max-drafts-per-day: 1
      max-pending-drafts: 1
      cooldown: 1h
      draft-expiration: 72h
      max-regenerations: 3
      regeneration-cooldown: 1h
      system-prompt: |
        あなたは AI 秘書のれいです。
        公開許可された話題から投稿案を考えてください。
        話題がなければ SKIP を返してください。
```

設定は起動時に読み込みます。不正な cron・zone、正でない生成/保留上限・期間、
負の再生成上限、空または過大な prompt、未知の設定項目を拒否します。
検討頻度と生成上限は独立しています。設定によって公開権限は付与されません。

## 基盤の責務

- `AutonomousActionPolicy` は現在の事実だけを評価し、生成を検討してよいかを返します。
  LLM・投稿サービス・承認処理を持ちません。
- `AutonomousDraftStore` は既存 `StorageDatabase` の transaction を利用し、機会 ID、
  実行中 claim、日付境界、pending 上限、試行 cooldown を永続状態で検証します。
  手動・定期の契機は同じ制約を通ります。
- 本文・SHA-256・reason・source IDs は immutable な revision として保存します。
  編集は新 revision を追加し、過去 revision を書き換えません。承認・送信情報は初期値 null です。
- 見送り理由は既定で非公開です。保存しただけでは LLM 用の情報源になりません。
- 期限切れと見送りは編集できません。古い revision による操作を拒否します。
- 起動時に残った実行中の検討は ABORTED とし、監査を残します。自動で再生成しません。
  既存 `application.shutdown.started` で新規受付を停止し、停止後の生成結果は保存しません。

## 保存と移行

storage.db の schema 23 で、`autonomous_opportunities`、`autonomous_drafts`、
`autonomous_draft_revisions`、`autonomous_audit` と必要な indexes を追加します。
既存の起動前 migration・検証済み backup・data directory lease を再利用します。
機会 ID の一意制約、同時実行中の機会を一件に制限する unique index、状態・revision の
制約を持ちます。移行だけでは機能を有効化しません。

本文と revision は短い同一 transaction に保存します。Project/Run 所有の ArtifactStore に
仮の Run を作成せず、今回の本文保存には使用していません。
一般の監査行には ID、操作、reason code、日時を保存し、本文やシステム prompt は複製しません。

## 安全性と後続の接続

自発処理は最終的にもドラフト保存と確認通知までとし、外部公開は人間の明示操作で
別に実行します。通常の Tool 自動承認を投稿承認として使いません。
承認 UI は既存機構の再利用を優先し、新しい本人確認方式や OS サービスの追加を必須にしません。
同じ OS 権限と API キーを持つ任意プログラムを識別できない既存の信頼境界は、
ドラフト保存によって解消したとは扱いません。公開段階の保証と残存する前提は
[Phase 0 設計](autonomous-action-design.md) を参照してください。

内部状態の具体的なパラメータは追加していません。将来は policy の context と
生成用の公開可能な表現情報に接続し、保存・上限・承認・送信の安全条件から分離します。

## 検証

時刻を制御した policy・settings・Unicode 本文制限・永続化・再起動・編集・見送り・
期限・同時起動・停止イベント・移行のテストを追加しています。
新しいテストは通常の JUnit として Maven/CI から実行します。
開発中は同じ JUnit を対象クラスだけコンパイルする補助 runner でも実行し、
拒否条件の Red → Green を短い周期で確認しています。補助 runner は製品に含めません。
外部サービスや実アカウントへの投稿は検証に使用しません。
