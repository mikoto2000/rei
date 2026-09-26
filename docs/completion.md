# Shell の文脈依存補完（Phase 1–3）

## 調査と設計

Shell は JLine 3、Slash command の登録・引数定義は picocli `CommandSpec` を使う。
Enter 後の入力は `UserInputParser` → `UserInputService` →既存の実行経路へ渡る。
`UserInputParser` は single/double quote を解釈し、バックスラッシュは文字として保持する。
従来の JLine 既定 parser はバックスラッシュを escape と解釈し、Windows パスと不整合だった。
さらに単語途中の引用符の規則も異なることを追加テストで確認した。
既存 tokenizer に raw token 範囲を持たせて共通化し、JLine の `Parser` / `CompletingParsedLine` に接続した。
空の引用符引数を実行時に省く既存仕様も維持する。挿入用の quote 生成も同じ parser に置き、
ファイル名中の single/double quote は交互の引用符区間で表現する（backslash escape は使わない）。

以前は `/project add` の専用分岐と `PicocliJLineCompleter` があった。
現在はコマンドモデルを読み取るだけの resolver に統一した。picocli の実行・再構築は行わず、
`@Spec` や出力 writer の再束縛も起こさない。旧 `ProjectAddDirectoryCompletion` は互換 facade として残し、
ファイル列挙を新しい Provider に委譲する。

Project は `ProjectRegistry` / `ProjectService` の登録パスを使用する。`cd` が受け付ける識別子はパスであり、
Project 名や UUID を挿入しない。Session は `SessionRepository` の永続 metadata を用い、
runtime 用 `SessionRegistry` や会話本文を検索しない。Agent registry は現状存在せず、
対応 Agent / action は `ExternalAgentRequest.Agent` / `Action` の enum が定義する。
補完と `ExternalAgentCommandRequest` の検証はこの定義を共有する。

## 処理フローと依存方向

```text
JLine Tab
  → ShellCompletionParser → CompletingParsedLine（quote / cursor / raw token 範囲）
  → CommandCompletionContextResolver（picocli metadata を読む）
  → CompletionContext
  → CompletionEngine（Provider registry）
  → CompletionProvider → CompletionCandidate
  → JLineCompletionAdapter → JLine Candidate / 候補一覧 / 挿入
```

`core.completion` は JLine、picocli、Spring に依存しない。
Context は全文、カーソル位置、token 一覧、編集中の word index、選択コマンド内の引数 index、
カーソルまでの prefix、コマンド階層、補完種別、定義由来候補、作業ディレクトリを持つ。
Candidate は挿入値・表示値・説明・kind・空白追加可否を持つ。
JLine / picocli の変換は `ui.shell` に置く。既存の Shell 生成入口 `ReiLineReaderFactory` は維持した。

Shell に Spring の `CompletionEngine` を注入する。外部 Shell から戻った際も同じ engine を使用する。
Agent Event 表示、バックグラウンド実行、キャンセル、Intervention の経路は変更しない。

## コマンド定義との連携

picocli の `@Parameters` / `@Option(completionCandidates=...)` が補完情報の置き場所。
`CompletionMetadata` は `Iterable<String>` を拡張し、文脈に応じた `types` と `choices` を返す。
従来の単純な `Iterable<String>` 候補も使用可能。Java `Path` / `File` 型もパスとして認識する。
コマンドとサブコマンドは毎回 live `CommandSpec` から取得し、追加登録や削除を即座に反映する。
Shell built-in は `UserInputService.builtins()` を実行と補完の共通定義にする。

| 入力位置 | 候補元 / 制約 |
| --- | --- |
| `/pro`、`/history ` など | CommandSpec のコマンド・サブコマンド |
| `/project add ` | directory |
| `/project cd ` | 登録済み Project のパス + directory |
| `/project remove ` | 登録済み Project のパス |
| `/agent ` | Agent enum |
| `/agent <agent> ` | action enum |
| `/agent <agent> <action> ` | 有効な組み合わせなら file-or-directory |
| `/history show ` | Session ID（title は説明） |
| `/session switch ` / `/session resume ` | 現在の Project に属する Session ID |
| `/image generate --output ` | file-or-directory |
| 通常入力中の `./`、`../`、絶対パスなど | 明示的なパス prefix のときだけ汎用補完 |

`/summarize` は現状 URL 専用であり、ファイル要約コマンドには変更していない。
明示的パスの入力支援と、各コマンドが Enter 後に受け付ける値は別の責務である。

## Provider の追加と優先順位

`engine.register(provider)`、または Spring の `CompletionProvider` bean を追加する。
metadata の補完種別は拡張可能な文字列なので Tool / Plugin 固有の種別を追加できる。

```java
public final class ToolProvider implements CompletionProvider {
  public boolean supports(CompletionContext context) {
    return context.types().contains("tool");
  }
  public List<CompletionCandidate> complete(CompletionContext context) {
    // メモリ上の registry を prefix で絞り込む。Tool を実行しない。
    return candidates;
  }
}
```

