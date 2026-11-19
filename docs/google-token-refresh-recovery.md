# Google token refresh recovery

Google の自動トークン更新は、一時的な失敗では5分から最大6時間まで待機を延ばして再試行します。
`invalid_grant` 等の再認証が必要な応答では、自動更新を停止し、`/schedule auth` または `/task auth` による
明示的な認証を案内します。バックグラウンドから認証ブラウザーを開きません。
OAuth 応答本文・トークンは診断ログへ出力しません。
