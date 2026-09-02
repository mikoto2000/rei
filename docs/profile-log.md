# Profile Log Specification

## Overview

プロファイルログは、Rei の Agent Event を後から集計・グラフ化するための追記専用ログです。
アプリケーション起動中に発行された Agent Event は、起動ディレクトリ配下の `.rei/profile.log` に JSON Lines 形式で保存されます。

このログは実行時の観測と性能分析を目的とします。会話履歴の永続化は `.rei/conversation-logs/` が担当し、プロファイルログには本文系 payload の全文を保存しません。

## File

- 保存先: `.rei/profile.log`
- 形式: UTF-8 の JSON Lines
- 書き込み方式: 1 イベント 1 行の append-only
- 作成タイミング: 最初の Agent Event 書き込み時
- 読み込み時の扱い: 空行は無視し、不正な JSON 行は警告ログを出してスキップします

## Event Entry Schema

各行は `ProfileEventLogEntry` として保存されます。

```json
{
  "id": "event-id",
  "sequence": 1,
  "timestamp": "2026-08-16T16:30:20Z",
  "type": "agent.run.completed",
  "version": 1,
  "sessionId": "session-id",
  "turnId": "turn-id",
  "runId": "run-id",
  "correlationId": "correlation-id",
  "parentEventId": "parent-event-id",
  "payloadType": "AgentRunCompletedPayload",
  "payload": {
    "runId": "run-id",
    "duration": 123,
    "completionTokens": 10,
    "timeToFirstTokenMillis": 20.0,
    "outputTokensPerSecond": 4.0,
    "endToEndTokensPerSecond": 3.0
  }
}
```

### Fields

| Field | Type | Description |
| --- | --- | --- |
| `id` | string | Agent Event の一意 ID |
| `sequence` | number | EventBus が付与する単調増加番号 |
| `timestamp` | string | イベント発生日時。ISO-8601 の Instant |
| `type` | string | `AgentEventType.value()` の文字列表現 |
| `version` | number | Agent Event schema version |
| `sessionId` | string or null | 会話・セッション単位の ID |
| `turnId` | string or null | ユーザー入力 1 回に対応する ID |
| `runId` | string or null | Agent Loop 実行単位の ID |
| `correlationId` | string or null | 関連イベントを結ぶ ID |
| `parentEventId` | string or null | 親イベント ID |
| `payloadType` | string or null | payload record の Java simple class name |
| `payload` | object | payload を JSON object 化した値 |

## Payload Redaction

プロファイルログでは、本文やツール入出力の全文を保存せず、文字数だけを保存します。

| Original Field | Stored Field |
| --- | --- |
| `delta` | `deltaLength` |
| `text` | `textLength` |
| `argumentsSummary` | `argumentsSummaryLength` |
| `resultSummary` | `resultSummaryLength` |

上記以外の payload field は、Jackson による object 変換結果をそのまま保存します。

## Duration Aggregation

`/profile summary` の duration 集計は、payload に次の field があるイベントだけを対象にします。

- `duration`
- `durationMs`

集計値は event type ごとに `count`, `avg`, `min`, `max`, `total` をミリ秒単位で表示します。

## Commands

`/profile` は `/profile summary` と同じ出力です。

| Command | Description |
| --- | --- |
| `/profile path` | プロファイルログのパスを表示します |
| `/profile summary` | event type ごとの件数と duration 集計を表示します |
| `/profile chart` | 時間バケットごとのイベント件数を ASCII 棒グラフで表示します |
| `/profile mermaid` | event type ごとの件数を Mermaid `xychart-beta` で出力します |

### `/profile chart` Options

| Option | Default | Description |
| --- | --- | --- |
| `--bucket-seconds` | `60` | 集計間隔の秒数。1 未満は 1 に丸めます |
| `--width` | `40` | 棒グラフの最大幅。1 未満は 1 に丸めます |

### `/profile mermaid` Options

| Option | Default | Description |
| --- | --- | --- |
| `--limit` | `12` | 表示する event type 数。件数の多い順に出力します |

## Graphing Workflow

1. Rei を通常通り起動します。
2. Agent Event が発行されると `.rei/profile.log` に追記されます。
3. `/profile summary` で event type と duration を確認します。
4. `/profile chart --bucket-seconds 30` で時系列のイベント量を確認します。
5. `/profile mermaid` の出力を Mermaid 対応 Markdown へ貼り付けると棒グラフとして表示できます。

## Compatibility Notes

- JSONL の各行は独立したイベントとして扱います。
- 将来 payload schema が増えても、既存の reader は未知 field を payload map 内の値として扱います。
- `type` は `AgentEventType.value()` の文字列を保存するため、Java enum 名ではなく wire value を外部連携に使います。
- `sequence` はプロセス内 EventBus ごとの単調増加番号です。複数プロセスや複数ログファイルを横断した一意性は保証しません。
