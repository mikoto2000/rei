# Run時間計測

テキストRunの実処理をメモリ内で観測し、現在のProject/Sessionから照会できます。

```text
/timing
/timing last
/timing RUN_ID
/timing last --details
```

照会は新しいSession/RunやLLM呼び出しを作りません。別Project/Sessionの履歴は表示しません。
`rei.timing.enabled=false` ではコマンド登録と記録を無効にします。
別のRunエンジン、SQL永続化、HTTP本文記録は追加していません。

## 実処理への接続

- `ChatExecutionService` の既存実行と後処理全体を `TimingExecution` が観測します。応答・例外・権限・予算・キャンセル処理は既存経路です。
- `AgentEventChatModel` は既存のリクエストIDをSpan IDとしてLLM呼び出し・購読を観測します。所有Runが不明なバックグラウンド呼び出しは記録しません。Activity検出は従来どおり通知しません。
- `TimingEventObserver` は既存のツール開始/終了IDを利用します。既知の検索ツールはTOOLを親とするSEARCH Spanも作ります。イベントの引数・結果要約やリモートの所要時間はコピーしません。
- `ToolApprovalRepository` はPENDING申請から人のAPPROVED/DENIED判断までを観測します。申請を重複計測せず、再起動後の過去の申請時刻から時間を捏造しません。未判断・期限切れは終端未取得として残り、保持期限で破棄します。
- `BoundedToolLoop` の既存の100ms再試行待ちをRETRYとして観測します。同じ論理リクエストIDに試行番号を付け、既存の残りステップ・共有呼び出し予算をそのまま消費します。SDK内部の透過的な再送やfeatureモデルの内部fallbackは個々のHTTP試行へ分解しません。
- `GoalChatGateway` はチャット終了後の同期完了コールバックまでRunを保持し、`FileGoalVerifier` の実際の独立検証をCOMPLETION_VALIDATIONとして記録します。通常チャットには宣言されたGoal条件がないため、この検証を捏造しません。

Run終了後の承認判断は詳細に生の待機終端を表示しますが、Run内占有は終了までに切り詰めます。
Spanの親は明示した所有Run/ツール/失敗試行です。並列の「最後に開始したSpan」から親を推測しません。

## モデルと時計

TimingRecorder / TimingStoreはRun・Span・リクエスト・試行・親SpanのIDを扱います。
カテゴリはLLM、TOOL、SEARCH、APPROVAL_WAIT、RETRY、COMPLETION_VALIDATION、OTHERの固定enumです。
IDは128文字以内のASCII識別子で、自由形式のラベル・本文・パスを受け付けません。
開始観測時刻にはClockの壁時計、経過時間にはローカルのSystem.nanoTimeを使います。
Spanの開始・終了は同じプロセスのRun開始からの相対nano値です。別マシンの時刻を引き算しません。
時計を注入でき、壁時計が逆戻りしても経過時間は変わりません。

状態はSUCCESS、FAILED、CANCELLED、TIMED_OUT、DISCONNECTED、INCOMPLETEです。
開始だけで終端のないRun/SpanはINCOMPLETEです。Run終端が成功でも未終端Spanがあれば記録不完全を表示するためのフラグを返します。
重複する終端は最初の観測を維持します。記録上限で省略したSpanも記録不完全として残します。

## 集計

Run自身は内訳に加えず、子Spanの区間をRunの範囲に切り詰めて集計します。

- 全体経過時間: Runの開始から終端、未終端なら現在まで。
- 延べ処理時間: 子Spanの区間長の合計。並列・入れ子を含み、全体経過を超える場合があります。
- 全カテゴリ占有時間: 子Spanの区間の和集合。
- カテゴリ別占有時間: 同じカテゴリ内の区間の和集合。
- 重複区間: 2件以上のSpanが重なる経過時間。
- 異カテゴリ重複: 2カテゴリ以上が重なる経過時間。
- 未計測区間: 全体経過から全カテゴリ占有時間を引いた値。

