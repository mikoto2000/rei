# 共有Run報告token上限 実装レポート

## 実装

既存の共有呼出回数予算に、既定無効のmax-total-tokens-per-runを追加した。親Chat、子・並列子、意味検証、Skill選択、出力上限plannerのProvider報告totalTokensを同じRunへ計上する。新しいGoal／Sessionや予算DBは作らない。

超過応答からToolを実行せず、ちょうど上限なら追加モデル呼出しを拒否する。未知usageは設定有効時にfail closed。子のモデル障害／取消で報告を失った場合も後続を止める。固定停止理由を既存Chatの失敗Event／結果へ渡し、子でも停止理由を返す。旧constructor・port・既定設定は互換。

## 検証

テスト構文を修正後、未実装APIによるRedを確認し、共有計上・未知usage・超過・既定無効のGreenを確認した。実Chatの失敗、超過Toolの非実行、子の失敗使用量／正常使用量、事前予約済み親の開始拒否、Skill使用量・非fallback、plannerの解析前計上、設定テンプレートを検証した。関連のChat／SubAgent／Skill／出力分割／planner／取消テストはPASS。最初の全体回帰は3063 tests / 587 suites、failure/error/skip各0。

最終確認で予算本体の読み書きも同期し、親の直接予約と並列子の報告が競合しても更新を失わないようにした。8 workerから合計80000 tokenを直接計上する試験を追加した。最終全体回帰はoffline Maven・JDK25・full profileで3064 tests / 587 suites、failure/error/skip各0。Javaのみの変更でNative/Reactは再実行していない。

## Gitと制限

ブランチcodex/shared-run-token-limit、base main。feature Commit/Push→main Merge→Merge後確認→main Pushの順に進める。API報告後の制限であり、in-flight呼出しの費用を戻さず、並列呼出しでの厳密な超過防止は保証しない。Goal跨ぎ永続token上限・独立要約やSleep・External CLI・embedding/rerankは残件として区別する。実Providerのusage品質は評価していない。
