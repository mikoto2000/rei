# Responses API 実装進捗

更新日: 2026-11-24。完了判定はGit・PR・CI・実装・検証結果と照合する。

| Phase | 状態 | ブランチ / worktree | PR | マージコミット | 検証 |
| --- | --- | --- | --- | --- | --- |
| 0 | IN_PROGRESS | codex/responses-api-phase-0-foundation / .worktrees/responses-api-phase-0 | 作成・CI待ち | 未マージ | 全回帰snapshot 7,717件成功、最新関連168件成功、品質ゲート成功 |
| 1 | NOT_STARTED | — | — | — | 未実施 |
| 2 | NOT_STARTED | — | — | — | 未実施 |
| 3 | NOT_STARTED | — | — | — | 未実施 |
| 4 | NOT_STARTED | — | — | — | 未実施 |
| 5 | NOT_STARTED | — | — | — | 未実施 |
| 6 | NOT_STARTED | — | — | — | 未実施 |

## Phase 0: 接続先管理・API抽象化

### 基準と前提

- 元worktreeのmain: `18894acb`。未追跡の2設計書とreview/は保持した。
- remote main: `cc12001e845ecc29c1267afe881dc858e6cca6a2`。PR準備時に再取得し、変更なしを確認。
- 動的ツール選択はremote未反映だったため、専用worktreeで通常マージ `8a775e32`。
  既存変更を作り直さず履歴を保持し、最新セキュリティ修正と統合した。
- PR上のarchitecture基準差分はローカル前提コミットのもの。Responsesによる予算緩和はない。
- 実装の検証済みcode commit: `f9b56cfe4415885f20e6e2858f4d02d8dd796a84`。
  最終ドキュメントcommitと最新PR headはGitHubで照合する。

### 実施済み

- API形式の明示、必要最小限の能力、再送方式、不変の実効接続構成。
- APIキーを含まないfingerprint、キー更新と互換性世代の分離。
- featureのモデル/Optionsの構成コピー固定、native Chat optionsの薄いAdapterへの集約。
- root/custom/private/fallbackで未検証Responsesの利用拒否。
- 診断表示から秘密URLを除外。音声補正の未指定上限は従来どおり保持。
- 明示max-toolsと動的公開枠を分離。独立接続先へのroot能力の暗黙継承を禁止。
  fallback候補の最小上限、基本ツール保持、重複拒否、公開枠1〜128を維持。
- 既存/modelの要求モデル引数を維持。動的接続先選択UIは追加していない。
- 設計をPhase 0〜6へ更新し、保存/再送の責務、障害境界、GC/容量、圧縮、セキュリティ、
  提供元差と有効化条件を明文化。
- Spring AI 2.1.0 / OpenAI SDK 4.101.0のPOM、source JAR、実bytecode、公式仕様を確認。
  Springの5ソースは前回展開された調査対象と改行正規化後に完全一致。

### TDDと不具合検出

| 境界 | Red | Green / 改善 |
| --- | --- | --- |
| 明示API選択 | Responses設定が無視され、期待した拒否がない | 本番経路で利用不可を明示 |
| モデルとOptions | cached first-modelとsecond-modelが混在 | 設定コピーを共通化。対象70件成功 |
| root生成経路 | 別composition経路がResponsesを拒否しない | native options生成を共通境界へ委譲 |
| private/fallback/診断 | 明示API無視、異API fallback、URL秘密情報露出 | 期待したAssertionError3件を解消 |
| 接続先能力と公開枠 | 明示能力が要求へ反映されない | 未指定時の既存制限を維持し、明示能力のみ適用 |
| 基本ツール保持 | 接続先上限31でRunを開始してしまう | 基本31件＋検索を保持できなければ開始前に拒否 |
| 既存音声補正 | 未指定上限のint変換でNPE | 任意上限を正確に表現。HTTP内容を変えず6件成功 |
| null安全性 | SpotBugsが3件検出 | 任意上限の型修正とcontextの一度取得で0件 |

Redの初期セットアップにあったコンパイル/classpath/Binder設定の失敗は、仕様上のRedとして数えていない。
短い開発サイクルの独立JUnit出力は全回帰の実行中classesを上書きせず、最終関連回帰でMavenから再実行した。

### 検証結果

| 検証 | 件数 / 結果 |
| --- | --- |
| 統合基盤の初期対象回帰 | 68件、failure/error/skip 0 |
| 初回全回帰 | 7,711件、成功7,697、failure0、error2、skip12。fake接続先の上限不整合を検出 |
| 修正後の全Backend回帰snapshot `279331b3` | 7,717件、成功7,705、failure/error0、skip12 |
| 最新ソースの関連回帰 `-Pfull,quality` | 168件、failure/error/skip 0。音声補正、model/client、能力、出力予算、ComputerUse/SubAgent、architectureを含む |
| Checkstyle | 指摘0件 |
| SpotBugs | 指摘0件、解析エラー0件 |
| Spotless | 成功。統合基盤を含む既存違反36ファイルは独立コミットで整形 |
| architecture | 既存guard/source report/package dependency gate成功。予算・除外を変更せず検証 |
| 最新sourceのpackage/repackage | 成功。専用worktree内のみ |
| 最新PR headの全件CI | 未実施。マージ前に必ず確認 |
| 実API / 性能 / production migration | 未実施。このPhaseでは利用不可 |

全回帰後の最終変更は、既存音声補正の未指定上限の表現とnullable contextの一度取得。
最新ソースは再コンパイルして上記168件・全品質ゲートで検証済み。
最新PR headの全回帰CIも成功するまでマージしない。
スキップは既存の[期待IDと理由](../config/test-expected-skips.json)でCI照合し、成功件数へ含めない。

全回帰で見つかったComputerUse179件/SubAgent178件のfake全ツール接続先には256件の能力を明示した。
未指定の184件を拒否する既存テスト、動的公開128件、重複拒否、権限・LRU・元要求snapshotは維持する。
無制限化、検査除外、静的解析の抑制、architecture予算の緩和は採用していない。

### 受入と残作業

ローカルで既存Chat・設定・動的選択/承認/再開に関連する回帰と品質ゲートが成功。
ただしPR差分レビュー、最新headの必須CIと承認、通常マージ、マージ後確認、worktree削除が未完了。
GitHub上の結果と作業記録で確定し、次PhaseでPR番号・merge commitを追記する。

Responsesは本番利用不可。通信・HTTP/SSE・完全保存・承認/Checkpoint・長時間Runの統合は後続Phaseで実装する。
設定descriptorだけを、Run受付時に選んだ要求モデルの代わりにしない。
外部設定、本番JAR、本番プロセス、本番DB、元worktreeの未追跡ファイルは変更していない。
実APIの認証・送信データ・費用・実行権限の確認が不足する場合、未検証の有効化条件として残す。

### 次の作業

Phase 0のPR・最新head CI・差分レビュー・通常マージ・後片付けを確定する。
前Phaseのマージ前にPhase 1を開始しない。再開時は実Git/PR/CIを照合し、記録だけで判定しない。
