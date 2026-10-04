# SubAgentの実行証跡検証

定義YAMLに `evidenceTools: [readMultiFile]` を追加すると、実際のTool応答を根拠として要求する。省略または空なら従来のJSON Schema検証のみ。evidenceToolsはtoolsの重複なし部分集合で、最大16件。既存のread-only Tool許可・親子のPermission境界は維持する。

有効時は、Runnerが正常に返ったTool応答にRun内の一意なevidenceId、tool名、inputSha256、outputSha256、output、truncatedを付けて子に渡す。ハッシュは実際の入力・応答全文から算出する。outputは先頭16,384文字に制限し、切り詰めを明示する。最大64応答まで保持し、超過時は実行失敗とする。証跡は実行ごとのメモリ内のみで、他Runや親の証跡を受け入れない。

最終JSONのresultにはevidence配列を含める。各要素のキーは次の4個のみ。

```json
{
  "evidenceId": "Runnerが発行したUUID",
  "tool": "readMultiFile",
  "outputSha256": "実際の応答全文のSHA-256",
  "quote": "受け取ったoutput中の完全一致する引用"
}
```

引用は空白のみを許さず最大2048文字。ID、Tool名、ハッシュ、引用を独立に保存した実応答と照合する。存在しないID、別RunのID、重複引用、Tool名やハッシュの偽装、応答にない引用を拒否する。配列は最大64件。SUCCESSにはevidenceToolsの各Toolについて少なくとも1件の有効な引用が必要。未実施の作業がある場合はPARTIALまたはFAILUREにできるが、その場合も証跡の偽装は許さない。

共通envelopeと任意のresultSchemaの検証後、COMPLETED確定前に照合する。resultSchemaを指定している場合はevidenceフィールドを許容するよう定義を合わせる。失敗時はFAILEDと値を含まないvalidationErrorsを親へ返し、未検証のraw回答は返さない。既存timeout、maxSteps、cancelを使用し、追加LLM呼び出しや自動retryは行わない。

これは構造化された根拠の整合性検証。Toolが正常に応答した事実と引用を保証するが、応答に書かれた内容の真偽、ファイルの現時点での存在、自由文summaryの推論、タスク全体の完了、コマンド成功を保証しない。自由文の矛盾検出や独立reviewerは今後の範囲。Shell/Process Toolは引き続き子に許可していない。

## 必須Tool呼出しの引数契約

任意の `requiredToolCalls` に、Tool名と実際のTool入力スキーマに沿った `arguments` オブジェクトを指定できる。最大16件で、Toolは `evidenceTools` に含まれている必要がある。省略時は従来のTool種別単位の照合を維持する。

```yaml
evidenceTools: [readMultiFile]
requiredToolCalls:
  - tool: readMultiFile
    arguments:
      files:
        - path: README.md
```

SUCCESSには、各契約について、そのToolとJSON引数が一致する実呼出しの有効な引用が必要。同じToolを別のファイルや検索条件で使っても満たされない。実行しただけで引用していない呼出しも満たさない。オブジェクトのキー順と空白は不問だが、全フィールド・値・配列順は一致する必要がある。省略値と明示null、追加フィールドも区別する。契約自体はToolの入力スキーマを置き換えず、必要なTool呼出しを実行する機能でもない。

設定引数はJSONに変換可能なYAML値のみ、JSON表現で4096文字まで。実入力の解析は16384文字までで、重複JSONキー・余分なJSON値・解析不能な入力では契約を満たせない。PARTIAL/FAILUREは未実施の契約を報告できるが、偽造引用は引き続き拒否する。診断は契約番号のみを示し、設定引数やTool応答の値を含めない。既存のbounded修復・timeout・maxSteps・cancelを共有し、検証自体はLLMを呼ばない。
