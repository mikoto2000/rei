# MCP Server Testing

実LLM・有料APIを使わず、SQLite、一時Project、mock WorkContext/approval、テストexecutor、実Tomcat listener、実MCP SDK clientで検証する。

## TDD記録

- McpIngressPolicyTest: 実装クラス不在によるRed → loopback/port/Host/Origin判定によるGreen。
- McpStorageMigrationTest: Migration不在によるRed → 既存schema migrationにMCP index追加によるGreen。
- SubAgentRunnerTest.childCannotExpandMcpReadOnlyAuthority: READ_ONLY親の子がEXCLUSIVEになるRed → 親mode継承によるGreen。
- McpToolPermissionTest: 個人メモリ・任意PDF・processログの読み取り許可となるRed → MCPの固定read許可リストによるGreen。
- 実行開始済みRunを強制終了するworkerのRed → RUNNING後にkillするGreen。
- 私有データ・別captured root読み取りとtombstone条件本文のRed → MCP限定file-openと期限処理のGreen。
- 既存Checkpointがあるreplay受付のtask_id欠落のRed → Run/Sessionと別のtask_idを返すGreen。
- その後のツール・HTTP・結果投影・負荷制限はコンポーネント/結合テストで検証する。

## 実行

```powershell
./mvnw.cmd test '-Dtest=Mcp*Test' -Pfull
./mvnw.cmd test '-Dtest=SubAgentRunnerTest' -Pfull
./mvnw.cmd test -Pfull
```

このWindows環境ではwrapper準備時の待機を避けるため、既存Maven 3.9.14 executableを直接使い、`-Dmaven.repo.local=F:/project/rei/.m2/repository -o`を付けて同じMaven goalを実行した。

## 検証範囲

- initialize、6ツール一覧と6ツール呼び出し、No authentication入口。
- 無効時listenerなし、通常Web portのMCP拒否、専用portのAPI隔離、Origin拒否、接続元/接続先/Host/転送ヘッダー拒否、本文64KiB、decoded path。
- 既存Web Bearerの401/認証成功200、既存ConversationAdmissionとStorageMigration回帰。
- Project ACL・Session/Run所有権、Shell Session拒否、権限指定拒否、READ_ONLY選択通知抑止、子Run mode継承。
- 同一要求replay、payload競合、並列重複、response/SSEに依存しない照会、期限切れtombstone。
- 永続Run状態、実子JVM強制終了と再起動owner喪失UNKNOWN、immutable結果、秘密形式redaction、ページ分割、cursor改ざん/別Run/別Project、結果期限切れ。
- 保存済み証拠の検証状態投影、MCP実行2/待機8、取消後と終了後の枠解放、通常Run上限への影響。

ローカル全回帰: 5,135件、failure=0/error=0/skip=5、BUILD SUCCESS（14分02秒）。コマンドはMaven 3.9.14に `-o -Dmaven.repo.local=F:/project/rei/.m2/repository -Dtest.dataDirectory=target/mcp-final-data -Pfull test`。skipは既存環境条件による。

その後のMCP安全境界追補は `-Drei.build.directory=target/mcp-security-tdd -Dtest=Mcp*Test -Pfull test` で31件、failure/error/skip=0。CLI/contract/launcherは `-f terminal/pom.xml test` で81件成功。PRの同じHEADに対するWindows全回帰CIもマージ前に確認する。

初回全回帰で設定テンプレートの不足と、旧schemaを再現するfixtureが新テーブルを残す失敗を検出し修正した。途中の未公開schema v7テストDBに列が足りない起動失敗は、新しい専用test.dataDirectoryで解消した。実データの検証条件は緩めていない。

実OpenAI Secure MCP Tunnel接続、実LLMによる調査品質、全形式の秘密検出、実外部プロセスのcleanup/rollback完了は確認済みではない。
