# context要約token予算 実装レポート

## 実装

LlmConversationCompressorは既に共有LLM呼出回数を消費していたが、usageを共有token予算へ報告していなかった。親と同じMessageAggregatorでstreamを集約し、要約を返す前にRunExecutionContextへtotalTokensを報告する。既存Goal portを通じて永続使用量にも計上される。新しい設定・Goal・Run・予算DBは追加しない。

使用量不明・超過は既存のExecutionStoppedExceptionへ渡す。ContextAssemblerが予算停止を再throwする既存処理と、StagnationChatModelが要約後に親モデルの開始可否を再確認する既存処理を再利用する。既知usageは品質判定／length失敗の前に記録し、Provider障害／timeoutで未報告なら不明状態へ止める。取消は既存の停止理由とHTTP取消を維持する。

## 検証

最初のテストではProvider optionsのmock戻り型を実際のOpenAiChatOptionsへ修正した。その後、totalTokensが0のままになることと、超過／使用量不明を停止できないことによるRed（3 tests、3 failures、0 errors）を確認した。

Greenでは集約usageの一度だけの計上、事前予約済み親の開始拒否、usage欠落・Provider障害・空応答、超過と既定無効、length応答の既知usage保持、timeout時のProvider取消を検証した。実圧縮経路ではGoal単独上限での親モデル非呼出し、SQLite使用量の再読込、超過／不明要約の非保存と上限ちょうどの保存を確認した。品質不足で不採用の要約も使用量を維持し、再生成を行わないことを確認した。

context圧縮・取消・実Chat・境界・Run/Goal予算の関連テストはPASS。全体回帰はoffline Maven・JDK25・full profileで3079 tests / 589 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactのテストは再実行していない。

## Git

独立ブランチcodex/context-summary-token-budget。feature Commit/Push→main Merge→Merge後関連テスト→main Pushの順に進める。検証済みhashは後続の作業記録へ保存する。

## 制限

RunContextを継承した要約を対象とする。RunContextなしの要約・Sleep／記憶整理・CLI・embedding/rerankは対象外。API報告後の停止条件であり、請求費用の厳密な上限を保証しない。実LLMの要約品質・実Providerのusage品質は未評価。詳細は[設定と範囲](context-summary-token-budget.md)。
