# SubAgentの必須Tool応答条件

requiredToolCallsの各項目に任意のexpectedOutputを追加できる。SUCCESSを返すには、引数が一致する同じ実Tool応答の有効な引用と、指定したトップレベルフィールドの値の一致が必要になる。標準の構造化Tool応答を確認するための機能で、追加LLMを使用しない。

```yaml
evidenceTools: [readMultiFile]
requiredToolCalls:
  - tool: readMultiFile
    arguments: {}
    expectedOutput:
      found: true
```

上記は形を示す例。Tool名・引数・応答フィールドは使用する実Toolの仕様に合わせる。呼出しを自動実行したり、子のTool権限を広げたりする設定ではない。

expectedOutputは空でないJSONオブジェクトへ変換できるYAML値で、JSON表現4096文字まで。指定フィールドの値はJSONの型を含めて一致させる。実応答の未指定トップレベルフィールドは許容するが、指定値が入れ子のオブジェクト／配列の場合、その値全体の一致を要求する。キー順・空白は不問。欠落とnull、数値と文字列、真偽値と文字列は区別する。

実応答は重複キー・余分なJSON値のない単一JSONオブジェクトである必要がある。既存証跡の保持上限16,384文字を超えて切り詰められた応答では契約を満たさない。別の呼出しの正しい応答を、不一致の引数の根拠として組み合わせることもできない。応答条件を省略した既存定義の動作は維持する。

不一致は値を含まないvalidationErrorsを返し、既存maxRepairs・共有maxSteps・共通timeout・cancelの制約下で修復できる。PARTIAL／FAILUREは未達を報告できるが、引用の偽造は引き続き拒否する。これは実Tool応答と設定した条件の整合性を確認するもので、Tool内の判断の真偽や自由文全体の意味を保証しない。
