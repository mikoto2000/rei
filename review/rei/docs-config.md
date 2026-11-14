# 設定とドキュメントの整合

対象は `src/main/resources/application.yaml`、`README.md`、`DEVELOP.md`、`docs/configuration.md`、`docs/architecture/README.md`、`pom.xml`。
すべて静的読解で、起動・実通信はしていない（[verification.md](verification.md)）。

## 設定の優先順位

README と `docs/configuration.md` は「環境変数 → `<rei-data-dir>/application.yaml` → 組み込み `application.yaml`」と説明している。

実装は `ExternalConfigSupport.defaultProperties()` が `spring.config.additional-location` に `ReiDataDirectory.current()` 配下の `application.yaml` を設定し、Spring Boot 標準の順序に乗る。
**説明と実装は一致している。** ここは問題ない。

## 配布物として問題になる既定値

### F-01 の再確認

```yaml
spring:
  ai:
    openai:
      # base-url: ${REI_OPENAI_BASE_URL:https://api.openai.com}
      base-url: ${REI_OPENAI_BASE_URL:http://192.168.1.50:11434}
      api-key: ${REI_OPENAI_API_KEY:dummy-key}
      chat:
        options:
          # model: ${REI_OPENAI_CHAT_MODEL:gpt-5.4}
          model: ${REI_OPENAI_CHAT_MODEL:qwen3.5:122b}
      embedding:
        options:
          # model: ${REI_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
          model: ${REI_OPENAI_EMBEDDING_MODEL:qwen3-embedding:8b}
```

コメントアウトされた行に本来の既定値（`https://api.openai.com`、`gpt-5.4`、`text-embedding-3-small`）が残っている。
つまり**意図的にローカル検証値へ差し替えたまま commit された**と読み取れる。
`docs/configuration.md` の設定例も `qwen3.5:122b` を使っており、この値が文書側にも波及している。

### F-12 の再確認

`application.yaml` 末尾で `dev.mikoto2000.rei.bluesky: DEBUG` だけが既定 DEBUG。
直前のコメントは「SDK debug/error messages may contain Socket URLs or raw payloads」と、生ペイロード回避を理由に Slack / WebSocket を `OFF` にしていると説明している。
Bluesky だけその方針から外れている。

## F-16 `rei.data-dir` の既定値が二重定義で食い違う

同じ「データ保存先」を2箇所が別々に決めている。

| 決定箇所 | Windows | macOS | Linux |
| --- | --- | --- | --- |
| `ReiDataDirectory.resolve` | `%LOCALAPPDATA%\Rei` | `~/Library/Application Support/Rei` | `$XDG_DATA_HOME/rei` または `~/.local/share/rei` |
| `application.yaml` の `rei.data-dir` | `${LOCALAPPDATA}/Rei` | `~/.local/share/Rei` | `~/.local/share/Rei` |

`application.yaml` 側は OS 分岐を持たない単一の式で、macOS の `Library/Application Support` を再現できず、Linux では大文字始まりの `Rei` になる。

通常起動では `ReiApplication.main` が `System.setProperty("rei.data-dir", ReiDataDirectory.current().toString())` を実行するため、System プロパティが優先されて yaml の既定値は使われない。
**したがって実害は限定的**だが、次の2点は残る。

1. `main` を経由しない起動（テスト用 `ApplicationContext`、将来の別エントリポイント）では yaml の値が有効になり、OS ごとに誤ったパスへ書く。
2. 同じ概念の既定値が2箇所にあるため、片方だけ直す変更が起きやすい。

**推奨**: yaml 側は `rei.data-dir: ${REI_DATA_DIR:}` のように空を既定にし、解決を `ReiDataDirectory` に一本化する。

## ドキュメント整合の確認結果

| 主張 | 出所 | 実装 | 判定 |
| --- | --- | --- | --- |
| 保存先は Windows `%LOCALAPPDATA%\Rei`、Linux `$XDG_DATA_HOME/rei`、macOS `~/Library/Application Support/Rei` | README | `ReiDataDirectory.resolve` | 一致 |
| JDK 25 以上 | README | `pom.xml` の `java.version` 25 | 一致 |
| 設定は環境変数 → 外部 yaml → 組み込み yaml | README / configuration.md | `ExternalConfigSupport` | 一致 |
| B 違反が1件、SpotBugs 既存1,907件を保持 | docs/architecture/README.md | `B-execution-ui.txt` は0行、ゲートは合計0件を要求 | 不一致（F-04） |
| 予算 C-business 2837 / C-wiring 799 / D 372 / E 1034 | docs/architecture/README.md | 実ファイルは 2851 / 793 / 390 / 1052 | 不一致（F-04） |

`docs/architecture/README.md` の不一致は、文書が Phase 4 完了前の計測を説明したまま更新されていないことを示す。
数値が4つとも動いているので、単なる記載漏れではなく**再計測されていない**と判断できる。

## 良い点

- 秘密情報はすべて `${REI_...}` の環境変数参照で、ハードコードされたキーは見つからなかった（`dummy-key` を除く）。
- `server.address` の既定は `127.0.0.1`。外部公開しない前提が既定になっている。
- `management.endpoints.web.exposure.include: health` のみ、`show-details: never`。
- 危険側の機能（secretary、MCP サーバー、GitHub Webhook、Artifacts、Computer Use、外部エージェント実装）は既定で無効。
- `rei.llm.output-limit` や `rei.context-compression` など、暴走を抑える上限が既定で入っている。
