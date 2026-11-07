# コードレビュー 指摘一覧

## 対象と方法

- 比較範囲: `git diff main...HEAD`（17ファイル）と未コミット作業ツリー（4ファイル）
- 重点: プロンプト Prefix 安定性、非同期境界の Run 所有権、レビュー再開の読み取りキー、`listFile` の拒否伝播
- 手法: 差分精読 → 呼び出し側・保存形式・検証条件の突き合わせ → 対象を絞ったテスト実行

## 指摘一覧

| ID | 重大度 | 対象 | 指摘時の要約 | 対応・検証状況 |
|---|---|---|---|---|
| M-1 | MEDIUM | `llm/AgentEventChatModel.java` | イベントと計測で Run の解決方法が異なり、非同期購読時に Timing Span が欠落し得る | 対応済み。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) |
| M-2 | MEDIUM | `core/working/WorkingSetAdvisor.java` / `ContextAssembler.java` | 通常経路で Working Set がユーザー本文の先頭から末尾の独立した `SystemMessage` へ移る | 実 Provider で確認済み。通常時の末尾は `user → system`。[検証証跡](./test-evidence.md#rei-本体からの実-provider-検証) |
| L-1 | LOW | `core/Tools.java` | `ExcludedInventoryPathException` の型付き拒否への変換が単一引数 `listFile` のみ | — |
| L-2 | LOW | `llm/AgentEventChatModel.java` | ローカル変数 `owner` / `publisher` が同名メソッドを隠蔽し可読性を下げる | — |
| L-3 | LOW | `llm/AgentEventChatModel.java` | `measurement(prompt, request)` が `owner(prompt)` を二重解決する | 解消済み。M-1 の修正で解決済みの `runId` を計測に渡す。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) |
| L-4 | LOW | `core/contextbudget/ContextAssembler.java` | 末尾の `ToolResponseMessage` の後ろに Working Set の `SystemMessage` が付く | 実 Provider で確認済み。ツール実行後の末尾は `assistant → tool → system`。[検証証跡](./test-evidence.md#rei-本体からの実-provider-検証) |
| L-5 | LOW | `core/contextbudget/ContextAssembler.java` | `estimate(project(...))` のたびに `workingSet.get()` を再描画し、推定と最終投影がずれ得る | 対応済み。組み立てにつき1回の取得に揃え、次の呼び出しで取り直す。[修正 PR #499](https://github.com/mikoto2000/rei/pull/499) · [回帰テスト結果](./test-evidence.md#l-5-の回帰テスト) |
| L-6 | LOW | `terminal/cli/.../RunEventOutput.java` | `llm.request.failed` の即時描画後に、同じ思考ストリームの見出しが再表示され得る | 対応済み。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) |
| L-7 | LOW | `review/ReviewTools.java` | 予算超過メッセージが、新規 claim 済みの場合に事実と異なる | 対応済み。[修正 PR #418](https://github.com/mikoto2000/rei/pull/418) |

重大度の基準: HIGH=データ破壊・権限逸脱・無制限消費、MEDIUM=機能欠落や観測不能、LOW=保守性・周辺互換。

## 総評

差分は3つの一貫した狙い（Prefix を壊さない配置、非同期境界での所有権保持、再開時の読み取りキー統一）を持ち、テストも意図に沿って追加されている。設計判断は `docs/llm-prompt-cache.md` と整合している。

実 Provider 検証は `main` の `7f32022b` からビルドした Rei 本体と TensorFold / DeepSeek-V4.1-Flash-1017 が対象。通常時と `readMultiFile` 実行後の最終応答で HTTP 200 と SSE の完了を観測し、Rei の Run が `COMPLETED` になったことを確認した。[検証証跡](./test-evidence.md#rei-本体からの実-provider-検証)

## 既存の懸念（今回の変更外）

- `WorkingSetAdvisor` は `rei.contextProjection` 経路で、Working Set が空でも空 content の `SystemMessage` を追加する。これは変更前からの挙動で、今回の差分による回帰ではない。`ContextAssembler` を通る経路では `withWorkingSetSnapshot` が空表示へ置換するが、通らない経路では空メッセージが残る。
