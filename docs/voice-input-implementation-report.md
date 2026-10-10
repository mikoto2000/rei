# ハンズフリー音声入力・会話モード 実装記録

Java 25 / Windows x64 / DRY (VT-4) / Whisper large-v3-turbo FP32を使用する。
開始前に先行改善のmain統合を確認し、各Phaseを独立PRで通常マージした後に次へ進めた。
Phase 7のローカル検証と実Agent受け入れ結果を記録する。各PhaseのCI・main統合の最終状態は、下表のPRとそのmerge commitで確認できる。

## Phaseとmain統合

| Phase | 主な成果 | PR | mainコミット | 検証・設計・制約の記録 |
| --- | --- | --- | --- | --- |
| 0 | Java/JNI・Windows実行・既存入口の調査と隔離PoC | [#53](https://github.com/mikoto2000/rei/pull/53) | `536f20628b443ade2431bba856bb2dc8037cc9c6` | [Phase 0](voice-input-phase0.md) |
| 1 | キーボード/VOICEの共通Gateway・ID重複排除・Session固定 | [#54](https://github.com/mikoto2000/rei/pull/54) | `5b3cb780afd6fcbdbf79738324924f2f5c052614` | [Phase 1](voice-input-phase1.md) |
| 2 | Java Sound→VAD→発話区間→ASR→Gateway、状態・上限付きキュー | [#56](https://github.com/mikoto2000/rei/pull/56) | `45cded4acb6453fd21cf4b5a595a7a2c54ff1ee8` | [Phase 2](voice-input-phase2.md) |
| 3 | 明示承認付き取得・固定サイズ/SHA・atomic有効化・offline再利用 | [#57](https://github.com/mikoto2000/rei/pull/57) | `943a6f5975fa6bba55052f48325d05d3a35bbd22` | [Phase 3](voice-input-phase3.md) |
| 4 | Windows endpoint監視・VAD/ASR別JVM隔離・認識確認/訂正・復旧 | [#58](https://github.com/mikoto2000/rei/pull/58) | `0f251277502f3a79930797c4a6877fb3c9eee070` | [Phase 4](voice-input-phase4.md) |
| 5 | turbo FP32文字欠落のbyte保持修正・実マイク・比較精度/遅延評価 | [#62](https://github.com/mikoto2000/rei/pull/62) | `2bfd903eaf97d27a7fc17d07f55f487570f71ef9` | [Phase 5](voice-input-phase5.md) |
| 6 | 任意の呼びかけ・所有Run停止・Windows TTS・半二重自己音声抑止 | [#63](https://github.com/mikoto2000/rei/pull/63) | `11614a1b393e7635ed6dc34deb058c6efe0ef772` | [Phase 6](voice-input-phase6.md) |
| 7 | SessionごとのNormal/Conversation・音声限定・実行能力維持 | [#64](https://github.com/mikoto2000/rei/pull/64) | PR #64のmerge commitを参照 | [Phase 7](voice-input-phase7.md) |

補足変更として [PR #60](https://github.com/mikoto2000/rei/pull/60) のturbo FP32移行と [PR #61](https://github.com/mikoto2000/rei/pull/61) のUser枠表示をPhase 5の起点へ統合している。
ブランチ、headコミット、CI、TDDテスト、主要クラス、設計判断、未対応事項は各Phase文書とPRに記録する。

## 最終構成の利用手順

現在起動中の古いアプリには、Gitのmain更新だけでは新しいコードは反映されない。
新しいmainの配布JARをビルドしてアプリを起動した後、CLIで以下を実行する。
通常アプリの停止・再起動は自動では行わない。

```text
/voice devices
/voice device set <DRY (VT-4)の表示されたID>
/voice models info
/voice models verify
/mode conversation
/voice on
```

モデルが不足・破損している場合は、案内された配布元・固定SHA・容量・ライセンスを確認して取得を明示承認する。
現行一式は約3.25 GBで、旧モデルとは別ID・別キャッシュとして扱う。

```text
/voice models install --approve sherpa-1_13_8-whisper-turbo-fp32-2ca6ff69-silero-9e2449e1
/voice models status
```

音声だけ会話スタイルにする場合は `/mode conversation --voice-only`。通常へ戻す場合は `/mode normal`。
任意の音声応答・呼びかけ・停止機能は、マイクOFF後に明示設定する。

```text
/voice off
/voice features --tts true --tts-voice "Microsoft Haruka Desktop - Japanese"
/voice features --wake true --wake-word れい
/voice features --interrupt true
/voice on
```

呼びかけ有効なら「れい、こんにちは」、所有Runを停止するなら「れい、実行を停止」と発話する。
読み上げ中と既定800msの余韻中は入力を抑止するため、その間は話さずに待つ。
音声受付を止めるのは `/voice off`、状態確認は `/voice status`。
認識内容を確認してから送る設定は `/voice config --confirmation true` と `/voice confirm <ID>` を使う。
認識の確認はツール実行の承認を兼ねない。VOICEの副作用Toolは従来の `/approval` による引数付き明示承認が必要。

## 確認済み範囲と制約

Phase 5の実マイク試験では全文認識→共通Gateway→実Agent応答→完了履歴保存を確認した。
Phase 6の配布JAR・DRY (VT-4)の20秒試験では、読み上げ完了、利用者の可聴性確認、認識0件・障害0件、終了時OFF・ネイティブワーカー0、プロセス終了を確認した。
録音ファイルを保存せず、Phase 6診断はAgentへ送信していない。

- GPU：固定JNIはCPUExecutionProviderのみ。CUDA指定を試してCPUフォールバックを実測した。GPU対応配布物・依存物・固定SHA・回帰検証を追加していない。
- 呼びかけ：認識後のプレフィックス条件。低消費電力の専用日本語KWSではない。
- 発話者識別：検証済みモデル・登録音声・誤判定評価を追加しておらず未実装。
- 自己音声抑止：半二重ゲート。物理AECではなく、TTS中の音声割り込みは受け付けない。他の音響経路の評価は未実施。
- 割り込み：発話末尾検出とASRを待つ。「実行を停止」は即時の緊急停止ではない。
- CPU遅延：投入p95 3秒の目標は未達。公開4件の実Gateway再生p95は9.965秒。FP32公開20録音のCERは41/917文字（4.47%）で、コマンド文字列の誤りも残る。

これらを実装済み・達成済みへ読み替えない。各Phaseの必須回帰とCI、main統合の証拠を確認して完了を判定する。
