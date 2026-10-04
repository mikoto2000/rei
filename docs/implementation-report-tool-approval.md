# 一回限り Tool 承認と明示 Resume

Status: Implemented

Branch: `codex/tool-approval`

Commit / Merge: `feat: persist exact one-time tool approvals` / `Merge branch codex/tool-approval` を参照。

Merged into: `main`

Implemented:
- SQLite に PENDING / APPROVED / DENIED / CONSUMED を保存。再起動後も有効な承認を利用できる。
- Project、Session、Tool、Project 絶対パスと引数の SHA-256 に固定。15分期限。
- 消費は実行前の単一 SQL update。並行クライアントと別プロセスでも一度だけ。
- 禁止優先。モデルが承認を作成・解除する Tool は公開しない。
- `/approval list|show|approve|deny`、既存認証下の Web API。
- 資格情報を伏せた引数 preview、16,384文字上限、既存 permission event に request ID を表示。
- 承認後の実行は既存 `/resume` / Resume API またはユーザーの明示的な再実行。承認自体では dispatch しない。

Tests:
- Red: repository の未実装テスト compile failure を確認。
- Green: 再起動、完全一致、Project/Session/Tool/root の分離、期限、拒否、並行消費、引数上限・資格情報秘匿。
- 実際の Chat Tool loop: 承認前0回、承認後の新 Run で1回、その後の再利用は実行不可。
- Shell と Web の明示決定、既存 Policy / Stagnation / SubAgent の回帰 PASS。
- 全 Java: 2,691 tests / 517 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存 cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- Native の専用承認ボタン、実行スレッドの保留と自動再開。
- SubAgent の親承認継承。子 Session は呼び出しごとに変わるため現時点では従来どおり拒否。
- 承認済み操作が失敗・キャンセルした場合の許可復活は行わない。再承認が必要。
- JSON の空白やキー順の変更でも再承認。引数文字列を正規化して権限を広げない。
