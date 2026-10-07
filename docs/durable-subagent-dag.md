# Durable child DAG

`rei.subagents.durable-enabled` と `rei.subagents.dag-enabled` は既定false。
両方を有効にすると `delegateTaskGraph` が現在のEXCLUSIVE human Project Runの下で
子の実行依存を扱う。Waiting条件のDependency DAGとは別で、既存SubAgent runner、
SQLite child checkpoint、最大2 workerのparallel delegatorを再利用する。

入力は `nodes`（id/agent/task/context/dependencies）、`failurePolicy`
（FAIL_FAST / CONTINUE_INDEPENDENT）、`maxConcurrency`（1–2）。
最大16ノード、各依存8件、task/context各4096文字、全入力32768文字。
重複ID、不明依存、cycleは実行前に拒否する。graphと全childの予約は単一DB transaction。
容量不足時に部分的なgraphを作らない。

fan-outした結果は検証済みSUCCESSのみ後続を解放する。fan-inは保存済みchild ID、
run ID、SHA-256 result hashと最大2048文字の結果を受け取る。
結果は信頼できない観測データとして扱い、欠落・切り詰めを明示する。
FAIL_FASTは失敗を検出して実行中の兄弟を取消し、後続を止める。
CONTINUE_INDEPENDENTは無関係な枝を継続し、失敗した依存の後続をBLOCKEDにする。
部分成功はPARTIAL、結果不明はUNKNOWNとしてreceiptに残す。

graphの元のモデル回数上限と消費量、token消費量はchildと親Run/Goalの予算と共有する。
並列モデル予約は個数で保存し、一件のusage報告で別の未報告予約を消さない。
期限は `rei.subagents.dag-timeout`（1–120秒、既定120秒）。
取消し・期限切れ後に自動再試行しない。再起動時も実行を自動開始しない。

`getSubAgentGraph` は現在のProject/root/human sessionのreceiptを読む。
再開は実際のユーザー要求 `subagent graph resume GRAPH_ID REVISION` と
`resumeSubAgentGraph` の引数が一致する場合だけ許可する。
元の予算、保存計画、agent定義/Git baseline、revisionを維持し、完了済みchildを再実行しない。
graphの子を通常の `resumeSubAgent` で個別実行することは拒否する。
UNKNOWN Tool副作用は元のchildの個別reconciliationが必要で、自動再実行しない。
上限付きtoken使用量が不明な場合は予算をリセットして再開できない。

DBは既存 `subagent_checkpoints` のversion 1 JSONへ `kind`、`graphId`、
`pendingModels` を追加する。旧レコードはCHILD、旧pending booleanは1予約として読む。
保存graph ID、child所属、結果参照を検証し、別graphの計画差し替えを拒否する。
ローカルSQLite・fake ChatModelの結合テストで検証し、有料モデルは呼び出していない。
