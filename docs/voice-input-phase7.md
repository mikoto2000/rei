# 会話モード（Phase 7）

## 仕様

`/mode` は応答のスタイルをSessionごとに切り替える。既定はautoで、音声入力はconversation、文字入力はnormalへ受付時に自動切替する。
既存の実行モード `AgentRunContext.Mode.CONVERSATION`（ツールを制限する並行相談）とは独立した `ResponseStyle` を使う。
会話スタイルを選んでも、通常のShell会話はEXCLUSIVEの共通Gateway・ChatClient・Planning Loop・Tool・Permissionを通る。

```text
/mode auto
/mode normal
/mode conversation
/mode conversation --voice-only
/mode status
```

`conversation` はテキスト・音声の両方に適用し、`--voice-only` は音声だけに適用する。
`--voice-only=false` またはオプションなしの `conversation` で両方へ戻せる。
Session未選択で設定すると新しいSessionを作成する。statusはSessionを作らない。
`/mode normal` は音声も通常に固定し、`/mode auto` で自動切替へ戻す。新しいSessionはautoから開始し、別Sessionの設定を継承しない。Sessionの終了・再開やアプリ再起動後も保存した設定を維持する。

応答指示は、既存の「れい」のキャラクターを保ちながら自然な会話調、原則短め、不要な前置き・反復・箇条書きの削減を促す。
毎回無理に質問で締めくくらない。詳しい説明を求められた場合や重要な結果・失敗理由・承認事項では必要な情報を省略しない。
応答文字数・トークン上限を削減せず、表示・保存された応答を切り詰めない。実際の言い回しは利用モデルにも依存する。

## 実装と互換性

- `SessionMetadata` にresponseStyle / voiceOnlyを保存し、`FileSessionRepository`の原子的保存・受付失敗時のロールバック・単調更新時刻を維持する。応答スタイルのない古いSession JSONはAUTO / falseとして読む。保存済みの明示NORMAL / CONVERSATION設定は維持する。Run JSONの後方互換既定はNORMALのまま。
- `SessionLifecycle`で入力受付時の有効スタイルを `AgentRunContext` へ固定する。キュー待機中に設定を変えても、受付済みRunは途中でスタイルを変更しない。
- `ConversationStyleAdvisor`は既存system promptに固定の会話指示を追加する。ユーザー入力・履歴・ツールオプション・予算を変更せず、コンテキスト圧縮後もスタイル指示を保持する。
- normalではスタイルAdvisorを追加せず、既存の応答経路へ戻る。会話指示を恒久的なユーザー履歴へ保存しない。
- `ConversationModeCommand`を既存RootCommandへ登録し、picocliのヘルプとサブコマンド・オプション補完を利用する。
- VOICEの認識確認とツール承認は別のまま。副作用Toolは引数に結び付いた一回限りの明示承認を必要とする。会話スタイル・呼びかけ・TTSは承認を与えない。

設定は通常の会話入力の共通受付に適用する。バックグラウンドGoalやSubAgentなどが独自に作成するRunの実行権限・応答規約は変更しない。

## 検証記録

Session設定・旧JSON互換とCLIの先行失敗を確認して実装し、関連31件成功。追加レビュー後の関連54件も成功。
テキスト/VOICEの実Tool呼出し、長い重要応答の完全保持、通常モードへの復帰、VOICE書込みの承認前拒否・承認後実行・再利用拒否、完了証拠不足の拒否、Session再開、保存ロールバック、履歴圧縮後のスタイル保持を確認した。

全回帰4,335件（失敗0・エラー0・既存条件付きスキップ1）/ 12分30秒、package成功。配布JARの別Springプロセスから共通Gatewayで実Agentへ通常テキスト・会話テキスト・音声限定のVOICE入力を送り、3件とも非空の応答とCOMPLETED履歴を確認した。試験プロセスの終了と標準エラー空、マイクOFF・ネイティブワーカー0、音声再生・録音ファイル保存なし。VOICE入力は合成テキストのenvelopeであり、新しい実マイク/ASR試験とは扱わない。回答例は [検証データ](voice-input-phase7-data/validation.json) に保持する。GitHub CI・main統合の最終状態とheadコミットは [PR #64](https://github.com/mikoto2000/rei/pull/64) のchecks / merge記録を参照する。製品変更commitは `62540ad1`、ブランチは `codex/voice-phase7-conversation-mode`。文書以外の変更後には必要な試験を再実施する。

## Phase 6からの統合確認

Phase 6はPR #63、head `50828a998824362987d9703af4eb82b23e94c0c3`。
ローカル全4,322件（失敗0・エラー0・既存条件付きスキップ1）、package、実Spring起動、SAPI可聴性、配布JARとDRY (VT-4)の20秒自己音声抑止診断に成功した。
GitHub CI run `38012307258`が同headでSUCCESS、未解決レビューコメント0、通常マージの要件を確認してmain `11614a1b393e7635ed6dc34deb058c6efe0ef772`へ統合した。
このmainをPhase 7ブランチ `codex/voice-phase7-conversation-mode` の起点としている。

Phase 6のGPU推論・専用日本語KWS・発話者識別・物理AEC・TTS中の音声割り込み未対応、およびPhase 5のCPU投入p95 3秒目標未達は残る。
