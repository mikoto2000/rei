# Rei リポジトリレビュー（2026-11-14）

対象: `F:\project\rei`（worktree、HEAD `b4b0c257`）
除外: `review/codes`（指示により未参照）、`review/rei`（本出力）、`.m2/**`（Maven キャッシュ）、ビルド生成物

このディレクトリは HEAD `b4b0c257` を対象としたレビューの記録です。
採用時に訂正した箇所は [再確認結果](reassessment.md) を参照してください。

## 結論

土台はかなり堅い。認証・Webhook・アーキテクチャゲート・CI の設計は、根拠を残す形で作られている。
一方で、**配布物としての既定値**と**検証範囲の非対称**に弱点が集まる。

優先して直すべきは次の4点。

1. `application.yaml` の既定接続先が私設 LAN アドレス（`192.168.1.50:11434`）と特定モデル名になっている。
2. CI の Linux ジョブはテスト結果 XML の検証だけで、README が案内する `sh ./build.sh` などの POSIX ビルド・起動は一度も実行されない。
3. `sqlite-vec` の自動ダウンロードにサイズ上限・タイムアウト・独立した checksum 照合がない。
4. `docs/architecture/README.md` が実装より古い（B 違反の記述と分析対象 SHA）。

## 文書

| ファイル | 内容 |
| --- | --- |
| [findings.md](findings.md) | 指摘一覧（重大度・根拠・推奨対応） |
| [architecture.md](architecture.md) | パッケージ構成と依存ゲートの評価 |
| [security.md](security.md) | Web API 認証・GitHub Webhook・供給網・Native Client の vault |
| [testing-ci.md](testing-ci.md) | テスト構成と CI の検証範囲 |
| [docs-config.md](docs-config.md) | 設定既定値とドキュメント整合 |
| [verification.md](verification.md) | 確認できたこと／できなかったこと |
| [specs.md](specs.md) | `.kiro/specs` の要件・設計・計画の整合性 |

## レビュー範囲と限界

- 旧スナップショット（`3005f5ca` / `1b2f84c3`）は `.m2/**` を拾って 1 回数ファイルしか進まず、未走査ディレクトリを残したまま `SCANNING` で打ち切った。
- 改めて `.m2/**`、`**/.m2/**`、`review/codes/**`、`**/target/**`、`**/node_modules/**`、`**/build/**`、`**/dist/**` を除外したスナップショット（`c02a75a8`）を作成し、列挙は **SEALED** に到達した（3923 ファイル、除外 73、未走査ディレクトリ 0、エラー 0）。
- ただし **列挙の完了はレビューの完了ではない**。評価を提出したのは `.devcontainer/**`、`.github/**`、`.gitattributes`、`.gitignore`、`.kiro/**` の一部に限られ、残りの作業単位は未読・未評価。したがってこれは**網羅レビューではない**。
- 提出した評価は `findings` を空配列とし、観測内容を `assessment` テキストに記録した。座標付き指摘の検証は行っていないため、レビュー基盤が検証した指摘ではない。
- 以下の指摘はすべて、実際に読んだ範囲の静的読解に基づく。
- 重点的に読んだ範囲: ビルド定義（`pom.xml`、`build.sh`/`build.bat`）、CI（`.github/workflows/*`、`.github/ci/*`）、Web 認証と Webhook（`web/*`、`github/*`）、アーキテクチャゲート（`config/architecture/*`、`architecture/*Test`）、設定既定値（`application.yaml`）、`sqlite-vec` 導入、テスト分割スクリプト。
- 未検証: テスト実行、ビルド、実機での起動・通信挙動。指摘は静的読解に基づく。
- 規模: 追跡ファイル 3996、`.java` 3088（main 1713 / test 1314）、`.md` 434、`.rs` 61、`.tsx` 39、`.ts` 33。