1. `supports` が true の Provider のうち最大 priority を選ぶ。
2. 同じ priority は登録順に merge し、同じ挿入 value は先の候補を採用する。
3. 標準の引数型・選択肢 Provider は 100、明示パス fallback は 0。
4. コマンド専用拡張は例えば 200 を指定できる。
5. 選択済み Provider の結果が空でも下位へ fallback しない。Directory 指定でファイルが混入することを防ぐ。
6. `supports` / 候補生成の RuntimeException は Provider 単位で隔離する。

登録 Project はローカルディレクトリ候補より先に merge し、kind `project` と説明で区別する。
JLine 側の表示順・一覧・共通 prefix 挿入は JLine 標準機能に委ねる。
独自 Provider は prefix 絞込み、読み取り専用、短時間応答を守る。

## パス仕様

- `file` はディレクトリを除外、`directory` はファイルを除外、`file-or-directory` は両方。
- 作業ディレクトリは現在の Shell Project。相対表記と separator は保持する。
- ディレクトリ末尾に separator を付け、空白は追加しない。ファイル確定時は空白を追加する。
- 一回の補完で親ディレクトリの直下のみ列挙する。ファイル本文の読取り・書込み・再帰探索はしない。
- 存在しない親、読み取り失敗、不正な Path は候補なし。空ディレクトリも正常。
- Windows は `/` と `\`、drive letter、UNC を保持し、大文字小文字を区別せず prefix match。
- Linux は `/`、大文字小文字を区別する。`\` はファイル名中の通常文字。
- Windows drive-relative `C:foo` は drive ごとのプロセス状態に依存するため候補なし。`C:\foo` は対応。
- `~/`（Windows では `~\` も）は home を展開して絶対パスを挿入する。
  JLine の候補照合にも展開した prefix を渡すが、置換範囲は元の token のまま保つ。
  補完を使わず入力した `~` の実行時解釈は変更しない。
- 行頭の `/prefix` は Slash command 名前空間。Unix の先頭パスは `/tmp/` のように次の `/` まで入力すると補完できる。

## 軽量化と失敗時の挙動

Project / Session のファイルは生成時に候補 snapshot を温め、通常の読込みと保存成功時に更新する。
Tab は immutable なメモリ snapshot のみ参照する。Session 保存後の enqueue 失敗時は snapshot も rollback する。
起動時の候補読み込み失敗だけは空候補にできるが、通常の repository 操作は従来通りエラーを報告する。
別プロセスによるファイル変更は通常の repository 読込みまで候補へ反映されない。
新しい Project の登録、副作用のある `currentContext()`、LLM、ネットワーク、Tool は補完から呼ばない。

## 検証と今後

TDD の Red → Green を core、Shell 接続、Session snapshot、home 挿入、引用符整合の各段階で確認。
ログは `target/completion-red-*.log`、`target/completion-green-*.log`。
temp directory と注入可能な directory lister により、読み取り失敗や Windows / Unix の lexical 処理を
ホスト OS に依存せずテストする。JLine の `runMacro` → `readLine` を使う Tab 入力テストも含む。

追加テストは41件。

| クラス | 件数 | 主な検証 |
| --- | ---: | --- |
| CompletionEngineTest | 3 | supports、priority、merge、dedup、例外隔離 |
| FilePathCompletionProviderTest | 12 | 型制約、relative / absolute / home、空・不存在・I/O 失敗、OS ごとの prefix と separator |
| ContextCompletionTest | 15 | live registry、引数 metadata、Project / Agent、引用符、cursor、独自 Provider |
| JLineCompletionInputTest | 6 | 実際の Tab 入力、空白付与、quote、階層継続、行途中、home 挿入 |
| SessionCompletionTest | 5 | 永続台帳からの snapshot、I/O 不使用、rollback、Project 限定、Spring / picocli 接続 |

実端末での見た目・Linux ネイティブ実行は別途手動検証対象。
正式 Plugin API、remote path、`~user`、環境変数展開、drive-relative path は今回の対象外。
汎用 option resolver は `--name value` と定義された必須 arity に対応する。
`--name=value` の値部分の補完や任意長 option value の高度な推測は将来拡張とする。

## 最終検証記録

2026-09-22、Windows / Java 25、ブランチ `codex/context-completion`。
全 **1,949件、Failures 0、Errors 0、Skipped 0**（追加41件を含む）。
ログ: `target/completion-full-verified.log`。`git diff --check` も成功。

```powershell
.\mvnw.cmd -s target/completion-maven-settings.xml -o `
  '-Dmaven.repo.local=F:\project\rei\.m2\repository' `
  '-Drei.data-dir=F:\project\rei\target\completion-full-test-data' test
```

初回 sandbox 内実行では既存 vector 関連27件が sqlite-vec のネットワーク取得制限でエラーになった。
取得可能な権限で再実行し、全件成功を確認した。権限切替先の Maven mirror 設定と既存キャッシュの差は、
`target/completion-maven-settings.xml` の空 settings を明示して解消した。ユーザーの Maven 設定・POM は変更していない。
検証用 data-dir も target 内に分離している。