カテゴリ別占有時間は互いに重なるため、加算して全体の合計内訳や100%積み上げにしません。
境界を時刻順に処理するO(n log n)の集計で、同時刻の境界に幅0の重複時間を付けません。
Run終了後に届いたSpan終端は実観測として保持できますが、Runの占有時間へ範囲外の時間を加えません。
承認待ちなどRunより長く続くSpanの生の終端値と、Run内の占有集計を区別します。

## 有界保持と設定

```yaml
rei:
  timing:
    enabled: true
    max-runs: 100
    max-spans-per-run: 2000
    max-total-spans: 10000
    ttl: 1h
```

既定は直近100 Run、1 Run最大2,000 Span、合計10,000 Span、開始から1時間の保持です。
Run数や合計Spanの上限では古いRunを一括破棄します。必要なら進行中Runも破棄されます。
1 RunのSpan上限では追加記録を省略し、実行本体には失敗を返しません。
失われたRunへ届いた子イベントで履歴を再作成しません。statisticsで保持数・破棄Run数・省略Span数を確認できます。
TTLは単調増加時計で判定し、次の記録・照会時に遅延破棄します。アプリ再起動では履歴を保持しません。
設定可能範囲はRun数1..500、Span/Run1..10,000、合計Span/Run以上..50,000、TTL1秒..1日です。
falseでは記録・時計読み取り・保持を行わず、未使用の上限設定も解析しません。

## メトリクスと秘密情報

計測モデルにはプロンプト・応答・ツール引数・結果・環境変数・音声・任意metadataを格納するフィールドがありません。
LLM Request Captureの本文保持とは独立しています。
通信の最初のデータ、SDKの最初のチャンク、生成トークン、生成テキスト観測、可視出力は別の指標です。
C2で観測する最初の時刻はFIRST_FRAMEWORK_CHUNK（空/思考のみのSDKチャンクを含む）とFIRST_GENERATION_TEXT（最初の非空本文）です。
この2つを生成トークンTTFTと呼びません。同期callでは受信完了から最初の時刻を逆算しません。
HTTPの最初のデータ、厳密な最初の生成トークン、実際のユーザー画面/端末への表示時刻、生成期間は未取得です。
Shellは入力中の部分行をJLineで保留するため、イベント発行やprint呼び出しを実可視時刻と見なしません。Webブラウザーでの実可視時刻もサーバーから推測しません。
未取得の指標はnull/キーなし、CLIでは「未取得」とし、文字数でトークン数を作りません。
usageはSDKが明示した入力/出力数のみ記録し、空のusageで既知の値を消しません。部分受信後に失敗した場合も成否とusageを別々に扱います。
入力・出力usageと実生成トークン数を分け、TPSは実生成数とそれに対応する生成期間の両方が明示取得された場合だけ算出します。
リクエスト全体時間をdecode時間と見なさず、reasoningやprefillなどサーバー内部情報を推測しません。

## 検証

`./mvnw -Pfull -Dtest=Timing*Test test` で偽時計の直列・並列・入れ子・リトライ・欠落終端・取消・期限・上限・所有範囲と、同時更新を検証します。
Spring統合はCaptureやモデル基盤なしで起動することを確認します。
TimingMemoryProbeTestは既定合計上限10,000 Spanを満たし、GC後のプロセスheap差分を概測します。
値はクラスロードやGC・共有heapの影響を受ける概測であり、厳密なdeep sizeや使用量上限保証ではありません。
C2の関連テストは `./mvnw -Pfull -Dtest=Timing*Test,AgentEventChatModel*Test,ChatExecutionTimingTest,GoalChatGatewayTest,ToolApproval*Test,SubAgentModelRetryTest test` です。
TimingOverheadProbeTestは単一チャンクの模擬モデルを使い、有効/無効双方の応答とイベント数が同一であることを確認します。
3組のウォームアップ後、各7組×1,000呼び出しの中央値と差分をログへ出します。実サーバーの遅延や全アプリの性能保証ではなく、観測追加の局所コストの概測です。
音声のSTT/TTS・再生や外部エージェント詳細は任意のC3として延期しています。
実LLM、GPU、ネイティブ音声デバイスの性能は、この決定的な自動テストでは検証していません。
