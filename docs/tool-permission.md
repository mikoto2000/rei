# Tool Permission Policy

`rei.tool-permission.enabled=true` で、Chat と SubAgent の Tool callback 実行直前に共通 Policy を適用します。
既定では無効で、既存 SubAgent allowlist / Computer Use SafetyPolicy / 外部 review の明示要求条件は引き続き適用されます。

```yaml
rei:
  tool-permission:
    enabled: true
    auto-approve: [READ, NETWORK_READ]
    denied: [DESTRUCTIVE]
    capabilities:
      myReadOnlyMcpTool: [NETWORK_READ]
```

能力は READ、LOCAL_WRITE、EXECUTE、NETWORK_READ、NETWORK_WRITE、EXTERNAL_SIDE_EFFECT、DESTRUCTIVE。
判定は AUTO_APPROVE、REQUIRE_APPROVAL、DENY。禁止能力が1つでもあれば DENY、必要能力がすべて auto-approve にあれば AUTO_APPROVE、
それ以外は REQUIRE_APPROVAL です。分類の上書きは管理者設定に限定し、LLM の引数や自然言語の「承認済み」では解除しません。

readMultiFile/grepMultiQuery/readPdfFile/searchAndRead、today/now/findFile/listFile/getShellProcessStatus は READ、
webSearch/webSearchAndRead は NETWORK_READ、searchKnowledge は READ + NETWORK_READ。
applyTextDiff/writeMultiFile/createDirectory/copyFile は LOCAL_WRITE。
deleteFile/moveFile/killShellProcess は LOCAL_WRITE + DESTRUCTIVE。
その他は任意の能力を使える可能性があるため全能力として扱います。runCommand/executeExternalProgram、
未知 MCP、Computer Use、外部 delegation もこの保守的な既定値の対象です。
管理者が安全に分類できる Tool のみ capabilities へ追加してください。

確認必要/禁止時は callback を呼ばず、所有 Project/Session/Run を持つ既存 `tool.failed` を発行します。
errorType は PermissionRequired / PermissionDenied、code は REQUIRE_APPROVAL / DENY。
引数をイベントへ追加しません。Shell のエラー表示、Agent UI Projection、Web SSE が既存経路で通知します。
監査イベントの保存に失敗した場合も実行しません。

この段階では対話中の承認待ちキュー・承認ボタン・一回限りの承認 token はありません。
操作は拒否され、管理者が設定を変更・再起動したうえでユーザーが要求を明示的に再実行します。
Policy 自体は LLM を呼びません。起動時に設定を読み込み、プロセス内で固定します。
Tool の引数（対象 path/URL/command）単位の制限、直接 slash command、背景 timer の内部処理、
LLM provider の通信、Tool callback 以外の I/O はこの境界の対象外です。
これを OS sandbox の代替や自律実行全体の権限保証として扱わないでください。
