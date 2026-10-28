# ファイル書き込み改善

基準: origin/main `9b1d3017` (2026-10-28、Asia/Tokyo)。

## Phase 0: 調査と比較方針

単一 Change Set は SHA-256 に束縛した部分編集、元版上の一意一致と重複範囲検証を持つ。
複数 Change Set は UPDATE/CREATE/DELETE/RENAME の全文入力のみ。
両者とも既存の提案保存・所有権・ポリシー・一回 Apply を維持する。
複数の journal、baseline backup、rollback、UNKNOWN、外部変更保護は再利用する。
単一と複数の部分編集・差分生成を共通化し、別のキャッシュや承認経路は作らない。

現状の制約: 各64 KiB、複数の前後合計256 KiB、保存 JSON 1 MiB。
UTF-8のみの Change Set に対し旧 applyTextDiff は CP932等にも対応するが直接 truncate する。
旧ツールの重複探索は重なる一致を見落とす。単一の差分は共通prefix/suffixのみ省略し、
複数は全文を返す。Applyでも同じ差分を返す。プロンプトの1000文字分割・writeMultiFile優先を整理する。

ベンチマークは固定fixture、入力/返却JSON bytes、差分bytes、ツール呼び出し数、
実行時間と成功/拒否状態を保存する。I/OはJFRの対象source/stagingのFileRead/FileWrite
イベントで観測し、syscall/open回数やSQLite native I/Oとは区別する。
未対応ケースは成功率ゼロの変更として扱わず UNSUPPORTED を記録する。
実LLMのトークン、タスク時間、成功率は測定環境がないため未測定。
