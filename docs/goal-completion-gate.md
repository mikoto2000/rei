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

## Phase 1：名前付き条件・受渡し・補助状態

Phase 0 の main `d4895388` から独立して実装。通常チャット・旧 Goal の安全な既定値は維持する。
既存 `/goal create --completion-json` と HTTP の completion definition を使い、新しい Goal 作成方式や
実行ループを追加しない。既存の exact SHA、JSON Pointer、宣言型 predicate、review/test receipt、
Artifact の所有・期限・content 検証を再利用する。

## 名前付き条件

completion definition に省略可能な `requirements` を追加。
1項目は `{id,statement,required,criterion}`。最大16項目、idは一意、statementは機密値や制御文字を含めない。
criterion は既存 `{relativeFile,sha256}` または JSON Pointer / 宣言型 predicate。存在だけの条件は拒否する。
`required:true` が未達なら Gate は成功しない。`required:false` の未達は一覧に残すが成功を妨げない。
旧 `completionEvidence / requiredTests / requiredArtifacts / requiredPredicates / reviewGate` は引き続き必須検証する。

## 受渡し

requiredArtifacts の各要素に省略可能な `deliveryRequired`（既定 false）を追加する。
true の場合、AVAILABLE / 所有 / media type / 名前 / 期待 SHA の検証に加え、Proof の
`deliveredArtifacts` に同じ Artifact ID / SHA が必要。これは human completion-evidence API / Shell で
人間が受渡し確認後に追加する確認記録であり、サーバーからファイルを送信し終えたことの自動証明ではない。
モデル側 `attachGoalCompletionEvidence` は deliveredArtifacts を送れない。添付済み Artifact と一致しない
ID / SHA、重複、同名別 revision の代用を拒否する。

Gate が `completion_delivery_pending` を返すと、自動再実行を止める。人間が確認記録を追加し、
既存 `/goal verify` で再検証できる。中間／部分／失敗応答を禁止しない。受渡し必須を全Goalへ強制しない。

## 状態と確認

既存 Goal の RUNNING claim / unique index / 予算 SQL を維持し、nullable `completion_phase` TEXT を追加。
稼働中に RUNNING / VERIFYING / REPAIRING を保存。claim が失効したら補助phaseを権限として使用しない。
旧DBは既存列検出で追加し、旧 completion JSON は空 requirements / deliveredArtifacts として読む。
SSE の既存 goal.updated payload は status を保持し、追加 completionPhase を通知する。
旧 Java constructor と旧イベントJSONを維持する。Native/HTTP の旧キーは変更しない。

`/goal progress ID` または `GET /api/v1/projects/{project}/goals/{id}/completion-progress` は
read-onlyで現在の条件・SHA・未達理由・受渡し待ちを返す。確認操作はGoalを完了状態へ更新しない。
既存statusと補助phaseをもとに RUNNING / VERIFYING / REPAIRING / WAITING / BLOCKED / COMPLETED /
PARTIAL / FAILED / CANCELLED を区別する。READYも維持する。PARTIALは独立検証で一部必須条件成立を
観測した未完遂の表示状態であり、既存の永続BLOCKEDを勝手に成功へ変更しない。
WAITING_APPROVAL / PAUSED はWAITINGとして表示し、既存承認・reconcileを必要とする。

旧モデルgatewayを使うPhase 0 の10シナリオ比較は旧動作を維持するため同じ期待値（70% / 30% / 30%）。
これは新Gateを使った実モデル改善率ではない。liveモデル・ユーザー環境へのdeployは行わない。

## TDD / 検証

未実装 Requirement / supplemental phase / SSE field に対するコンパイルRedをそれぞれ確認して実装。
同名別SHAの受渡し取り違えは expected completion_delivery_pending / actual completion_gate_verified の
失敗を再現後、名前・media type・SHAと実際に確認済みIDの全照合でGreenにした。
名前付き必須/任意、保存と受渡しの区別、モデル拒否、SQLite再起動、既存予算、read-only view、
SSE transition、旧Gate・Goal・Reflection・Attention・Shellの回帰を実行する。

安全な既定値：新 requirements の required は true / false を明示必須とし、省略時に任意へ弱めない。
旧 definition の requirements 自体の省略は引き続き空リスト。無効JSON・未知predicate・取消等の
検証不能を単なる未達へ置き換えず、元の理由を保持して自動修復対象にしない。

### Phase 1 CI 回帰での並列予算競合

最初の CI では既存 StandaloneSubAgentBudgetTest が、先行する子処理の token 使用量だけ確定した瞬間に後続の子処理を予約できる競合を検出した。未確定の兄弟処理が残る波では追加予約を拒否する。総予算を増やさず、既存の全 assertion を維持し、予約・部分使用量報告を直列に再現する Red → Green テストを追加した。既に開始した並列 provider の token overshoot 自体を予見できる保証ではなく、その後の追加呼び出しを止める制御である。

CI の再実行では Reflection の既存並列昇格テストが SQLITE_BUSY を検出した。同じ VerifiedReflectionMemoryService singleton の人間による昇格を直列化し、証拠照会と兄弟 writer の競合を防ぐ。DB の一意制約・transaction は維持し、他プロセスまでのロック排除保証は追加していない。通常チャットの経路には変更しない。既存並列・キャンセル・rollback・再検証テストをそのまま通している。
