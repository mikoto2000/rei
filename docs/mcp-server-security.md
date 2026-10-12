# MCP Server Security

## 信頼境界

このMVPは本人専用・認証なしである。127.0.0.1は利用者認証ではない。同じPCのプロセスはMCPを呼び出せる。Host/Originも悪意あるローカルプロセスを認証しない。利用PC・tunnel-client・OpenAI側のアカウントと権限を管理者が信頼できることが前提。

専用Tomcat connectorをIPv4 127.0.0.1に固定する。接続元・接続先・接続先ポート・Hostを検証する。Hostは127.0.0.1:<専用ポート>のみ。Originは欠落または同じhttp originのみ。null/外部origin/重複Host/Origin/Forwarded/X-Forwarded-*を拒否する。ただしSecure MCP Tunnelが送るX-Forwarded-Client-Certだけは認証情報として信用せず、SDKや後続フィルターへ渡す前に破棄する。このヘッダーによって接続元・Host・Originの検査を省略しない。DNS rebindingと通常WebポートへのMCP要求を拒否し、MCP専用ポートには通常APIを公開しない。servlet decoded pathも検証する。

tunnel-clientは上記URLへ直接接続する。Originを転送する構成なら許可originと一致することを実接続で確認する。Origin検証を解除して解決しない。

## 認可

固定local-mcpの権限は READ_CONTEXT、SUBMIT_READ_ONLY、READ_OWN_RUN、CANCEL_OWN_RUN。明示Project ACLは既定ゼロ。Sessionは同じprincipal・Projectに所有されるMCP Sessionだけ。Runのstatus/result/cancelでも毎回Project所有権を検証する。IDの知識だけで権限を得られない。

READ_ONLYを既存ToolPermissionGuardへ渡す。自然言語、Work Context、文書、検索結果、tool outputは権限変更の根拠にならない。SubAgentも親の実行モードを継承する。MCPでは既存のProject内snapshot読み取り・探索・repositoryMap/changeTestImpact・now・webSearch/webSearchAndReadだけを固定許可する。個人メモリ、任意PDFパス、汎用URL取得、process生ログは既存READ能力に分類されていても拒否する。write/git/設定/汎用shell/承認・再開をMCPで公開しない。既存Web APIは引き続きBearer認証を要求する。

## 保存と出力

監査はprincipal、Project、Run、request_idのSHA-256、操作、判定、日時、correlation_idだけを保存する。instruction本文・認証情報・推論は記録しない。業務例外の生メッセージをMCPへ返さない。final assistant outputは既存 CredentialRedactorを通す。成果物はメタデータ参照まで。

既存redactorは認識する認証情報形式を伏せる処理であり、未知の形式や自由文に埋め込まれた全ての秘密を完全検出する保証はない。許可Projectと依頼内容を限定する。ローカルに悪意あるプロセスがある場合の機密性・資源消費は保証しない。

MCPのfile-openはcaptured Project rootに束縛する。設定されたRei data directory、.rei、代表的な認証ファイルとDBファイルはProject内に置かれていても拒否する。既存のsymlink/alias拒否を維持する。

結果cursor秘密鍵はstorage.dbに永続保存する。データディレクトリのOSアクセス権・バックアップを管理する。tombstoneはキー再利用防止のため長期保持される。大量の新規キーや監査要求によるディスク消費はローカル利用者側でも管理する。

## 取消と承認

取消受付・Run最終状態・実プロセスcleanup・rollbackを区別する。既存RunServiceで状態競合を解決する。cleanup_status=UNKNOWN、rollback_status=NOT_GUARANTEEDであり、停止・巻き戻し完了を保証しない。承認は既存人間向け手段で行う。承認だけでRunが再開するとは限らない。

OAuth2、トンネル導入、ChatGPT登録は実装範囲外。公開ネットワーク向けにこの認証なし構成を転用しない。

