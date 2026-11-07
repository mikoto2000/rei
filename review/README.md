# Rei コードレビュー結果

- 実施日: 2026-11-07
- 当初のレビュー対象: ブランチ `codex/prompt-review-cli-fixes`（`main...HEAD` の差分17ファイル、+377/-106）と未コミット作業ツリー4ファイル（+28/-5）
- レビュー基準: 正確性、回帰リスク、所有権・予算境界、プロンプト Prefix 安定性、テスト妥当性
- 結果格納先: `./review`

## 結論

変更は「プロンプト Prefix の安定化」「非同期境界での Run 所有権の保持」「レビュー再開時の読み取りキー統一」「`listFile` の拒否メッセージ改善」を目的としており、方向性は妥当。データ破壊・権限逸脱・予算の無制限消費につながる重大な不具合は確認できなかった。

## 対応・検証状況

| ID | 状態 | 対応・検証 |
|---|---|---|
| M-1 | 対応済み | イベントと計測を同じ Run に紐づけ、停止要求を受理した Run の購読解除を `CANCELLED` として記録する。追加した回帰テスト3ケースを含む53件が通過した。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [所有権の検証](./test-evidence.md#m-1-と-l-3-の検証) · [キャンセル修正 PR #525](https://github.com/mikoto2000/rei/pull/525) · [キャンセル判定の検証](./test-evidence.md#m-1-のキャンセル判定の回帰テスト) |
| M-2 | 実 Provider で確認済み | Rei 本体からの通常リクエストで Working Set が末尾の `system` に入り、正常に応答した。[取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [証跡記録 PR #487](https://github.com/mikoto2000/rei/pull/487) · [検証証跡](./test-evidence.md#rei-本体からの実-provider-検証) |
| L-1 | 対応済み | 検索・読み取りの部分成功を維持し、除外項目に `PROJECT_PATH_EXCLUDED` と再試行の案内を返す。[修正 PR #505](https://github.com/mikoto2000/rei/pull/505) · [回帰テスト結果](./test-evidence.md#l-1-の回帰テスト) |
| L-2 | 対応済み | `AgentEventChatModel` のローカル変数を `resolvedOwner` と `requestEventPublisher` に変更した。既存テスト29件が通過した。[修正 PR #516](https://github.com/mikoto2000/rei/pull/516) · [テスト結果](./test-evidence.md#l-2-の既存テスト) |
| L-3 | 解消済み | M-1 の修正で、計測に解決済みの `runId` を渡すようになった。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [検証証跡](./test-evidence.md#m-1-と-l-3-の検証) |
| L-4 | 実 Provider で確認済み | Rei 本体が `readMultiFile` を実行した後、末尾が `tool → system` のリクエストで正常に応答した。[取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [証跡記録 PR #487](https://github.com/mikoto2000/rei/pull/487) · [検証証跡](./test-evidence.md#rei-本体からの実-provider-検証) |
| L-5 | 対応済み | 組み立てごとに Working Set を一度取得し、見積もりと最終プロンプトで共有する。次のモデル呼び出しで取り直す。[修正 PR #499](https://github.com/mikoto2000/rei/pull/499) · [回帰テスト結果](./test-evidence.md#l-5-の回帰テスト) |
| L-6 | 対応済み | 失敗を即時表示しながら思考見出しの重複を防ぎ、回帰テストを追加した。[修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [検証証跡](./test-evidence.md#l-6-の回帰テスト) |
| L-7 | 対応済み | 確定時に使う attempt ID でページを見積もり、予算内に収めてから claim を保存するようにした。回帰テストも追加した。[修正 PR #418](https://github.com/mikoto2000/rei/pull/418) · [検証証跡](./test-evidence.md#l-7-の回帰テスト) |

## 最終確認

2026-11-08 に、PR #525・#526 を取り込んだ `main`（`c6dd25af`）のレビュー文書4ファイルを照合した。M-1 のキャンセル誤分類の修正と検証結果を含め、全9件の対応・検証状況が文書間で一致していた。対応済み6件、解消済み1件、実 Provider で確認済み2件で、全9件の対応・検証が完了し、未対応の指摘は0件だった。

全9件に関連 PR と検証証跡へのリンクが揃っていた。文書内リンク34件と、重複を除く GitHub リンク21件の参照先を確認し、リンク切れは0件だった。

## レビュー範囲と限界

- 全リポジトリ（約3,000ファイル）の網羅レビューではない。差分と関連する呼び出し側・テストを対象とした。
- 実行したのは対象を絞った回帰であり、全 suite の成功を主張しない。
- 実 Provider 検証は Rei 本体のチャット経路と TensorFold / DeepSeek-V4.1-Flash-1017 が対象。実 Windows Terminal と実マイクは当初レビューの検証範囲外。
- Checkstyle / SpotBugs は本レビューでは実行していない。

## ファイル

- 指摘一覧と対応状況。[code-review.md](./code-review.md)
- 各指摘の根拠・影響・対応結果。[findings.md](./findings.md)
- 回帰テストと実 Provider 検証の結果。[test-evidence.md](./test-evidence.md)
