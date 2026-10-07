# Goal completion gate

既存 `FileGoalVerifier` と `GoalLoopService` の完了判定を拡張する。既定では旧Goalの
file SHA/JSON criteriaを維持する。`rei.goal.completion-gate.require-all` /
`REI_GOAL_COMPLETION_GATE_REQUIRE_ALL` は既定false。有効時、全Goalに人のcompletion定義を
要求し、未定義GoalのRun開始を拒否する。モデルは定義を作成・変更できない。

人の `/goal create OBJECTIVE --file ... --sha256 ... --completion-json 'JSON'` で、
Goalとcompletion定義を同じSQLite transactionに保存する。複数criteriaには既存
`--criteria-json` を使う。停止中の既存Goalには `/goal completion ID --completion-json 'JSON'`。
実行中/COMPLETED/CANCELLEDの定義変更を拒否し、定義変更は古いproofを破棄する。

```json
{
  "completionEvidence": [{"relativeFile":"result.txt","sha256":"64桁の期待SHA"}],
  "requiredTests": {"testCommand":"./mvnw -Dtest=ValueTest test","tests":["ValueTest#returnsValue"]},
  "requiredArtifacts": [{"filename":"result.txt","mediaType":"text/plain","sha256":null}],
  "requiredPredicates": [{"relativeFile":"state.json","jsonPointer":"/done","expectedJson":"true"}],
  "reviewGate": {"semanticRequired":false,"requirements":[{"id":"R1","statement":"指定した値を返す","files":["src/Value.java","test/ValueTest.java"],"tests":["ValueTest#returnsValue"]}]}
}
```

completionEvidenceは1–16件の正確なfile SHA。testsはcommand SHAと1–64件のtestcase IDs。
`testCommand` からSHAを計算し、両方指定した場合は一致が必須。command bytesを保存しない
定義では `commandSha256` を指定し、実際のcommandは人のobjectiveで説明する。
Artifactとpredicateは各最大16。Artifactの期待SHAは任意、filename/MIMEは必須。
各項目のnull/空listはその種類を要求しないという人の明示定義であり、自動追加はしない。
JSONは32KiB/depth16、未知field、重複key、trailing JSONを拒否する。

モデルは `getGoalCompletionDefinition(goalId)` をREADで参照できる。
実行中の捕捉Goal Runだけが `attachGoalCompletionEvidence(goalId,proof)` で保存証拠を
LOCAL_WRITEとして添付できる。別Project/root/session/Run、READ_ONLY、停止中のモデルを拒否。
人は停止中に `/goal completion ID --proof-json 'JSON'` で添付できる。
既存Bearer認証HTTPにも `PUT /api/v1/projects/{project}/goals/{id}/completion` と
`POST .../{id}/completion-evidence` を追加する。bodyは同じstrict JSONで、Runを起動しない。

```json
{"review":{"id":"保存Review UUID","sha256":"保存receipt SHA"},"artifacts":[{"id":"Artifact UUID","sha256":"Artifact SHA"}]}
```

test/Review必須時は [要件Patch Review](semantic-patch-review.md) の確定receiptを使う。
deterministic-only定義ではReview requestの `semantic:false` を使う。意味Review必須なら
設定も有効にし、`REVIEWED_CHECKS` とMATCH verdictが必要。通常のcommand exitだけ、
自由文の完了宣言、任意のJUnit file、モデルが作ったreceiptを成功証拠へ読み替えない。

gateはowner、全要件定義の一致、要求command SHA、実際のPASSED testcase、report SHAを確認。
保存Reviewは24時間以内、未来時刻は60秒以内。現在Git patch versionと同じでなければ
成功せず、確認の最後にもpatchを再読取する。ArtifactStoreのowner/AVAILABLE/expiryと
実内容hashを読み直す。required predicatesは既存scalar/declarative verifierを利用し、
宣言的predicateの既定OFFも維持する。追加検証は30秒の共有予算、追加LLMは0。

既存のRun前確認、`/goal verify`、Chat終了後確認、queued dispatch前確認をすべて同じ
FileGoalVerifierに通す。不足する実証は既存Run/LLM予算内で継続、UNKNOWN/拒否は停止。
保存成功理由は `completion_gate_verified`。停止中verifyからの完了DB更新は検証した定義と
proofの一致をCASし、途中で定義が変われば完了しない。Reflectionもこの検証理由を扱う。

DB migrationは `agent_goals` にnullable `completion_json` / `completion_proof_json` を追加。
旧record constructorと旧行を維持する。定義と証拠はGoalと同じowner/予算/historyの一部。
再起動時にRunやcommandを自動実行しない。保存proofは現在の検証を省略する許可ではない。

意味一致は確率的判断で、普遍的な正しさ・test完全性・commandとreportの独立した因果証明
ではない。ファイル群を同時にロックするsnapshot transactionでもなく、外部からの変更は
独立チェックの間にも起こり得る。既存ProjectのEXCLUSIVE Runとhash再確認を使う。
