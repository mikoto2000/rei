# アーキテクチャ評価

## 構成

| 場所 | 責務 | 規模 |
| --- | --- | --- |
| ルート Maven | Spring Boot Backend、従来 Shell、Web API、ツール、永続化 | main 1713 / test 1314 Java |
| `terminal/` | `contract` / `completion` / `cli` / `launcher` の reactor | 54 ファイル |
| `client/` | Tauri 2 + Rust + React + TypeScript | 243 ファイル（`.rs` 61、`.tsx` 39、`.ts` 33） |
| `docs/` | 設計・計測記録 | 434 `.md` |
| `.kiro/` | 仕様・実装補足 | 82 ファイル |

Backend のトップレベルパッケージは 40 以上。大きい順に `core` 277、`activity` 116、`event` 103、`web` 91、`topic` 72、`application` 57、`voice` 50、`memory` 47、`subagent` 45、`paper` 43、`ui` 42。

## 依存ゲートの設計

`PackageArchitectureTest` は ArchUnit で**本番 bytecode のみ**を import し、`target/architecture/*.txt` へ辺を書き出したうえで `config/architecture/*.txt` と比較する。

| ID | 監視対象 | 現在の基準行数 |
| --- | --- | ---: |
| A | application → ui/web（処理本体） | 0 |
| B | run/session/chat/stagnation/execution/checkpoint/process → ui/web | 0 |
| C-business | 機能境界間の循環に参加する処理本体の辺 | 2851 |
| C-wiring | 配線を含む循環の辺 | 793 |
| D | Configuration を依存元とする機能間の辺 | 390 |
| E | core 外 → core への辺 | 1052 |

`ArchitectureBaseline.check` の挙動は明快。

- 基準にない辺が1本でもあれば失敗（`NEW dependencies`）。
- 基準にあって実在しない辺があれば失敗（`resolved debt`）。明示 shrink でのみ書き換え。
- `CI=true` では shrink 自体を拒否。
- 全ルールの追加判定を先に集め、1件でも追加があれば shrink を一切書かない。

**評価**: 「新規の依存悪化を確実に止める」という目的に対して、設計は妥当。
特に次の点が良い。

- 予算ファイルが無ければ失敗する（暗黙の初期作成を許さない）。
- `target/architecture/*.txt` の無条件コピー運用を文書で禁止している。
- `ArchitectureGuardTest` が「実際に違反を注入してゲートが落ちること」を TempDir 上で再現する。ゲート自体の有効性を検証している。

## 注意点

### ゲートは「凍結」であって「健全性の証明」ではない

C-business 2851 辺、E 1052 辺という基準は、既存の循環と core 集中を**そのまま許容**している。
ゲートが緑であることは「これ以上悪化していない」を意味するだけで、「依存方向が正しい」は意味しない。

### 文書との不整合（F-04）

`docs/architecture/README.md` は B 違反が1件残っていると書くが、`B-execution-ui.txt` は 0 行。
`BackgroundCommands` は `CompletedRunNarrator`（`core.execution` 内のポート）に依存しており、UI 依存は解消済み。
文書の「Phase 4 で port と UI 側購読に分ける候補」は既に実施されている。

### 分析対象 SHA の固定

文書は `bb4f49247370ed3e6cd9cc3ee06209aa060e5075` を分析対象と記すが、現在の HEAD は `b4b0c257`。
`--source-commit HEAD` で再計測する手順は用意されているが、文書は更新されていない。

## 構造上の観察

- `core` が 277 ファイルで最大。`core.chat`、`core.project`、`core.execution`、`core.policy`、`core.sqlitevec` など責務が広い。
- `application` は 57 ファイルで、`run` / `session` / `input` / `read` / `project` / `state` / `task` に分かれる。
- `web` は 91 ファイルで、コントローラが 80 弱。1 コントローラ 1 機能の粒度で揃っている。
- `event` が 103 ファイル。イベント型ごとにクラスを分ける方針。

`core` への集中（E 1052 辺）は、機能追加のたびに `core` が太る構造を示す。
Phase 1〜5 の移行計画が `docs/architecture/migration-roadmap.md` にあるので、次に触るならそこが起点になる。
