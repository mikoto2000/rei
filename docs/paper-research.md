# 論文リサーチ / Paper Library

論文を UUID を持つ永続オブジェクトとして保存し、検索、公開 PDF 取得、解析、日本語要約、翻訳を提供します。Long-term Memory への書き込みや Embedding は行いません。自然言語は既存 Tool Calling、スラッシュコマンドは既存 Session / Run 実行経路を使用します。

## 操作

```text
/paper search "GUI agent" --since 2025 --until 2026 --limit 10 --oa --sort citations
/paper show 3
/paper summarize 3 --quick
/paper summarize 3 --detailed --refresh
/paper translate 3 --technical
/paper translate 3 --section Method --natural --refresh
/paper library
/paper library search "GUI Agent"
/paper library show <uuid>
/paper library remove <uuid>
/paper library purge <uuid>
/paper import <paper-ref> "C:\papers\paper.pdf"
```

検索ソートは `relevance|newest|citations`。要約の標準モードは STANDARD、翻訳は TECHNICAL（`--literal|--natural|--technical`）。`--section` は `getPaperContent` の見出しと大文字小文字を無視して一致させます。同名見出しの全ページを処理します。論文番号は直近の検索または Library 一覧と対応し、Session 間で共有しません。再起動後は UUID を使用します。番号の一時テーブルは最大 1000 Session を保持します。

「2025年以降の GUI Agent 論文を10本探して」「3番を要約して」「前に読んだ ShowUI の論文を探して」などは通常の Tool Calling で処理します。モデルの Tool Calling 対応が必要です。コマンドの検索・一覧表示に LLM は不要です。

ユーザー PDF の import は既存 Paper に本文を関連付けるローカル Shell 専用操作です。Web Run からサーバー上の任意ファイルを import することはできません。取り込み先はタイトルや入力パスに依存せず内部 UUID で決定します。

## Architecture

```text
PaperCommand / PaperTools
  → PaperResearchService
    → PaperLibraryService → PaperRepository → SqlitePaperRepository
    → PaperSearchService → AcademicSearchProvider
    → PaperContentService → PaperContentProvider / PaperHttpClient
    → PaperExtractionService
    → PaperSummaryService → PaperLanguageModel / PaperSummaryValidator
    → PaperTranslationService → PaperLanguageModel
  → PaperArtifactStore
```

Provider 固有 JSON は Provider 内で Canonical `Paper` に変換します。LLM が外部 API を直接呼ぶ設計ではありません。検索用 `AcademicSearchProvider` と本文 URL 解決用 `PaperContentProvider`、HTTP 用 `PaperHttpClient`、生成用 `PaperLanguageModel` を別々に差し替えられます。

## 保存場所・Schema

アプリと同じ global data directory を使います。Windows の既定は `%LOCALAPPDATA%\Rei`、上書きは `REI_DATA_DIR` / `rei.data-dir`。Project 配下には保存しません。

```text
<rei-data-dir>/
  memory.db
  papers/
    originals/<uuid>.pdf
    extracted/<uuid>.json
    translations/<uuid>.<version-uuid>
```

既存 DataSource の `memory.db` に起動時の冪等 DDL で以下を追加します。既存テーブルの変更・別 DB・PDF BLOB はありません。

| テーブル | 内容 |
| --- | --- |
| papers | Canonical metadata JSON、DOI / arXiv の一意索引、タイトル指紋、検索文字列、登録・削除状態 |
| paper_aliases | DOI / arXiv が後から結び付いた場合の旧 UUID → 正規 UUID |
| paper_artifacts | 要約 JSON（Evidence を内包）、翻訳ファイルの version、cache key、生成時刻 |

著者・タグは metadata JSON と検索文字列に保持します。Summary に Evidence を内包するため、Claim は Summary のバージョンと不可分です。小さい検索・関連付け用データを SQLite、PDF・構造化本文・翻訳全文をファイルに分けています。既存の SQLite 初期化方式に合わせ、別 migration framework は追加していません。

ファイル書き込みは同じディレクトリの一時ファイルから atomic move します。原本・解析 JSON を再起動後も再利用し、破損した解析 JSON は原本から再解析します。

## ID・重複排除・検索

