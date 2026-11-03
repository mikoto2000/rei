# レビューの取得単位と性能計測

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
