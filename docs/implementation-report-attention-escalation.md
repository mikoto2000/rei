# 永続確認待ち / アプリ内 Escalation

Status: Implemented (in-app attention slice)

Branch: `codex/attention-escalation`

Commit / Merge: `feat: persist owned attention escalations` / `Merge branch codex/attention-escalation` を参照。

Merged into: `main`

Implemented:
- 既存の所有イベントから承認要求・Policy拒否・停滞停止・確認済み長時間待機を分類。
- SQLite inbox、Project/Session/Run/Kind/reference の永続 unique key。再起動・ack 後も同じ問題を再通知しない。
- 連続待機は monotonic 2分。進捗・通常の停滞・Run終了で解除し、観測状態は最大1024件。
- 固定の案内文、資格情報を redaction した reference。Tool引数・モデル回答・例外文を保存しない。
- /attention list|show|ack、既存認証下の Web API、所有境界を引き継ぐ attention.required イベント。
- Shell表示、Web SSEの明示 public fields。任意のreason/evidenceを非公開に保ち、waiting_for_dependency 固定値だけを投影。
- ack は表示上の確認済み操作だけ。Tool承認・Resume・dispatch を行わない。

Tests:
- Red: 未実装 AttentionRepository/Service の compile failure を確認。
- Green: 再起動、重複抑制、ack後の非再通知、Project境界、2分待機・進捗によるreset・終端解除。
- 実際の PermissionGuard: 通知ack後も Tool拒否を維持し、承認要求はPENDINGのまま。
- Shell / Web controls、Shell表示、Webの明示投影と既存非公開情報の境界を検証。
- 全体回帰で arbitrary reason 公開の2件失敗を検出し、固定waiting値だけの投影へ修正。
- 最終全 Java: 2,715 tests / 523 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存 cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- Native専用UI、OS通知・音声・メール等の外部配送、任意の判断待ちの分類。
- Runをまたぐcooldown、自動解決照合、履歴retention。
- イベントが来ない間の監視と、再起動をまたぐ連続待機時間の復元。
- 通知配送に失敗した場合は永続一覧から確認する。外部サービスは不要。