内部 ID はランダム UUID。外部 ID は Provider metadata です。DOI は URL prefix / 大文字小文字を正規化、arXiv は version suffix を除去して同一視します。DOI → arXiv → NFKC・小文字・空白正規化した title + first author + year の順で照合します。異なる DOI / arXiv が既知の場合、タイトルだけでは統合しません。欠けた metadata は既存値で補い、タグは集合として統合します。

Library 検索はパラメーター化 SQL の `instr` を用いた語ごとの AND 検索。title、authors、abstract、venue、DOI、year、tags が対象です。FTS5 / Vector Search への依存はありません。

`remove` は登録を非表示にし、保存データを残します。同じ論文を再検索すると再登録されます。`purge` は最初に DB へ「完全削除保留」を永続化し、ファイル削除後に metadata / Summary / Translation 索引をトランザクションで削除します。ファイルまたは DB の削除失敗時は非表示のまま同じ UUID で再試行可能です。OS と SQLite を跨ぐ原子的 rollback は行いません。保留中の論文は検索による自動再登録を拒否します。

## Search Provider

OpenAlex を主検索、障害時は Crossref に縮退し、警告に Provider とエラーコードを返します。`/paper show` と `getPaper(enrich=true)` は DOI を使って Crossref の書誌情報を補完します。

OpenAlex の OA 情報・PDF URL・citation count・abstract inverted index を正規化します。Crossref の著者・書誌・Abstract を読み、Crossref の license の存在だけで OA と推測することはしません。そのため Crossref fallback の `--oa` では結果が空になる場合があります。author / venue 条件は取得した候補に対する追加フィルターです。

