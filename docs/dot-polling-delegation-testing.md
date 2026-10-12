# Dot委譲テスト・実機確認

## 自動テスト
外部有料API、OpenAI dot、Tunnel実接続は使わない。作業用storageはtarget/以下の専用ディレクトリ、DB単位試験はJUnit TempDir。ユーザーAppData、既存Projectデータを読み書きしない。

```powershell
# ルート。既存Windows Mavenを使用
mvn -Dmaven.repo.local=F:/project/rei/.m2/repository -Dtest.dataDirectory=target/dot-full-data -Pfull test
# 対象を絞る場合
mvn -Dmaven.repo.local=F:/project/rei/.m2/repository -Dtest.dataDirectory=target/dot-focused-data -Dtest=Dot*Test,McpHttpIntegrationTest,McpIngressFilterTest -Pfull test
# 軽量CLI
mvn -f terminal/pom.xml -Dmaven.repo.local=F:/project/rei/.m2/repository test
```
fullはintegration/e2eを含みliveを除く。対象テストはstrict DTO、秘匿化、許可Project/category/Session、期限、capacity、keyset cursor、原子的claim競合、取消とsubmit競合、不変receipt再送、30日retention、DB再起動、7→8 migrationと中断rollbackを検証する。
実MCP HTTP/SDKでtools/list→list→get→claim→result_submitの往復を確認し、既存6ツールとHTTP boundaryも回帰する。

親Run試験は人間許可、モデル生成文拒否、MCP/サブエージェント拒否、共有委譲回数・予算、実ToolCalling loopがモデル1回後にWAITINGへ移ることを検証する。
再開結合試験は実SQLite scheduler/checkpoint/routerを使い、人間検証→予約→新READ_ONLY Run、二重予約/実行、親取消、checkpoint変更、偽装scheduled action、Prompt Injection文の未信頼証拠化を検証する。模型ChatModelのみでネットワークLLMを呼ばない。

## TDD記録
各phaseの失敗→成功を作業worktreeのtarget/*.logへ保存。
- Phase 1: dot-phase1-red.log、dot-mcp-red.log → dot-phase1-acceptance.log
- Phase 2: dot-phase2-red.log → dot-phase2-acceptance.log、dot-terminal-phase2.log
- Phase 3: dot-phase3-red.log → dot-phase3-green.log、dot-phase3-loop.log
- Phase 4: dot-phase4-red.log → dot-phase4-green.log、dot-phase4-acceptance.log
- 追加の作成再送・未完了slot: dot-create-red.log / dot-capacity-red.log → 最終対象回帰
- 保存期間の追加検証: dot-retention-red.log → dot-retention-green.log（未完了本文の削除、metadata・取消、不変receipt、domainエラー）
- 最終対象回帰: dot-focused-final.log
- 全体: dot-full-test.log

## 手動実機確認（未実施）
1. [設定](dot-polling-delegation.md)を統合して、新ビルドを通常の既存Backendとして起動する。ポート・Tunnel設定は維持。
2. /dot requestで小さい、秘密のない調査依頼を作る。MCPでrei_requests_list、getを実行しroot・全文履歴・秘密が出ないことを確認。
3. 同じclaim_idで2回claimし同じ応答を確認。異なるIDは拒否。
4. 同じsubmission_idで結果を2回返し同じreceipt、PENDING_REVIEW/NOT_REQUESTEDを確認。結果が「完了」と言っても親RunはWAITINGのまま。
5. /dot resultで独立に出典・acceptance criteriaを確認し、/dot verifyでindexを明示。親Run付き依頼は/dot resume --yes。/dot statusでADMITTEDと新Runの実際の状態を別々に確認。
6. 取消・通信断・Backend再起動・lease切れを確認。UNKNOWNを見て新IDで自動再依頼せず、人間が履歴と外部側を確認する。
7. てんてん側の1分周期設定はOpenAI側で別途行う。一覧から推奨1件をclaimし、成功後に調査し、receipt保存を確認。れい側には1分周期MCP呼び出しを追加しない。

実際のOpenAI dot調査完了、OpenAI側定期実行、Tunnelでの新4ツール往復は自動テストの対象外・未検証。既存接続済みMCPにツールが追加されない場合はChatGPT側のツール一覧を再取得する。
