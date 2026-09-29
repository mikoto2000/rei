# Paper Research 実装報告

利用方法と設計の詳細は [paper-research.md](paper-research.md) を参照してください。

| 項目 | 実装内容 |
| --- | --- |
| 1. Branch | `feature/paper-research-library` |
| 2. Commit | 最終コミットの hash は実装完了の応答に記載。`git log -1 --format=%H` でも確認可能 |
| 3. 範囲 | Foundation / Phase 1 / Phase 2 / Phase 3 |
| 4. 主要クラス | `Paper`, `StructuredPaper`, `PaperSummary`, `PaperTranslation`, `PaperResearchService`, `PaperLibraryService`, `SqlitePaperRepository`, `PaperArtifactStore`, `PaperSearchService`, `PaperContentService`, `PaperExtractionService`, `PaperSummaryService`, `PaperTranslationService`, `PaperTools`, `PaperCommandExecutor` |
| 5. Architecture | Canonical model を中心に Repository / Artifact / HTTP / Search Provider / Content Provider / LLM を分離 |
| 6. 保存構成 | 既存 global data directory の `memory.db` と `papers/`。Long-term Memory への自動書き込みなし |
| 7. DB migration | 冪等 DDL。`papers`, `paper_aliases`, `paper_artifacts` と識別子・検索・バージョン用 index。既存 table の破壊的変更なし |
| 8. Artifact | `originals/<uuid>.pdf`, `extracted/<uuid>.json`, `translations/<uuid>.<version-uuid>`（JSON） |
| 9. ID / dedup | UUID、正規化 DOI、version を除いた arXiv、NFKC title + first author + year。旧 UUID の alias を維持 |
| 10. Library Search | title / authors / abstract / venue / DOI / year / tags。SQL パラメーターと件数上限 |
| 11. Search Provider | OpenAlex 検索、Crossref fallback と DOI metadata 補完。縮退理由を返却 |
| 12. PDF 取得 | arXiv / OA metadata URL / ユーザー PDF import。原本再利用 |
| 13. PDF 解析 | 既存 Tika に含まれる PDFBox 3.0.5 を direct dependency として明示。ページ・文字数・セクション上限 |
| 14. StructuredPaper | availability、見出し、本文、ページ、References、警告。JSON 永続化 |
| 15. Summary | QUICK / STANDARD / DETAILED。入力が抜粋・Abstract の場合は明示 |
| 16. Schema | 既存 SubAgentResultParser / SubAgentResultSchema と Draft 2020-12 schema を再利用 |
| 17. Evidence | Claim に section / page / 最大500文字の原文を関連付け、入力原文への完全一致を検証。Summary と同時保存 |
| 18. Translation | Section / Chunk ごとに LITERAL / NATURAL / TECHNICAL。失敗・中止結果を完成キャッシュにしない |
| 19. Glossary | 初期用語集と chunk 間の追加用語を共有。既存訳語変更・本文での訳語欠落・代表的な略語やモデル名の欠落を拒否 |
| 20. Cache | mode / model / promptVersion / section / glossaryVersion、Abstract hash と import revision。refresh でも旧 version を保持 |
| 21. Context | getPaperContent は索引のみ。getPaperSection / getPaperTranslationChunk は4000文字のページング。会話に全文を渡さない |
| 22. Cancellation | RunExecutionContext を HTTP・PDF・LLM・chunk・保存へ伝播。Future / stream を中止。待機 lock も中止可能 |
| 23. Security | 接続先 IP 検証・redirect 再検証・HTTP(S)限定・Content-Type・UUID path・symlink拒否・原文内の指示を実行しない生成経路 |
| 24. Resource Limit | maxPdfBytes / maxPages / maxExtractedChars / maxSections / maxSectionChars / translationChunkSize / summaryInputLimit / maxLibrarySearchResults、HTTP / LLM timeout |
| 25. Commands | search / show / summarize / translate / library list・search・show・remove・purge、追加の import |
| 26. Tools | searchPapers / getPaper / searchPaperLibrary / getPaperContent / getPaperSection / summarizePaper / translatePaper / getPaperTranslationChunk |
| 27. Configuration | `rei.paper.*`、`REI_PAPER_ENABLED`、`REI_PAPER_OPEN_ALEX_API_KEY`。保存先は Rei の data directory |
| 28. 新規テスト | 77 件（parameterized test の各ケースを含む）、7 test classes |
| 29. 結果 | 全体 2,584 件成功（失敗0・エラー0・skip0）。用語集のリクエスト間再利用追加後は Paper 関連77件を再検証し、全件成功 |
| 30. 既知の制約 | OCR・表/数式の完全再現なし。長い要約は抜粋。author/venue は候補の追加フィルター。実 Provider / LLM の E2E は未実施。複数プロセスの排他は対象外 |
| 31. 次 Phase | Citation Graph、複数論文比較、後続研究、Embedding / RAG、ResearchAgent |
| 32. git status | コミット後の clean を最終応答で確認 |

## 検証手順

JDK は既存 `C:\Java\jdk-25`、Maven は同梱 wrapper、依存は既存 `.m2/repository` を利用しました。最初に Library、次に Provider、その後 PDF / processing の未実装テストを実行して Red を確認し、実装後 Green を確認しました。最終的に標準書式への整形と責務の分割、障害・キャンセル・削除・用語検証の回帰テストを追加しています。

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-25'
$env:REI_DATA_DIR = 'F:\project\rei\target\paper-test-data'
$env:REI_LOG_FILE = 'F:\project\rei\target\paper-test-data\rei.log'
.\mvnw.cmd -o '-Dmaven.repo.local=F:\project\rei\.m2\repository' test -q
```

通常の実データへテストを書き込まないよう、保存先とログを target に隔離しました。既存 sqlite-vec テストは自分の一時ディレクトリへ release binary を取得するため、テスト実行時のネットワークを許可しています。新規 Paper テストの外部検索・生成にはスタブを使用し、有料 LLM 呼び出しを行いません。

初回の全体実行には同じ target へのコンパイルを重ねたことによる一時的な class 不在、既定 data directory の権限制限、ダウンロード制限がありました。後続実行は直列化し、保存先を隔離して再検証しました。新コマンド追加による picocli の引数なし構築と未知オプションの例外変換も回帰テストで修正しました。