API の公式仕様: [OpenAlex API](https://help.openalex.org/api/)、[Crossref REST API](https://www.crossref.org/documentation/retrieve-metadata/rest-api/)、[Crossref filters](https://www.crossref.org/documentation/retrieve-metadata/rest-api/rest-api-filters/)。

## 本文・Lifecycle・PDF 解析

本文は arXiv または OA metadata の PDF URL から取得します。paywall・認証の回避は実装しません。取得不能・解析不能時は警告を残し Abstract に縮退し、それもなければ METADATA_ONLY です。

`StructuredPaper` は FULL_TEXT / ABSTRACT_ONLY / METADATA_ONLY、ページ番号付きセクション、References、警告を保持します。PDFBox 3.0.5 は既存 Tika の依存に含まれる版を直接依存として明示しました。ページ範囲と抽出量を制御するため Tika の一括文書抽出ではなく PDFBox を直接使います。見出しのない PDF は UNKNOWN セクションです。長いセクションは分割します。

Lifecycle は直線的な enum ではなく、metadata と原本・解析結果・Summary / Translation の存在で表現します。Abstract だけの Summary から、後で本文付き Translation が作られるケースを許容します。

## Summary・Evidence

QUICK / STANDARD / DETAILED を同じ構造で生成します。研究課題、背景、貢献、手法、データ、評価、結果、制約、今後の課題を日本語で記述します。

既存 `SubAgentResultParser` / `SubAgentResultSchema` を再利用し、`subagents/schemas/paper-summary.schema.json` に厳密に検証します。不正 JSON、欠けた項目、過大な出力は保存しません。Evidence は section / page / 短い完全一致 source text を持ち、実際に LLM に渡した本文中の引用であることを確認します。引用の実在チェックは Claim の論理的正しさを保証するものではありません。

長い論文は入力上限内で各セクションから抜粋し、その旨を明示します。本文未取得時は「Abstract のみを元にしています」と表示します。Metadata しかない論文について内容を推測しません。

## Translation・Glossary・Cache

Section / Chunk 単位で日本語へ翻訳し、LITERAL / NATURAL / TECHNICAL を使い分けます。モデル名・Benchmark 名・数式を維持する指示を与え、初期の専門用語集と各 chunk で追加した用語集を次の chunk へ渡します。用語集は paperId / glossaryVersion ごとに DB に保存し、別セクション・別リクエスト・再起動後も引き継ぎます。既存 glossary の訳語を変えた出力、該当する訳語や代表的なモデル名が欠落した出力は失敗扱いです。訳文の意味や数式の完全な機械的保証、OCR はありません。

Summary は paperId / mode / model / promptVersion、Translation はさらに section / glossaryVersion を cache key に含めます。Abstract の hash と PDF import の revision も照合し、資料の更新後は再生成します。`--refresh` は別バージョンを追加します。翻訳は全 chunk 完了後にファイルと DB 索引を保存し、途中失敗を完成キャッシュとして返しません。残存する未参照ファイルも purge の対象です。

## Context・Tools・Events・Cancellation

本文全文を Chat History に残さず、取得 Tool は availability とセクション一覧を返します。必要部分は `getPaperSection` で最大 4000 文字ずつ読み、既存 Working Set / Context Compression の通常の Tool 処理経路を利用します。翻訳は短い preview と保存完了情報だけを返します。

Tools: `searchPapers`, `getPaper`, `searchPaperLibrary`, `getPaperContent`, `getPaperSection`, `summarizePaper`, `translatePaper`, `getPaperTranslationChunk`。

自然言語 Tool は既存 Tool Event decorator、コマンドは既存 `tool.started/completed/failed` と Run / message event を利用します。RunExecutionContext を明示的に受け渡し、HTTP 待機・PDF 抽出・チャンク反復・LLM stream・保存でキャンセルを確認します。HTTP future と LLM subscription は中止時にキャンセルされます。要約・翻訳用 LLM には会話メモリや実行 Tool を与えません。

## Security・リソース・観測

HTTP(S) のみ、userinfo 拒否、private / loopback / link-local / multicast / CGNAT アドレス拒否、接続時の DNS 解決結果検証、リダイレクト先の再検証を行います。自動 redirect は無効。レスポンスは Content-Type とサイズを検証しながら読み込みます。ファイルは UUID パスのみで symbolic link を拒否します。

timeout、有限 retry（429 / 5xx と通信障害）、最大5秒の backoff / Retry-After 待機、User-Agent を設定できます。本文・翻訳・Prompt 全文はログに残しません。検索件数・dedup、HTTP duration/size、PDF cache、ページ・抽出文字数、要約 cache/duration、翻訳 cache/chunk 数を記録します。

## Configuration

既存の Spring 設定方式に従います。以下は既定値の例です。

```yaml
rei:
  paper:
    enabled: true
    open-alex-api-key: ${REI_PAPER_OPEN_ALEX_API_KEY:}
    user-agent: Rei-PaperResearch/1.0
    default-limit: 10
    max-limit: 100
    max-library-search-results: 50
    timeout: 30s
    llm-timeout: 3m
    retries: 2
    backoff: 500ms
    max-pdf-bytes: 20000000
    max-response-bytes: 4000000
    max-pages: 200
    max-extracted-chars: 1000000
    max-sections: 500
    max-section-chars: 16000
    summary-input-limit: 16000
    translation-chunk-size: 4000
    default-summary-mode: STANDARD
    default-translation-mode: TECHNICAL
    prompt-version: "1"
    glossary-version: "1"
```

LLM 接続は既存 chat model 設定を再利用します。OpenAlex API key はアカウント側の利用条件・quota に応じて設定してください。

## 既知の制約・将来拡張

- PDF の段組み、数式・表、画像のみのページは正確に抽出できない場合があります。OCR はありません。
- heading は英語の代表的な見出しを検出します。未知の見出しやレイアウトは UNKNOWN / ページ単位になります。
- Summary の入力は上限内の抜粋です。全ページの全テキストを逐語的に評価したという意味ではありません。
- OpenAlex / Crossref の実サービスと課金される LLM の end-to-end テストは通常のテストに含めません。モックで Provider・生成障害を再現します。
- 同名・同年・同 first author で外部識別子がない論文は、指紋による誤統合の余地があります。
- 訳語指示、引用の実在、JSON 形状を検証しますが、翻訳や要約の学術的妥当性は人間による確認が必要です。
- 同一プロセスの取得・生成・削除は Paper 単位の lock で直列化します。複数プロセス間の distributed lock はありません。
- purge 失敗後の回復は明示的な再実行です。保留中の UUID はエラー時の操作対象を使用します。

将来は Canonical Paper と永続 UUID を基盤に、比較、Citation Graph、後続研究、Embedding / RAG、ResearchAgent、研究トレンドを追加できます。今回これらは実装していません。
