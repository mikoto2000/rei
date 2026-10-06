# 文書・図の編集案を作るDocument Agent

既存SubAgentのサンプル [document-editor.yaml](../config/subagents/document-editor.yaml) を追加した。新しい実行基盤は作らず、既存 `delegateTask`、共有LLM予算、8step/120秒の制限、キャンセル、Schema validationと最大1回の回答修復を利用する。編集対象は既存の非空UTF-8単一ファイル（本文/提案各64KiB以内）。通常テキスト・Markdown・Mermaid・PlantUMLを自然言語で編集する。

利用する場合は既存researcher/reviewerと同様に、サンプルを `rei.subagents.directory` のディレクトリへ配置して `/subagent reload` する。開発時は `rei.subagents.directory=config/subagents` を指定できる。ユーザー設定の自動追加・上書きは行わない。

例えば「document-editorでdocs/design.mdの説明を簡潔にして。まず差分を見せて」「document-editorでdiagram.mmdのBからCへの関係を追加して。まず案を見せて」と依頼する。親は次の既存Toolを使う。

1. `readTextChangeSetBase(path)` で正確なpath/text/sha256を取得。
2. `delegateTask("document-editor", 自然言語の依頼, baselineと必要な文脈)`。子は読み取り専用で、proposal・参照資料・未検証事項を返す。
3. 成功かつproposalがある場合、親が指定したpathとbaselineを照合し、`proposeTextChangeSet({path,expectedText,replacement})` に本文を渡す。format/reasonは変更要求の確認に利用し、ツール引数に混ぜない。保存時も実ファイルとのexact baseline・Project/root・機密パス・byte上限を検査する。
4. 保存ID、diff、未検証範囲をユーザーへ提示。ファイルはまだ変更されない。
5. 明示適用要求と既存Policyに従い、正確なID/proposalSha256で `applyTextChangeSet`。不要なら `discardTextChangeSet`。

完全baselineがない場合や作業が未完了ならPARTIAL、proposal=nullを返す。変更不要でもnullにできる。親は失敗/部分結果を自動適用しない。保存後に元ファイルが変わればSTALEとなり、読み直しから新しい案を作る。再起動後も保存差分は確認でき、APPLYING/FAILED_UNCERTAINを再送しない。

子にはShell、ファイル書込、Change Set保存/適用、再帰委譲を公開しない。資料内の命令はuntrustedとして扱う。モデルが挙げたsource/quoteはSchemaで形を検査するだけで、事実や引用の正しさを保証しない。出典確認・図の構文検証/rendering・要件充足は親が独立確認し、未実施の検証を成功として扱わない。図の編集でも既存node ID・関係・周辺文書を維持するよう指示する。

DOCX/PDF/PPTX生成、複数ファイル編集、図の専用renderer、自動適用はこの機能に含めない。既存テキスト編集と共通Change Setの機能を利用するため、追加モデルへの無条件呼び出しや権限拡張はない。
