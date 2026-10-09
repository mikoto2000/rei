# Phase 5 測定データ

公開 FLEURS とローカル合成音声だけを使用。マイク音声・モデル本体は含まない。
`fixtures.json` に参照文・録音行番号・変換後 SHA-256・出典を保持する。TSV の reference64 / hypothesis64 / pipelineHypothesis64 は UTF-8 Base64。
`main` は tiny/base-int8/small-int8/turbo の CPU 1 thread / tail 1000 / silence 1200 比較。録音名衝突の修正後、19既存録音と1追加録音の結果を対応づけた。process.json の rawProcesses はその実プロセス記録であり、再構成30件の専用プロセス測定ではない。
精度・速度・試験範囲の解釈は ../voice-input-phase5.md を参照。


`main` / `calibration` / `tuned` / `commands` はUTF-8 byte欠落対策前の履歴。`utf8-*` は同じ固定モデルとbyte保持対策による新規実行で、TSVの `byteSafe=true` でも区別する。修正前のCERにはランタイムの文字欠落が含まれ、修正後の精度として引用しない。新しいprocess.jsonはそれぞれ専用の所有プロセスを100ms周期で測定した記録。
