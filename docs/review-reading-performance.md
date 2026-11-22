# レビューの取得単位と性能計測

モデルへ渡すツール定義の絞り込みは [レビュー用ツール一覧](review-tool-profile.md) を参照。

リポジトリ全体の新規レビューは既存のdurable TaskとWorkItemのID指定経路を優先する。
`beginRepositoryReview` → `scanReviewSnapshot`（SEALEDまで）→
`readNextReviewWorkItem(taskId)` → `submitReviewWorkItem(workItemId, attempt, ...)` の順に進める。
確保と取得を1回にまとめ、取得結果のworkItemId/attemptをそのまま提出に使う。
取得後の再呼び出しは同じRunの未提出claimを再配送し、未評価のまま次の範囲を確保しない。
評価済み範囲は保存され、再開時に未完了範囲だけを処理する。読み取りや取得だけでは評価済みにならない。
既存のlegacy reviewJobIdは元の台帳で完了させる。
`readNextReviewWorkItem(taskId="legacy:<reviewJobId>")` は未評価の範囲を意味単位で返し、
そのworkItemId/attemptを `submitReviewWorkItem` に渡せる。
この互換経路は既存の評価・指摘・所有権・配送確認を保持し、台帳を新規Taskへ複製しない。
legacyのattemptは元ファイルの版に対応する。receiptには元台帳の受付記録も含め、
再開時に保存された旧ページの評価義務も正確に照合する。
legacy IDの指摘位置は省略またはnullで指定でき、サーバーが厳密な引用から補う。
通常のdurable claimでは位置が必要であり、欠けていれば保存せず修正可能な受付エラーを返す。
この境界は実ツールJSONの入力・出力とSQLiteへの保存まで通すテストで検証する。

中断したレビュー読み取り（旧readReviewPagesを含む）は、チェックポイントでRETRYABLEに分類する。
旧形式のUNKNOWNも同じ規則で再取得可能にするが、成功・モデル配送・評価完了とは扱わない。
reviewStatus等の状態確認も、結果不明の外部変更を繰り返さず実行できる。
評価登録や外部変更操作のUNKNOWNは、この読み取り分類では解除しない。

## 0.8Mコンテキストでの予算

モデル上限の設定は既存の `rei.context-compression.model-context-limit` とモデル別設定を使用する。
取得時は回答・ツールschema・安全余白を差し引き、hard-limitも守る。
さらにその有効入力容量の1/8、現在の空き容量を兄弟ツール数で割った値、
`rei.tool-result-delivery.review-max-tokens`（既定100,000）の最小値までとする。
800,000のモデルでは1単位は約100,000推定トークン以下となる。
既定の小さいモデル上限を変更する機能ではなく、実際のモデル容量設定に追従する。
長い日本語やJSON escapingを含む推定は保守的で、プロバイダーの課金トークン数とは異なる。

収まるファイルは全文を読む。長い文書は予算の後半にある見出し、段落、フェンス外の行境界を優先する。
コードフェンス中の見出し・空行を境界にしない。必要なら早い位置の境界に戻り、
巨大な単独ブロックや行だけはUTF-16サロゲートペアを壊さず分割する。
別ファイルへの参照や前のチャンクの前提が必要なら、追加で原文を確認してから指摘を確定する。

1単位を評価するたびに、同じモデル応答で提出を先に並べ、次の取得も要求できる。
新しい本文の評価はその本文がモデルに配送された後に行う。配送ハッシュ・所有権・attemptの検査は維持する。
全文配送済みのレビュー単位は通常の16,000トークンのファイルページ制限で再分割しない。
最終プロンプトの総量検査は引き続き有効。

## 計測と比較

### 小さいファイルのまとめ読みと指摘位置の解決

新規 durable レビューでは `readNextReviewWorkItems(taskId)` を優先できる。
既定で最大4ファイルを、単独取得と同じ共有 token 予算に収めて返す。
先頭の長いファイルは意味単位の1チャンクとし、後続のファイルは全文が収まる場合だけ追加する。
`maxItems` は1..4、`tokenBudget` は省略時に自動計算する。既存の単独取得と legacy 経路も利用できる。

