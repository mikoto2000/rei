# Activity Timeline Phase 3.5: Behavior Evaluation

Activity Timeline単体の観測から、SNS・動画等の長時間の傾向について「れい」が短く声をかける補助機能。
デフォルト無効。Task、Calendar、締切、Working Set、未完了project、Productivity Scoreは参照しない。
「仕事でないから遊び」「予定が残っているから注意する」という評価ではない。

## 入力と責務

```text
Fine-grained ActivitySession + 対応するActivityRecord
  → BehaviorEvaluator
  → BehaviorAssessment
  → BehaviorNotificationPolicy
  → BehaviorNotification
  → LlmBehaviorMessageGenerator
  → 既存AgentMessagePublisher / messageイベント / 会話履歴
```

Evaluatorは時刻・設定・詳細セッション・対応する元Evidenceだけを入力とする純粋ロジック。
LLM、UI、SummarySegment / TrendSummarySegment、外部ツールには依存しない。
既存ActivitySessionは全モニターの候補を持ち、観測区間の内訳やPrimaryを直接保存していないため、
`recordIds`が参照するRecordに既存ActivityRolePolicyを適用してPrimaryを判定する。
セッションへ所属しないRecord、曖昧・低確信度のPrimary、背景だけに見える動画は娯楽に加算しない。

Recordの推定区間をセッション・次の観測・評価時刻・履歴範囲でクリップし、重複時間を除去する。
さらに各詳細セッションの`observedSeconds`を上限にする。Evidenceが不足するときに壁時計の幅で補完しない。
既存Record・Session・roles・画像・保持期間は変更しない。新しいスクリーンショット取得・保存もない。

## カテゴリと時間

娯楽対象は `social` / `media` / `shopping` / `gaming`。gamingは抽出プロンプトに存在したが
canonical vocabularyから漏れていたため追加した。設定ではこの4つの部分集合を選べる。
development / research / documentation / communication / monitoring / navigationを娯楽とはしない。
Slack / Discord / ChatGPTというサービス名だけで娯楽に振り分けない。

`eligibleObservedDuration`はPrimaryを判定できた観測時間のうちunknown / other / idle以外。
communication / monitoring / navigationもeligibleだが娯楽には加算しない。
unknown / other / idle / 未観測は分子にも分母にも入れない。最小eligible観測量を別に要求し、
少数サンプルだけによる100%の割合で通知しない。

連続娯楽時間は対象カテゴリ内の切替をつなぎ、**観測推定秒数だけ**を合計する。
X→YouTube→Xでリセットしない。短いunknown・未観測gapは累積60秒まで連続性を許すが、
その秒数は娯楽に足さない。60秒超の欠測・unknown等やcontinuityId変更は連続時間をリセットする。
これらは作業復帰の証拠ではないため、通知cooldownの回復には使わない。

既知の非娯楽活動（work系を含む）が累積5分観測されると、連続時間とepisodeを回復させる。
5分未満の既知非娯楽区間は中断候補として保持し、娯楽時間へは足さない。例えばmonitoring 20秒も
連続性を壊さない。回復時刻は5分に達した観測時点で固定する。unknown等が長く続く場合はこの
中断候補もリセットし、「不明だったから回復した」とはしない。

連続判定の既定閾値:

| 観測された連続娯楽時間 | Severity |
|---|---|
| 30分未満 | NONE |
| 30分以上 | NOTICE |
| 60分以上 | WARNING |
| 120分以上 | STRONG_WARNING |

時間窓は評価時点から遡る半開区間。割合=`entertainmentObservedSeconds / eligibleObservedSeconds`。

| Window | 最低eligible観測量 | 娯楽比率 | Severity |
|---|---|---|---|
| 直近60分 | 30分 | 50%以上 | NOTICE |
| 直近120分 | 60分 | 60%以上 | WARNING |

連続時間と両windowの最大Severityを最終評価にする。29分socialだけでは、比率が100%でもNONE。
60分の時間幅に20分しかsocialが観測されていなければ、娯楽時間は最大20分。

## Assessmentと通知判断

BehaviorAssessmentはseverity、enum reason（NONE / CONTINUOUS_ENTERTAINMENT /
ENTERTAINMENT_RATIO_HIGH / BOTH）、evaluatedAt、continuousEntertainmentSeconds、両windowの
durationMinutes / entertainmentObservedSeconds / eligibleObservedSeconds / entertainmentRatio / severity、
dominantCategories、services、confidence、最新観測時刻、現在娯楽を観測しているか、回復時刻、
episode開始時刻、回復後のepisodeが通知閾値を満たしたかを持つ。
confidenceは娯楽Primaryの確信度を観測秒数で重み付けした値。無関係な高確信度のworkで上乗せしない。

BehaviorNotificationPolicyは文章を作らず、通知可否・抑制理由・更新する状態・cooldown期限を返す。

- 同じepisodeの同一・低いSeverityは、理由がCONTINUOUSからBOTH等へ変わってもcooldownを守る。
- cooldownは直前の通知予約のSeverityからNOTICE 60分、WARNING 45分、STRONG_WARNING 30分。
- 直前に通知したSeverityより高くなった場合はcooldown中でも通知可能。上下動による同じWARNINGの連発はしない。
- 十分な既知非娯楽の観測でepisodeを回復させる。回復通知は行わない。
- 回復後、過去のwindow比率だけで即座に再通知しない。回復以後の連続時間またはwindowが閾値を
  再び満たす必要がある。評価そのもののwindowは変更せず、通知資格だけを別に計算する。
- 現在の最新Primaryが娯楽でなければ通知しない。最終観測区間からnoise toleranceより長く経過した
  古いデータでも通知しない。Chat/Agent実行中は抑制し、次の評価で再検討する。

