# 要件定義書: 機能別 LLM ルーティング

## はじめに

本機能は、LLM を利用する各機能に対して、既定の OpenAI 互換 API とは別の LLM サーバー・モデルを指定できるようにする。
負荷の高い機能や用途の異なる機能を別サーバーへ逃がし、通常チャットへの影響を抑えることを目的とする。

## 用語集

- **LLM 機能キー**: `chat`、`search`、`memory`、`bluesky-reply` など、LLM 呼び出し用途を識別するキー。
- **既定 LLM**: `spring.ai.openai` 配下の設定で構成される既存の ChatModel / ChatClient。
- **機能別 LLM 設定**: `rei.llm.features.<feature>` 配下に定義する `base-url`、`api-key`、`model`、`temperature`。

## 要件

### 要件 1: 機能別 LLM 接続先の指定

**ユーザーストーリー:** ユーザーとして、LLM を使う機能ごとに接続先サーバーを分けたい。そうすることで、重い機能や自動実行機能が通常チャットをブロックしないようにできる。

#### 受け入れ基準

1. THE system SHALL `rei.llm.features.<feature>.base-url` で機能別の OpenAI 互換 API ベース URL を指定できる。
2. THE system SHALL `rei.llm.features.<feature>.api-key` で機能別の API キーを指定できる。
3. THE system SHALL `rei.llm.features.<feature>.model` で機能別のモデル名を指定できる。
4. THE system SHALL `rei.llm.features.<feature>.temperature` で機能別の temperature を指定できる。

---

### 要件 2: 未指定時の後方互換

**ユーザーストーリー:** ユーザーとして、機能別設定を書かなくても従来どおり動いてほしい。そうすることで、既存設定のままアップデートできる。

#### 受け入れ基準

1. WHEN `base-url` が未指定または空文字のとき、THE system SHALL 既定 LLM を使用する。
2. WHEN `model` が未指定または空文字のとき、THE system SHALL 現在選択中または既定のモデルを使用する。
3. THE system SHALL 既存の `spring.ai.openai` 設定を変更なしで利用できる。

---

### 要件 2.1: 機能別 LLM 接続失敗時のフォールバック

**ユーザーストーリー:** ユーザーとして、機能別に指定した LLM サーバーが一時的に落ちていても、可能な限り処理を継続してほしい。そうすることで、単一機能向けサーバーの障害がアプリ全体の失敗にならないようにできる。

#### 受け入れ基準

1. WHEN 機能別 LLM の同期呼び出しが失敗したとき、THE system SHALL `spring.ai.openai` で構成された既定 LLM へフォールバックする。
2. WHEN 機能別 LLM のストリーミング呼び出しが失敗したとき、THE system SHALL `spring.ai.openai` で構成された既定 LLM へフォールバックする。
3. WHEN 機能別 `model` が指定されておりフォールバックが発生したとき、THE system SHALL 機能別モデル名を既定 LLM へ引き継がない。
4. THE system SHALL フォールバック発生をログに記録する。

---

### 要件 3: 対象機能

**ユーザーストーリー:** ユーザーとして、LLM を呼ぶ主要機能を個別に制御したい。そうすることで、用途ごとに軽量モデル・高性能モデル・別サーバーを使い分けられる。

#### 受け入れ基準

1. THE system SHALL `chat` 機能で機能別 LLM 設定を利用できる。
2. THE system SHALL `search` 機能で機能別 LLM 設定を利用できる。
3. THE system SHALL `memory` 機能で機能別 LLM 設定を利用できる。
4. THE system SHALL `bluesky-reply` 機能で機能別 LLM 設定を利用できる。
5. THE system SHALL `feed-summary` 機能で機能別 LLM 設定を利用できる。
6. THE system SHALL `briefing` 機能で機能別 LLM 設定を利用できる。
7. THE system SHALL `interest-discovery` 機能で機能別 LLM 設定を利用できる。
8. THE system SHALL `agent-skills` 機能で機能別 LLM 設定を利用できる。
