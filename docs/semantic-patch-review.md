# 要件と証拠を結ぶ Patch Review

`reviewPatchRequirements` は既存 `SelfPatchReviewService` の test → static diff → final test を
拡張する。新しい実行ループや権限経路は作らない。必ず捕捉済みProject/root/sessionの
EXCLUSIVE Runで実行する。任意commandを含むため既存の全能力Policyを維持する。

```json
{
  "testCommand": "./mvnw -Dtest=ValueTest test",
  "timeoutSeconds": 60,
  "requirements": [{"id":"R1","statement":"指定した値を返す","files":["src/Value.java","test/ValueTest.java"],"tests":["ValueTest#returnsValue"]}],
  "allowedFiles": ["src/Value.java","test/ValueTest.java"],
  "testReports": ["target/surefire-reports/TEST-ValueTest.xml"],
  "semantic": false
}
```

実際のProject相対pathとJUnitの `classname#name` を指定する。要件は1–16、変更fileは1–32、
test reportは1–8、必須test identityは合計64。timeoutは各1–60秒、全体180秒を共有する。
追加の変更・要件に割り当てられない変更・必須file不足・必須test不足・失敗/error/skipped・
不完全/古いreport・変化したpatchは `FIX_REQUIRED`。report時刻はcommand開始以降
（filesystemの時刻精度のため1秒の許容）でなければならない。

Gitの同一patch version、file content SHA、report SHA/time、実際に観測したPASSED testcaseと
command SHAを保存する。Git indexは変更しない。変更source合計64KiB、diff32KiB、
receipt128KiB。追加行のlexerでTODO/FIXMEコメント、disabled/focused test、空catch、
既知のsecurity boundary変更を検出する。文字列内の説明例はコメントと区別する。
読めないdiff headerや不完全な字句断片は確認要求とし、検査済みへ読み替えない。
これは保守的なheuristicで、任意の実装やsecurity問題を網羅する解析ではない。

意味判定は `rei.patch-review.semantic-enabled` / `REI_PATCH_REVIEW_SEMANTIC_ENABLED` が
既定 **false**。設定とrequestの両方で有効にした場合だけ、親Run/Goalの共有LLM予約を
一回消費する。モデルの既定Toolを拒否し、Tool callbackなし、toolChoice=noneで既存の
bounded loopを使う。再試行なし、最大30秒、出力4KiB。user task、要件、patch、現在source、
test証拠をuntrusted dataとして渡す。入力のcredentialをredactする。

意味Reviewは要件・余計な変更・security・testの意味・hygiene・互換性の6観点と全要件の
PASS/FAIL/UNKNOWNだけを受け入れる。deterministic failureをモデルが上書きすることはない。

| 状態 | 意味 |
|---|---|
| DETERMINISTIC_CHECKS_ONLY | 明示command/static/要件別証拠を確認、意味判定を要求していない |
| REVIEWED_CHECKS | deterministic checksとoptional model judgementが一致 |
| FIX_REQUIRED | 必須checkまたは意味ReviewのFAIL |
| SEMANTIC_UNAVAILABLE | 意味判定がOFF、捕捉Run/共有予算が利用不可 |
| SEMANTIC_UNKNOWN | 不正/不完全なモデル応答、timeout等で判定できない |
| STARTED / UNKNOWN | 永続claim済みで確定receiptがない。自動再実行を拒否 |

SQLite `patch_requirement_reviews` は既存memory DataSourceに `CREATE TABLE IF NOT EXISTS`。
Project128件/全体4096件の上限、Project/root/session/run/request hashの一意claimを
commandの前に保存する。同じRun/requestの再送は保存receiptを返し、commandを再実行しない。
確定receiptは `getPatchRequirementReview(id,sha256)` でownerとpayload SHAを検証して読める。
別sessionからの参照は拒否する。raw source/diffや自由文モデル出力はDBに保存しない。

保存receiptは歴史的な観測であり、現在patchの成功証明ではない。`truthVerified=false`。
明示commandと保存JUnitの因果関係を独立に証明する仕組みではなく、モデル一致も確率的評価。
要件の選択やcommandの意味が正しいかは定義とreviewによる。次のGoal gateはこのreceiptを
現在のpatch/要求されたcommand/testsと照合する。

TDDで未実装入口と読めないdiffの偽成功をRedで再現し、予算・不正JSON・ambient Tool・cancel、
欠落/skip/stale report、追加変更、hygieneと文字列、途中のpatch変化、SQLite再読取を検証。
実Git/実Shellではcommandが2回実行してJUnitを更新し、同一patch・receipt・index不変と
再送時に実行が増えないことを確認する。実LLMや外部サービスは呼ばない。
