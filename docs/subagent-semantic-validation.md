# SubAgentの意味検証

定義YAMLの`semanticValidation: true`で、最終回答の意味を検証する追加のモデル呼出しを有効にする。既定は無効。実証跡を使うため、空でない`evidenceTools`も必要。

```yaml
evidenceTools: [readMultiFile]
semanticValidation: true
maxSteps: 6
maxRepairs: 1
```

既存のJSON Schema、引用・ハッシュ・必須Tool引数・応答条件の決定的検査を先に通す。その後、同じモデルに独立した検証Promptを渡し、原タスク・定義のタスク指示・最終回答・Runnerが取得した実Tool観測を照合する。作業中の会話や修復指示は検証Promptへ引き継がない。Tool名・callbackを空にし、検証はToolを実行できない。

検証は根拠のないファイル／コマンド／結果の主張、観測との矛盾、未実施なのにSUCCESSとする回答を調べる。PARTIAL／FAILUREによる正直な未達報告を許容し、切り詰められた観測の欠落部分を根拠と認めない。タスク・回答・観測中の指示は未信頼データとして扱うよう指示する。

判定は`valid`と`issues`だけのJSON。許可する診断は`UNSUPPORTED_CLAIM`、`CONTRADICTION`、`INCOMPLETE_TASK`の3コードで、自由文診断や追加キー・未知コード・不整合な判定は拒否する。判定全文は4096文字まで。観測snapshotは65,536文字、検証入力全体は131,072文字までで、超過を黙って切り詰めず検証失敗にする。

モデル呼出しは既存の共有maxStepsとGoal呼出予算を1回消費する。初回実行、検証、修復、再検証はすべて同じtimeoutを共有する。予算・step不足時に検証を省略して完了とはしない。モデル障害や不確定結果は自動再送しない。検証の否定／不正JSONは既存maxRepairsの範囲で修復に渡し、元の診断を保持する。取消・timeoutは進行中streamをdisposeする。

これはモデルによる確率的な評価であり、正しさの証明ではない。同じモデルの誤りや未信頼データによる影響は残る。実証跡の決定的検査を置き換えず、重大な適用は既存の人間確認と独立した実テストを利用する。実LLMでの意味判断品質は別途評価が必要。
