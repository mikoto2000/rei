# MCP Server Operations

[設定とツール契約](mcp-server.md) / [信頼境界](mcp-server-security.md)

1. 既存BackendのREI_API_KEYと起動方法を確認する。実データをバックアップする。
2. 既存登録済みProject IDからMCP許可対象を選ぶ。ファイルパスを許可設定に渡さない。
3. rei.mcp-server.enabled=true、専用port、allowed-projectsを設定する。READ_ONLY受付にはrei.conversation.concurrent-enabled=trueが必要。
4. Backendを一度だけ起動する。既存Migrationはschema v7へ更新する。別Backendを重複起動しない。
5. 同じPCから `http://127.0.0.1:8091/mcp` をSDKでinitializeし、6ツールだけが列挙されることを確認する。
6. 許可Projectを使った調査依頼でrequest_idを記録し、status→resultを照会する。APIのBearer認証維持も確認する。

ビルド後、既存REI_API_KEYを維持したPowerShellから foreground server を起動する例:

```powershell
.\start.bat --mode=server --rei.conversation.concurrent-enabled=true
```

MCP用のenabled/port/allowed-projectsは上記環境変数またはrei.mcp-server.*設定で指定する。起動中のBackendに重ねてこのコマンドを実行しない。

foreground起動ならCtrl+C、常駐起動なら既存Backendの停止方法を使う。設定を無効化して再起動すると追加listenerは作成されない。設定変更に再起動が必要。

## トラブルシューティング

| 症状 | 確認 |
|---|---|
| 接続拒否 | MCP有効フラグ、Backend Web起動、専用port重複、IPv4 127.0.0.1 |
| 403 | 通常Web portに接続していないか、正確なHost/Origin、転送ヘッダーがないか |
| NOT_FOUND | ACL、Project登録、MCP Session/Runの所有権。別Project IDで照会しない |
| FEATURE_DISABLED | rei.conversation.concurrent-enabled。EXCLUSIVEへの再送で回避しない |
| REQUEST_CONFLICT | request_idを別内容へ流用していないか。元の要求をstatusで照会 |
| REQUEST_EXPIRED | tombstoneが残る。タイムアウトを理由に新規request_idを自動生成しない |
| SESSION_BUSY / CAPACITY_EXCEEDED | 同一会話所有権、MCP実行2・待機8、既存全体上限。statusを照会して待つ |
| RESULT_NOT_READY | statusで実際のRun状態を確認。承認状態とRun状態は別 |
| RESULT_EXPIRED | 結果30日保持を過ぎた。受付tombstoneは保持される |
| RUN_STATE_UNKNOWN / UNKNOWN | 再起動・owner喪失。既存Checkpointを人間が確認し、二重実行を避ける |
| startupのunsafe configuration | 自動tool公開・stdio・transport・servlet mapping・専用port条件を確認 |

SSEイベントは復旧の正本ではない。通信断後は同じrequest_idでstatusを照会する。同じpayloadの再送は既存receiptを返し、Backend再起動後も自動実行しない。completion.execution_succeededとacceptance_statusを別々に判断する。

保持期間の設定は新しく保存する受付/結果に適用される。結果期限を後から変えて既存期限を自動延長しない。監査は設定保持期限で定期削除する。結果本文削除後のmetadata/tombstoneは再利用防止のため残る。

## 将来のSecure MCP Tunnel接続

`tunnel-client`のHTTP server URLには `http://127.0.0.1:8091/mcp` を指定する。transportはStreamable HTTP、MCP側はNo authentication。OpenAI側のtunnel ID・runtime key・workspace権限は別途必要。公式 [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) に従う。

トンネルのインストール・アカウント設定・ChatGPTプラグイン登録・外部接続確認はこの実装の検証に含めていない。

