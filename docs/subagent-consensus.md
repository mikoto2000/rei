# SubAgent answer/evidence comparison

`rei.subagents.durable-enabled` と `rei.subagents.consensus-enabled` は既定false。
両方を有効にすると `compareSubAgentAnswers` が保存childの回答を比較する。
入力は2–8件の `{childId,resultHash}`。同じ保存taskを回答した、別のCOMPLETED child runが必要。
現在のhuman Project/root/session、保存SHA-256とrun IDを照合し、重複・stale hash・別所有者を拒否する。
比較はread-onlyで、モデルを呼ばない。

SUCCESS envelopeの `result.answer`（最大4096文字）と `result.evidence`（最大64件）を比較する。
総入力は131072文字。回答の前後空白と改行を正規化した文字列一致を判定する。
意味的に同じ回答かどうかをこの比較だけで断定しない。
返すsourceにはchild/run/agent/result hash/task hash/保存時刻、回答、引用と観測output hashを保持する。

- 異なる回答があればDISAGREEMENT、unresolved=true。多数側で少数回答を消さない。
- 同一回答でも、元runnerのevidence contractがなく、根拠が不足し、または現在のagent/Git baselineが変わった場合はUNRESOLVED。
- contract付き保存結果が一致すればAGREEMENT_REPORTED。ただし `truthVerified` は常にfalse。

evidence contractは既存runnerがそのrunの実Tool観測と引用/hashを照合する仕組みを再利用する。
比較APIへ新しいモデル主張を渡して根拠を追加することはできない。
共有output hashを別に示す。同じモデル・データによる相関があり得るため、
別run IDがあることを統計的独立性や正解の保証とはしない。

`judgeSubAgentAnswers` は追加の `rei.subagents.consensus-judge-enabled`（既定false）が必要。
EXCLUSIVE human Runの下で、管理者が定義したtools空のagentだけを既存runnerで実行する。
元Run/Goalのモデル回数・token予算、deadline、cancel、durable checkpointを再利用する。
比較全体を信頼できないデータとして渡し、judge入力は32768文字以内。
judgeの結果と比較結果を並べて返し、元の不一致・未解決・provenanceを上書きしない。
judge verdictは正解の証明ではない。ここでは有料モデルを呼ばずSQLiteとmock runnerで制御を検証した。

追加DB migrationはない。既存durable child ledgerを読む。
Toolは両方のChatClient構築経路に接続し、比較のみREAD権限、judgeは既存delegationと同じ権限境界を使う。
