# 共通LLM接続基盤の統合検証

OpenAI API、ChatGPTサブスク、ローカルLLMを共通カタログ・選択・Run固定で扱う実装の記録。
操作は[利用ガイド](chat-llm-connections.md)、サービス別の未確認範囲は
[互換性マトリクス](chat-llm-compatibility.md)を参照する。

## 段階的な実装

各Phaseを専用worktreeで実装し、PRの最新head、通常マージ、実際のmerge commitに対する
push CIを確認してから、所有するworktreeとブランチを通常のGit操作で片付けた。
元のworktreeの変更と、別の作業用worktreeは保持した。

| Phase | 実装内容 | PR | マージコミット | テスト結果 | CI | Worktree | 状態 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 棚卸し・SDK境界 | [#640](https://github.com/mikoto2000/rei/pull/640) | [7afd501](https://github.com/mikoto2000/rei/commit/7afd50192bca1b5b4cf3eeb77ab02e2accf55fe7) | 8360件・12skip | PR/push成功 | 削除済 | 完了 |
| 1 | 共通プロファイル・能力宣言 | [#641](https://github.com/mikoto2000/rei/pull/641) | [ad0d810](https://github.com/mikoto2000/rei/commit/ad0d810ae1d61fbd0bee0d3e0dea65e38d06dc80) | 8449件・12skip | PR/push成功 | 削除済 | 完了 |
| 2 | Run固定・通常チャット統合 | [#642](https://github.com/mikoto2000/rei/pull/642) | [13aee01](https://github.com/mikoto2000/rei/commit/13aee01fc0c2ebfcf3bdafb9e381c170a4389c09) | 8656件・12skip | PR/push成功 | 削除済 | 完了 |
| 3 | CLI切り替え | [#643](https://github.com/mikoto2000/rei/pull/643) | [44a01af](https://github.com/mikoto2000/rei/commit/44a01afde82400a3263fffb6ceb181e2444ee60b) | 8693件・12skip | PR/push成功 | 削除済 | 完了 |
| 4 | 名前付きResponses・固有制約 | [#644](https://github.com/mikoto2000/rei/pull/644) | [4ee90a5](https://github.com/mikoto2000/rei/commit/4ee90a5a12afbfa2a655132b616498601836f5bb) | 8773件・12skip | PR/push成功 | 削除済 | 完了 |
| 5 | Plan通常チャット・要求枠 | [#645](https://github.com/mikoto2000/rei/pull/645) | [0f44c26](https://github.com/mikoto2000/rei/commit/0f44c26d5fe6e4c2b723dffa007e2159a2b6e67b) | 8931件・12skip | PR/push成功 | 削除済 | 完了 |
| 6 | 内容・宛先に結び付く承認 | [#646](https://github.com/mikoto2000/rei/pull/646) | [8df11f1](https://github.com/mikoto2000/rei/commit/8df11f16461bfffa709aa101d51b933fe00b0c88) | 9047件・12skip | PR/push成功 | 削除済 | 完了 |
| 7 | 実ツールループ・別プロセス再開 | [#647](https://github.com/mikoto2000/rei/pull/647) | [fcb550c](https://github.com/mikoto2000/rei/commit/fcb550cb97c13b0c6319f192fc9ef6a5ed6eaf52) | 9084件・12skip | PR/push成功 | 削除済 | 完了 |
| 8 | 履歴・媒体・生成設定・再送 | [#648](https://github.com/mikoto2000/rei/pull/648) | [9b9b5d8](https://github.com/mikoto2000/rei/commit/9b9b5d827a5fef0ac5ab19fc936070cdfe80c9d3) | 9229件・12skip | PR/push成功 | 削除済 | 完了 |
| 9 | GUI・モデル別宣言・競合検出 | [#649](https://github.com/mikoto2000/rei/pull/649) | [be92057](https://github.com/mikoto2000/rei/commit/be9205710ace535a1776e0c1b4317805d1c378ba) | 9253件・12skip | PR/push成功 | 削除済 | 完了 |

上表のJava試験は最終的に失敗・エラー0、既存の許可済みskip12件。CIは最新PR headと実際の通常merge commitのpush実行を確認した。

上表は最終結果。Phase 2の初回ローカル全回帰にはfixtureの失敗があり、修正後の関連試験と
最終CI全回帰で確認した。Phase 4は既存HTTP timeout試験、Phase 5はCI runner timeoutに
対する失敗ジョブの再実行を含む。Phase 5・8の初回ローカル全回帰で見つかった不具合・
fixture不整合は修正してから全回帰を完走した。途中の失敗や停止を成功として数えていない。

## 再利用した境界

- Run受付・既存エージェントループ・動的ツール選択・モード別権限・承認・Checkpointを共有。
- 既存のOAuth・DPAPI保護ストア・登録・refresh・logoutを再利用。認証基盤を複製しない。
- Spring AI 2.1.0 / OpenAI Java SDK 4.101.0の型付き要求を使用。JSON bodyのinterceptor書換えは行わない。
- 旧flat Chat Completionsと閉じたResponsesProductionPolicyを維持。名前付きローカルへ旧OpenAI固有制限を一般化しない。
- 通常チャットの動的選択を補助推論・Scheduler・Goal・SubAgentへ暗黙継承しない。

## Phase 10の最終確認

最終Java全回帰は**9289件、成功9277件、失敗0、エラー0、既存skip12件**、28分31秒。
1449個のJUnit XMLが今回の実行で更新されたこと、9289個のsuite/test IDの重複がないこと、
12件のskip IDと理由が既存の許可一覧に完全一致することを確認した。

Checkstyle・SpotBugsは各0件、解析エラー・欠落クラス0、Spotless3311ファイル。
terminalは205件成功・skip0、配布物verifyも成功した。Java以外のクライアントソースは
Phase9から変更せず、最新PRのクライアントCIでも回帰を確認する。

依存関係の正規化集合とSHA256は6種すべてPhase9と完全一致。
A=0、B=0、C-business=2914、C-wiring=799、D=402、E=1077。
baseline・分類・budget・suppression・skip定義は変更していない。

関数呼び出しの能力が未確認・非対応の名前付きChat Completionsで送信が進む差を検出した。
修正前は20件中8件の想定したassertion failure、修正後は20件成功。
Responsesと同じ共通チェックを用い、HTTP・資格情報参照より前に拒否する。
JSON Schemaの内容とstrictの真偽は、実SDKのChat Completions / Responses送信で8件確認した。

隔離したアプリ起動で、Plan有効・非Web構成では既存SubAgentに必要なRunRegistryが
不足する問題も検出した。Web・Slack・明示登録がある場合はその台帳を保持し、それらの
ないShell起動にだけ既存RunRegistryを用意する。修正前は起動2件と構成1件が失敗した。
修正後は実起動2件・構成6件を含む関連回帰152件がすべて成功。モデルサービスへの
起動時要求は0件。Java全回帰、最新ソースの静的解析、terminal配布物検証を別々に確認する。

## 安全境界の最終確認

認証なし接続ではSDKに空のキーを明示し、Authorization/API-key/Cookieを送らず、
資格情報のsupplierを呼ばない。キーとOAuthは固定した登録から送信直前に取得する。
送信先のURI・method・Host変更を拒否し、redirect・proxy・SDK自動retryを使わず、
送受信のバイト数と時間を制限する。公開プロファイルにキー・URL・資格情報値は含めない。

送信承認のdigestは実際の入力と公開するtool定義を含む。宛先・登録・所有者・Runと
結び付け、入力変更で新しい承認を要求する。管理者DENYが優先し、ツール権限・モード制限を
送信承認で解除しない。Planの要求枠は要求単位で解放し、異常終端を成功や自動再送にしない。

## 実サービスと残る確認

実OpenAI API・実ChatGPTアカウント・実ローカルモデルへの推論は実施していない。
所有するloopbackまたはDNS前fixtureの契約試験であり、実モデルの推論能力・利用枠・
サービスの提供条件の証明ではない。実サービスの版・モデル・template別確認は
互換性表の手順と記録様式で、利用者が許可した接続先に対して行う。

利用可能なモデルIDや能力宣言の自動推測、非対応フィールドの削除再送、別アカウントや
API課金への自動フォールバック、未知のreset時刻による自動再開は実装しない。
実機GUIと実サービスを組み合わせた確認も残る。未確認機能はUNKNOWNとして扱う。

## 対応範囲と未実装事項

登録済み接続先・モデルの選択を動的に変更できる。設定ファイルへの接続先追加・
能力宣言の変更を再起動なしで読み直す機能は未実装。Chat Completionsのreasoningは
native保存形式を試験したが、設定経路からのreasoning replay有効化は非対応を維持する。
実サービス・実機GUIとの組合せは未確認で、能力宣言とfixture成功で代用しない。

## 今後の改善候補

許可された実接続ごとに互換性記録を増やし、サーバー・モデルの更新後に再検証する。
設定登録のホットリロードを将来追加する場合も、受付済みRunの不変スナップショットと
承認・Checkpointの登録束縛を維持する必要がある。現在は設定登録の変更に再起動が必要。
