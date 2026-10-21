# Phase 1〜5の移行計画

以下は調査に基づく**提案**です。Phase 0で移動・分割は実装していません。
根拠のソースと定量値は[集計](package-dependencies.md)、[循環](dependency-cycles.md)、[Tools](tools-analysis.md)を参照してください。
推奨順は **1 → 2 → 3 → 4 → 5**。配線を先に分離すると、処理本体の境界を動かす際のSpringへの影響を観測しやすくなります。
Phase 3の契約棚卸し・互換snapshotだけはPhase 1と同時に始められます。

## Phase 1: 横断配線をbootstrapへ

対象は `core.chat.AgentRunConfiguration`、`web.WebApiConfiguration`、`web.BackgroundRunConfiguration`、
`core.configuration.AiConfiguration`。全Configurationを機械的に移す方針ではなく、機能内に閉じた設定・Propertiesは残します。

- 最初のPR: AgentRunConfigurationのBean配線を`bootstrap`へ。UIのChatResponseNarrator、Session、Run、voiceを結ぶため、
  [import:3](../../src/main/java/dev/mikoto2000/rei/core/chat/AgentRunConfiguration.java#L3)から境界の混在が明確です。
- 次のPR: WebApiConfiguration / BackgroundRunConfigurationから共通Run/Sessionの組立だけを切り出す。
  Web固有security/servlet条件はWeb側に残し、[BackgroundRunConfiguration:22](../../src/main/java/dev/mikoto2000/rei/web/BackgroundRunConfiguration.java#L22)の条件を維持する。
- 次のPR: AiConfigurationのTool登録とAdvisor順序を独立したProviderへ。モデル設定・Bean名・条件を固定する。
  [登録:406](../../src/main/java/dev/mikoto2000/rei/core/configuration/AiConfiguration.java#L406)を互換性の基準とする。

期待効果は処理本体のUI逆依存と配線をさらに判別しやすくすること。起動条件が影響範囲です。
bean名（クラス移動による既定名含む）、qualifier、scope、条件、遅延ObjectProvider、Advisor順序、native hintsを維持します。
必要テストはShellのみ/Webのみ/voice on-offのContext起動、条件付きBean不在、MCP/Tool公開、起動停止・SSE・音声回帰。
完了条件は旧Configurationの横断辺が減り、bootstrap以外の同等の配線が増えず、full回帰とC予算の縮小・D予算の移動対応レビューが通ること。
クラス移動は辺の完全修飾名を変えるため、旧辺→新辺の対応をPRで示してDを個別更新します。
bootstrapへの移動により機能間と数える辺が増える場合もあるため、Dの総数だけを改善指標にはしません。
`ReiApplication`のShellループは別のUI責務であり、SpringBootApplication注釈だけを理由に丸ごとbootstrapへ移しません。

## Phase 2: Toolsの責務分割

対象は`core.Tools`（3,750行・53 Tool）、`DeliveryToolRegistration`、`AiConfiguration`、各処理サービス。
既に別Toolクラスがある機能は統合を急がず、登録集合の重複・欠落を先に検出します。

| 分割PR | 内容 | 期待する改善 |
| --- | --- | --- |
| 2a | 実callbackの公開契約snapshot、旧入力fixture、Providerインターフェース | 分割前後の差を可視化 |
| 2b | File / Search Adapterと明示的なFile操作サービス | キャッシュ・token配信・検索状態の所有者を明確化 |
| 2c | Process AdapterとプロセスApplication Service | 実行・停止・所有Run・timeoutを局所化 |
| 2d | Document / Change / Review / Repair Adapter | 提案・検証・Apply・receiptと承認の境界を明確化 |
| 2e | Goal / Stage / Projectを所有者付きApplication Serviceへ委譲、Registry集約 | 共有Toolsへの機能集中を縮小 |

公開Tool名・説明・schema・default・returnDirect・JSON戻り値・エラー・delivery種別を固定します。
`Tools.diagnosedOwner`、ToolContextのRunExecutionContext、イベント・共有キャッシュを暗黙のsingleton状態として複製しないこと。
PolicyはAdapterだけで完結させず、現在の実行・承認経路と整合させます。
必要テストは[Tools分析の契約テスト案](tools-analysis.md)、Policy拒否/承認、read-only/full-auto、Run偽装・別root拒否、
キャンセル、raw結果・配信サイズ、ファイルとプロセス回帰。
完了条件は登録snapshot差分ゼロ、関連full回帰成功、残るToolsが互換facadeのみ、循環とcore予算がレビュー可能に縮小すること。

## Phase 3: CLI共有契約のMavenモジュール化

現状、[terminal/contract/pom.xml:4](../../terminal/contract/pom.xml#L4)は`../../src/main/java`からinclude列挙で共有ソースを再コンパイルします。
`BackendEndpoint`、`ReiDataDirectory`、`UserInputParser`、`CliCatalog/CliGrammar`、`ActivityRequest`、
Paper/ExternalAgentのRequest、HistoryFormatter/ConversationLogEntry、CredentialRedactor/FailureMessages等が混在しています。
`terminal/completion`も同様にcore.completionとShellCompletionParserを選択します。
contractは22本、completionは18本のbackend共有ソースを選択します（同じファイルは本番統計で二重計上していません）。
**既にterminal側の複数Mavenモジュールは存在する**ため、全面再構成ではなく共有ソースの所有者を一本化する段階です。

- 3a: 共有DTO・enum・parser・OS保存先・redactionを棚卸しし、純粋契約と実装を分ける。CLI JSON/golden fixtureを追加。
- 3b: 純粋な契約をshared-contract Maven moduleへ移動し、backendとterminal双方がartifactを参照。
  初回はJava package名を維持し、二重定義とsource includesを除去する。
- 3c: completionの共有parser契約を整理し、completion実装・OS配置・launcher検出を別PRで検証する。

期待効果は二重コンパイル・include漏れ・backendとCLIの契約ドリフト防止です。
影響はCLIのビルド・配布とbackendのclasspath。JSON名、既定値、Jackson 2/3相違、picocli grammar、exit code、
endpoint所有権・保存パス、履歴フォーマットを変えません。
必要テストはbackend/terminal独立verify、旧CLI対新backend・新CLI対旧backend、serializer互換、
契約moduleにSpring/UI依存を入れないArchUnit検査、配布zip/launcher/CLI smoke。
完了条件は共有ソースの所有moduleが一意、sourceDirectory/include共有が不要、両ビルドと互換fixture成功。

## Phase 4: Run・Sessionの責務整理

対象は`application.run`（ChatSubmitService/RunService/RunRegistry/SessionRegistry）、
`application.session`（SessionLifecycle/SessionRepository/ShellConversationService）、
`core.chat`（AgentRunContext/ConversationInputRouter/ChatExecutionService）、`conversation.ConversationAdmissionStore`。

- 4a: 所有者付き不変Run/Session context、状態、エラー契約の配置を決め、例外型だけの逆参照を減らす。
- 4b: Session永続identity・lifecycleとRun一時registry・admissionを分ける。RunにあるSessionRegistryはTTL cacheで、
  永続Session identityを削除するものではない点を維持する。
- 4c: 入力gateway→admission→実行router→LLM loopのportを定義し、状態を通知する方向を統一。
  具体的な3境界循環は[図](README.md#3境界を経由する主要な循環)を参照。
- 4d: ShellConversationServiceのShell専用操作と共通Session用例を分離してUI adapterを薄くする。

期待効果は受付・所有権・実行・永続化の責務を局所化すること。特に状態遷移・並行性・再開が影響範囲です。
session ID/会話ID/root、idempotency、先にidentity保存する順序、Run終端、承認継続、取消・queued cancel、
Web/Shell/voice入力、Goal durable resume、SSE/replay/順序を保持します。
必要テストは既存Run/Session full回帰、同時受付・retry、他Project拒否、再起動・checkpoint再開、
取消競合・終端イベント一度のみ・history互換、UIへ逆依存しない新境界のArchUnit。
完了条件は図の循環を代表クラス辺から解消し予算を縮小、UI契約・保存データ互換と全回帰成功。

## Phase 5: core縮小と旧構造整理

対象は`core`直下の文書・修復・ファイルサービス、`core.configuration`、`core.command/completion`、
残るcontext/Policy/Process/Project基盤。これまでのPhaseで所有責務が確定したものから機能別packageへ移します。

- 5a: File/Document/Reviewの既存サービスを各所有機能へ移し、旧facadeを段階的に削除。
- 5b: command/completionのUI専用部分をadapterへ、純粋parserは共有契約へ。
- 5c: 不要な互換bridge・旧Configurationを削除し、coreを残すか機能名へ改名するかを再評価。

期待効果は曖昧な共通領域の縮小。影響はimport、Spring scan、reflection/native hints、設定メタデータです。
永続化された型名、外部設定のclass名、tool登録対象を調べ、公開契約を移動と同時に変えません。
必要テストはfull回帰、native metadata/build smoke、配布起動、古い設定・保存データ互換、
機能ごとの循環禁止とbootstrap以外からConfigurationへの依存禁止。
完了条件はcore各責務の所有者が明確、不要bridgeゼロ、新構造のhard ruleが有効、初期負債予算を削除または大幅縮小できること。

## 技術的リスク・追加する検査

確認済み: 処理本体の巨大SCCがあり、単純にトップレベルの層に分けるだけでは循環が消えません。
推定: 共通contextと実装サービスの同居が逆参照を増やしています。個々の依存の必要性は各Phaseで検証します。
提案: bootstrapからの一方向配線、処理本体→Configuration禁止、純粋contract→Spring/UI禁止、
Tool公開snapshot、Run/Sessionの状態・event契約を追加します。
反射・設定・動的Bean登録は静的検査だけでは保証できないため、起動と旧クライアント互換テストも維持します。