## 実行・永続化・失敗時

専用の1 worker・queue 0のSpring schedulerを使い、既定60秒ごとに評価する。CaptureとChatとは別のworker。
behavior無効、Activity Capture無効・pause中は自動評価もLLMも実行しない。workerが忙しい場合は積み残さない。
LLM呼び出し中にserviceのmonitorを保持せず、`off`やChat開始を妨げない。
送信直前にもActivityを再評価し、回復・娯楽終了・Severity低下・Chat開始・off等があれば結果を破棄する。

既存DataSourceに小さなSQLiteテーブルを遅延作成する。

- `activity_behavior_state`: 1行。episode ID、確認済み回復時刻、最終通知予約時刻・Severity・reason、直近Severity。
- `activity_behavior_transitions`: 最大100件。Severity変更・episode開始/回復・通知予約など状態が変わった
  ときだけAssessmentを保存。毎分の同じNONE等は書き込まない。

通知許可後、**LLM呼出前に予約を保存**する。再起動・LLM障害・配信直前のキャンセルでもcooldownを維持し、
重複通知を避ける。その代わり生成失敗時はその回の通知が欠け、次のcooldown満了またはescalationまで再試行しない。
状態読み込み・保存に失敗したら通知しない。保存はcheckpointと履歴を1 transactionで更新する。
新たなraw画像・タイトルは保存しない。ログは例外型だけにし、モデル応答や画面由来の値は出さない。
評価・policy・DB・LLM・配信の失敗は補助worker内で隔離し、Activity CaptureやChatを停止しない。

イベントtaxonomyを増やさず、発話は既存AgentMessagePublisherを通じてmessage.started / delta / completedと
会話履歴へ届ける。内部originはBEHAVIOR。評価・抑制状態はstatusと小さな永続stateで確認する。

## LLMの責務

`SystemPromptService`の既存キャラクタープロンプトを再利用し、Behavior用の指示を追加する。
コードが決めたSeverity・理由・観測時間・比率・カテゴリ・サービス・confidenceの小さなJSONだけを渡す。
画面、Raw Evidence、タイトル、会話履歴、Task、Calendar、Working Setは渡さない。
tools / tool callbacks / chat memory / advisorsは使わず、ツール実行を無効にする。

NOTICEは軽く、WARNINGは少し明確に、STRONG_WARNINGははっきり区切りを促す2〜3文。
人格評価・侮辱・羞恥を避け、観測と実操作を混同せず、締切や未完了作業を創作しないよう指示する。
confidence 0.7未満ではサービス名を入力から外し、不確実性を明示するよう指示する。
空出力・長すぎる出力・生成上限到達・tool call・代表的な侮辱表現は配信しない。
これはLLM出力の完全な意味検証ではなく、実モデルでのトーン・忠実性の確認余地は残る。

モデルは `rei.llm.features.activity-behavior` で指定可能。未指定なら通常の既定LLM。
画面抽出用の `features.activity` とは独立し、カスタム接続先から別サーバーへの自動fallbackはしない。

## 設定とコマンド

以下は全既定値。`/config init`の新規テンプレートにも含まれる。既存設定を自動上書きはしない。

```yaml
rei:
  activity:
    enabled: false
    behavior:
      enabled: false
      check-interval-seconds: 60
      history-minutes: 1440
      entertainment-categories: [social, media, shopping, gaming]
      continuous:
        notice-minutes: 30
        warning-minutes: 60
        strong-warning-minutes: 120
      windows:
        short-window:
          duration-minutes: 60
          minimum-observed-minutes: 30
          ratio: 0.50
        long-window:
          duration-minutes: 120
          minimum-observed-minutes: 60
          ratio: 0.60
      interruption:
        reset-after-work-minutes: 5
        noise-tolerance-seconds: 60
      cooldown:
        notice-minutes: 60
        warning-minutes: 45
        strong-warning-minutes: 30
  llm:
    features:
      activity-behavior:
        # base-url / api-key / model / max-output-tokensは既存feature設定と同じ
        model: ${REI_LLM_ACTIVITY_BEHAVIOR_MODEL:}
```

`history-minutes`は読み取る履歴の上限（既定24時間、最大7日）。全window・strong warning閾値以上が必要。
それより長い連続娯楽の表示時間は、この読み取り範囲内の下限値となる。
閾値の順序、正の時間、比率0超〜1以下、最低観測量≦窓幅、不明カテゴリの娯楽指定等を検証する。

```text
/activity behavior on
/activity behavior off
/activity behavior status
/activity behavior evaluate
```

on/offはこの起動中のoverride。再起動時はYAMLのenabledに従うが、cooldown/episodeは保持する。
statusはenabled、Severity、連続観測分、cooldown期限、直近通知状態を簡潔に表示。
評価前のSeverityは保存値と明示する。evaluateは無効中でも明示操作として実行できるdry runで、
現在の評価と両windowを表示するがLLM・通知・episode更新・履歴保存は行わない。サブコマンド補完にも対応。

## Fixture例とPhase 4との境界

合成fixture: SNS20分→YouTube10分→開発5分→unknown30秒→未観測5分→SNS10分→動画15分→開発10分→SNS30分。
結果: 連続娯楽観測30分、直近60分は50/60分=83.3%、直近120分は85/100分=85%、最終WARNING / BOTH。
unknown30秒と欠測5分は分子・分母から除外し、開発への復帰で連続時間をリセットする。
これは実機DBを読み出した結果ではなく、実運用に似たパターンをテストで再現したもの。

Phase 4以降の予定・締切・Task・Working Set・project未完了判定、Productivity Score、週次/月次Analytics、
Adaptive Coachingは未実装。休憩の意図や仕事が終わったかは判断できないため、不要な場面はoffで制御する。