各ページの `workItemId` / `attempt` と配送ハッシュは独立して保持する。
同じ Run の未提出 claim がある間は新しい範囲を取得せず、それらを再配送する。
取得の途中で失敗すれば、そのバッチの新しい claim はまとめてロールバックする。
評価は各ページの本文がモデルに配送された後に `submitReviewWorkItem` で保存し、
1つの応答に複数の提出と次のバッチ取得を並べられる。空配列だけで完了と断定しない。

durable の指摘でも `start` / `end` を両方省略できる。
ホストが配送済みの凍結範囲内で `evidence` の完全一致を探し、絶対 UTF-16 座標を補う。
引用が重複する場合は `REVIEW_EVIDENCE_AMBIGUOUS`、見つからない場合は
`REVIEW_EVIDENCE_NOT_FOUND` とし、指摘は保存しない。
重複時は引用を周辺の原文まで広げるか、従来どおり両方の座標を指定して区別する。
片方だけの座標、サロゲートペア途中の境界、範囲外の引用は受け付けない。
再提出は座標補完後の結果で照合するため、同じ引用の再送でも保存済み結果を増やさない。

比較は `ReviewBatchPerformanceTest` の同一4ファイルを使い、従来プロトコル、
座標移管のみ、座標移管＋まとめ読みの3条件で行う。
固定応答テストは取得・提出・網羅性を比較する。実LLM比較は明示実行の
`ReviewBatchPerformanceLiveTest` を使い、通常のテストではネットワークを使わない。
実LLM比較は入力を固定した評価ループであり、汎用エージェントの探索・ツール選択時間は含まない。

```powershell
$env:REI_REVIEW_BATCH_LIVE = 'true'
$env:REI_REVIEW_EVAL_URL = 'http://<server>/v1/chat/completions'
$env:REI_REVIEW_EVAL_MODEL = '<model>'
$env:REI_REVIEW_EVAL_API_KEY = '<key>'
$env:REI_REVIEW_EVAL_THINKING = 'false' # 両条件で同じ設定。true でも比較可能
$env:REI_REVIEW_BATCH_TRIALS = '3'
./mvnw.cmd -Plive-e2e '-Dtest=ReviewBatchPerformanceLiveTest' test
```

結果は `target/review-batch-live.json` に保存する。実行順序を試行ごとに回し、
実経過・モデル待ち・呼び出し数・拒否数・入力/出力/キャッシュ token と fixture の検出数を記録する。
サーバーの他の利用者による負荷は統制しないため、所要時間はその実行条件の観測値として扱う。
2026-11-22 の条件・結果・制約は [比較記録](../.kiro/docs/review-batch-performance-20261122.md) を参照。

アプリログの `rei.review.metrics` に本文を含まない `review_read` / `review_submit` を記録する。
run/task/work/attempt、本文UTF-16文字数、serialized推定トークン数、提出の成功・拒否理由を確認できる。
同じwork IDへの提出の再試行は、集計で重複したレビュー完了として数えない。

既存の `llm.response.completed` イベントには、プロバイダーが報告したpromptTokens / completionTokensと
firstResponseMillisを追加した。ストリームで繰り返される累積usageを加算せず、最大報告値を保持する。
報告がないusageはnull。同期要求のfirstResponseMillisもnull。
firstResponseMillisは最初のチャンク到着時間であり、空のチャンクやmetadataのみのチャンクも含む。
サーバーのキュー待ち・prefillはこのクライアント計測だけでは区別できない。

```powershell
python scripts/analyze-review-performance.py --events <profile-jsonl> --review-log <rei.log> --run <run-id>
# 再開を含める場合は --run を繰り返す。
```

比較する指標はレビュー完了文字数あたりのLLM要求数、取得回数、再取得量、拒否回数、
要求時間中央値、初回チャンク到着時間、usage報告率。
要求時間の合計は並列処理で重なるため、実経過時間とは別に扱う。
同じsnapshot・レビュー基準で比較し、指摘の正しさや見落としも別途確認する。
`ReviewReadingEfficiencyTest` は同じ教材を2,048予算と0.8M向け自動予算で読み、
原文の完全性・配送確認・完了判定を維持したまま必要な取得単位数が減ることを検証する。
実LLMの速度・指摘品質の改善率を保証するベンチマークではない。
