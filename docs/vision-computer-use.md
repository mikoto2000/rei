# Vision-first Computer Use v1

## 調査結果と置換方針

調査対象は main の `7fee4e1`。src、config、docs、README を Computer Use /
UI Automation / Accessibility / Robot / desktop の語とファイル名で検索した。
この checkout には旧 Computer Use 試作、その専用テスト、UIA 依存は存在しなかった。
したがって旧コードの削除はない。Google OAuth の `Desktop.browse` は別機能として維持した。

既存の構造と採用方針:

- Tool taxonomy: 明示的な分類 enum はなく、`*Tools` の `@Tool` を
  `AiConfiguration` と feature 別の `LlmChatClientProvider` が ChatClient に登録。
  通常チャットは後者を使用するため、Computer Use は両経路へ登録する。
  feature 別では CHAT のみに公開する。検索には workflow と説明する既存 Tool がある。
  同じ方式で `computerUse(goal)` を Preferred workflow として一つだけ公開する。
- Tool 実行: Agent の通常 Tool loop → callback → service。
  外側の tool.started / tool.completed / tool.failed は既存 `ToolEventCallbackDecorator` を利用。
- Event: `AgentEventFactory`、sealed payload、publisher、Shell renderer を利用。
  Computer Use 固有の metadata のみ `computer_use.progress` で追加する。
- Cancel: `CommandCancellationService` の run 状態と child registration を再利用。
  `AgentRunScope` を callback で復元し、ツールを実行する worker の interrupt を登録・解除する。
- AI: `LlmModelProvider` の OpenAI-compatible 接続、feature 別設定、fallback を利用。
  Spring AI の UserMessage / Media に画像入力の基盤がある。
- Structured Output: 既存に ComputerAction 用の schema / validation はないため新設。
- Prompt: 既存の classpath resource に prompt を置く規約を利用し、通常 Agent prompt とは分離。
- OS: Computer Use の Robot 呼び出しを初めて追加。Windows UIA を導入しない。

## 主経路

```text
Agent → computerUse(goal)
          └─ ComputerUseService
                ScreenCapture.captureScreen()
                  → ComputerObservation
                  → ComputerVisionModel.decide()
                  → Java validation / SafetyPolicy
                  → ComputerInput.execute()
                  → UiStabilizer.awaitAfter()
                  → 次の screenshot / decision
```

screenshot が一次情報。1 回の観測に対して最大 1 action のみ dispatch する。
入力イベント送信は goal 達成ではない。次の新しい screenshot を見たモデルが
DONE を返したときだけ成功。UIA 要素の有無による verification は存在しない。

内部モデルは `SpringAiComputerVisionModel`。ChatClient、通常 prompt、memory、
advisors、ToolCallingManager を使用しない。内部 Tool 実行は false、callback / tool names は空。
HTTP JSON の tools / tool_choice は省略する。空の tools 配列を拒否する互換 API があるため
（[vLLM の validation](https://github.com/vllm-project/vllm/blob/main/vllm/entrypoints/openai/chat_completion/protocol.py)）。
provider の default / fallback に raw tools / callbacks / tool names が
ある場合は拒否し、モデルが tool call を返しても dispatch せず不正出力として扱う。

JSON schema は response_format と専用 system message の双方に含める。
返答検証に失敗した場合、bounded repair にローカルの検証理由を渡す。
修正後も不正なら Tool 結果と Shell の finished MODEL_ERROR にその理由を表示する。
診断に返答本文、入力文字列、JSON parser の生の例外メッセージは含めない。
受信成功後の MODEL_ERROR はモデルの不調とは限らず、返答形式や action の検証失敗も含む。
既定の repairs=1 では、1 回の観測で最大 2 回の推論要求になる。
reason は全 action で任意の説明として許可する（非 null なら非空文字列・最大300文字）。
操作 action の説明は実行内容に反映せず、DONE / FAILED / UNCERTAIN では従来どおり必須。
他 action の入力フィールド混入は引き続き拒否する。
返答テキストなし、30,000文字超過、finish_reason=length を区別して表示する。
出力上限到達は同一要求で repair せず停止する。過去の Invalid response size だけでは
null の返答とサイズ超過のどちらだったかは分からない。

Observation は goal、現在の PNG、直近 action summary、step / maxSteps を持つ。
履歴はデフォルト直近 5 件。入力文字列の全文や過去画像は履歴に追加しない。
画像は推論リクエストだけに含め、イベントやディスクへ保存しない。

## 有効化・設定

### Shell と画面操作の使い分け

有効な通常 chat と標準 ChatClient に computer-use/orchestration.md を追加する。
通常エージェントは URL / ファイルを開く、アプリを探して起動するなどの準備に既存の
runCommand / ファイル / API ツールを使い、画面の確認と操作を computerUse に渡す。
runCommand は `{"request":{"command":"...","executionMode":"auto","timeoutSeconds":30}}`
という入れ子の引数を必要とする。この形を Tool 説明と連携プロンプトに明示する。
アプリの事前登録は不要。たとえば Windows の Start-Process 'https://x.com/' で開き、
起動結果を確認してから、残りの目的と準備済みの内容を computerUse の goal に含める。
computerUse は新しい画像から現在の状態を確認する。起動コマンドの成功を完了とみなさない。
ツールの選択順序はプロンプトによる誘導であり、固定の自動ディスパッチではない。
内部 Vision モデルに Shell を公開する変更はなく、安全上の停止を別ツールで回避しない。
feature 別の検索・記憶 client にはこの指示を追加しない。

JDK 25 と対話可能な Windows desktop が必要。標準では無効。
既存の設定ファイルへ次の設定を追加できる:

```yaml
rei:
  computer-use:
    enabled: true
    max-steps: 20
    history-limit: 5
    stabilization-millis: 500
    repairs: 1
    clipboard-millis: 150
  llm:
    features:
      computer-use:
        # 省略時は既存 chat model / 接続設定を利用
        base-url: http://your-vision-server:port
        api-key: your-key
        model: your-vision-model
```

画像入力と JSON Schema Structured Output に対応したモデルを指定する。
非対応の場合に自由文解析へ fallback しない。repair 回数を使い切るか provider が
エラーを返すと MODEL_ERROR。カスタム接続で通信エラーが出た場合は既存 provider の
default model fallback が働くので、default 側にも同じ能力が必要。

設定上限: max-steps は 1–200、history-limit は 1–20、repairs は 0–2、
各 wait は 1–10000 ms。maxSteps は action 数ではなく observation / decision の回数。
例えば上限の最後に CLICK を送っても、次の観測で DONE を確認できなければ MAX_STEPS。

Spring Boot の headless 既定値を避けるため JVM 起動時に
`-Djava.awt.headless=false` を指定する。実行時に headless / Windows 以外なら CAPTURE_ERROR。
機能を有効化しただけでは Robot を生成しない。

## Structured Output

正規 schema: [action.schema.json](../src/main/resources/computer-use/action.schema.json)。
専用 prompt: [system.md](../src/main/resources/computer-use/system.md)。

root は単一 object。すべてのキーを含め、使用しない値は null とする。
未知キー、重複キー、配列、Markdown fence、末尾の別 JSON、暗黙の型変換を拒否する。

| action | 必須の非 null フィールド |
| --- | --- |
| CLICK / DOUBLE_CLICK | target {description, centerX, centerY}, confidence |
| TYPE_TEXT | text |
| PRESS_KEY | key |
| SCROLL | amount |
| WAIT | millis |
| DONE / FAILED / UNCERTAIN | reason |

全 action で `action` と `risk` は文字列。
risk は LOW / CONFIRM_REQUIRED / PROHIBITED。
その他の root キーは target / confidence / text / key / amount / millis / reason。

```json
{
  "action": "CLICK",
  "risk": "LOW",
  "target": {"description": "保存ボタン", "centerX": 123, "centerY": 45},
  "confidence": 0.94,
  "text": null,
  "key": null,
  "amount": null,
  "millis": null,
  "reason": null
}
```

Java 側で座標、有限の confidence [0,1]、必須フィールド、長さ、キー名、
scroll / wait の上限を検証。0.8 未満のクリック confidence は dispatch せず UNCERTAIN に変換。
不正応答は default 1 回だけ同一観測の schema 修復を依頼し、再失敗は MODEL_ERROR。
通常の Provider HTTP retry / timeout は既存 AI 基盤の設定に従う。

sealed `ComputerAction` に action ごとの record を定義。
MOVE_MOUSE / DRAG / HOTKEY は v1 で不要と判断して未公開。
追加時は record、schema、parser、validator、input adapter、テストを一緒に拡張する。
内部 clipboard paste の Ctrl+V は TYPE_TEXT の一部であり、独立 Tool ではない。

## OS 操作・座標系

- `ScreenCapture` → `RobotScreenCapture` → `RobotDriver` → `AwtRobotDriver`。
- `ComputerInput` → `RobotComputerInput` → 同じ native driver。
- driver だけが java.awt.Robot を所有し、capture は createScreenCapture を使う。
- v1 の capture 対象は **primary monitor 一台のみ**。別モニター選択、仮想全画面、
  active window 検出は提供しない。全対象ウィンドウを primary monitor に置く。
- screenshot 座標は画像左上を (0,0) とする。Robot は AWT の画面論理座標を使用する。
  point の変換は `origin + floor(imagePixel * logicalSize / imageSize)`。
  HiDPI の倍率を画像へ重ねて掛けない。負の origin と画像 resize を Fake でテストする。
- capture 前後で display geometry を比較。入力直前にも primary 選択、capture 時の
  bounds との一致、画像内座標、virtual screen bounds 内の点であることをチェック。
  モニター構成変更を検出したら入力を拒否する。
- CLICK / DOUBLE_CLICK は左ボタン。PRESS_KEY は prompt に列挙した単一キーのみ。
  SCROLL は wheel notches（正:下、負:上）。スクロール対象はマウス位置に依存するため、
  先に対象領域へクリックしてマウス位置と focus を合わせる。
- キーとマウスボタンは finally で release。GUI の結果判定は次の Vision 観測が担当する。

AWT の座標仕様は [Java 25 Robot API](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Robot.html)
を参照。Structured Output は [Spring AI の公式説明](https://spring.io/blog/2024/08/09/spring-ai-embraces-openais-structured-outputs-enhancing-json-response/)
を参照し、実際の API は本プロジェクトの 2.0.0-M3 jar で確認した。

## Unicode / clipboard

TYPE_TEXT は Unicode を clipboard に置いて Ctrl+V。
元 Transferable の各 flavor を置換前に読み出す。遅延取得される native データや
stream / reader を保持し、paste 消費待ち後に元データを復元する。
保存できない場合は paste 前に停止。stream は 8 MiB、reader は 4 Mi 文字まで。
元が空の場合の復元は空文字列。

Rei 内は割り込み可能な lock で clipboard transaction を直列化し、workflow 全体も排他する。
独自 token を paste 前と復元前に照合し、別アプリの更新があれば上書きしない。
dispatch / stabilization が失敗しても finally で復元を試みる。
復元失敗は ACTION_ERROR とし、再 paste は行わない。元の例外がある場合は suppressed に保持する。
非対応形式、他プロセスとの完全に原子的な clipboard 操作、アプリ固有の paste 消費時間は保証しない。
必要に応じて clipboard-millis を調整する。clipboard history を消去する機能はない。

## 安全・終了・イベント

`SafetyPolicy` を dispatch 前に評価。既定は LOW のみ許可。
CONFIRM_REQUIRED / PROHIBITED は SAFETY_BLOCKED。
モデルの分類が安全を保証するわけではない。Human Approval UI は未実装で、
ブロックされた action を承認待ちのまま保持したり、自動再実行したりしない。
将来は goal / observation を参照する policy と承認フローへ拡張できる。

結果は DONE / FAILED / MAX_STEPS / CANCELLED / MODEL_ERROR / CAPTURE_ERROR /
ACTION_ERROR / STABILIZATION_ERROR / SAFETY_BLOCKED / BUSY。
UNCERTAIN / WAIT は入力を送らず stabilizer 待ち後に再観測し、maxSteps を消費する。
default stabilizer は注入可能な Sleeper で一定時間待つ。

capture、model、safety、dispatch、wait の境界と、repair 間で cancellation を確認。
既存 run の cancel は worker を interrupt する。HTTP / native 呼び出しの最中の停止時期は
下位実装に依存し、送信済み OS イベントを取り消すことはできない。

`computer_use.progress` は step / phase / action / target / coordinates / confidence / reason のみ。
外側の Tool lifecycle は既存イベントで表現し、成功戻り値は「Tool が返った」こと、
DONE は「goal が達成された」こととして区別する。
Shell へ `[computer_use]` を表示し、run / project 所有情報を維持する。
イベント metadata は長さ制限、credential redaction、制御文字除去を行う。
モデルが説明に画面内容を引用する可能性はあるので、履歴・イベントの既存アクセス管理は適用される。

## 自動テスト・TDD

テストは Fake / mock の ScreenCapture、ComputerVisionModel、ComputerInput、UiStabilizer、
SafetyPolicy、Sleeper、RobotDriver、ローカル Clipboard を使用する。
新規テストから実画面 capture、OS 入力、LLM HTTP request、実 sleep は行わない。

| テスト | 主な検証 |
| --- | --- |
| ComputerUseServiceTest | Observe→Act→Observe 順序、DONE/FAILED、上限、履歴制限、cancel、低 confidence、エラー区別 |
| ActionValidationTest | 全 action、必須値、厳密 JSON、座標、confidence、型 |
| RobotAdaptersTest | 座標変換、primary 制約、入力順序、全 input action、release、stabilizer |
| ClipboardPasteTest | Unicode、復元、native 遅延取得、競合、失敗 |
| SpringAiComputerVisionModelTest | image/goal/history、schema、Tool 禁止、bounded repair、cancel |
| ComputerVisionWireTest | 実 SDK の JSON シリアライズ、tools / tool_choice の省略、画像と strict schema の維持（HTTP transport は mock） |
| ComputerUseIntegrationTest | workflow callback、run cancel、排他、fallback safety、イベント、Shell |
| ComputerUseConfigurationTest | opt-in、遅延 Robot 初期化、設定 validation |
| ComputerUseApplicationTest | Spring の結線と実際の chat リクエストへの単一 Tool 登録、検索・記憶処理での非公開（OS / model は mock） |
| AiConfigurationTest（追加検証） | 既存 Tools と共存する単一 computerUse 登録 |

実施した Red → Green の単位:
1. 画像→CLICK→画像→TYPE_TEXT→画像→DONE（未定義モデルの compile failure → 最小ループ）。
2. cancel / safety / uncertain / boundary errors（挙動の assertion failure → 境界処理）。
3. JSON / action validation（未定義 parser → 厳密 parser / validator）。
4. 不正判断 / 低 confidence / metadata（誤 dispatch の failure → 検証と再観測）。
5. Robot / clipboard adapter（未定義 adapter → native 境界を使う実装）。
6. 専用 Vision inference（未定義 provider adapter → schema と画像入力）。
7. Tool / event / 排他 / fallback safety（未定義 API → 既存 architecture 統合）。
8. 設定と Tool 登録（未定義設定・登録 API → opt-in wiring）。
9. clipboard の native 遅延取得と競合（2 件の failure → snapshot と token 照合）。
10. action_started / action_completed イベント中の cancel（入力や wait が続く failure → 境界の再チェック）。
11. 通常 chat の送信 Prompt にある Tool 一覧（computerUse が 0 件の failure → feature 別 client への登録）。
12. 実 SDK の HTTP JSON（tools: [] が送信される failure → tools / tool_choice の省略）。
13. モデルの raw default tools（拒否されない failure → SDK merge 前の拒否）。
14. 返答検証の診断（Tool 結果・終了イベントから理由が失われる failure → 安全な検証理由の伝達）。
15. repair prompt（schema と具体的な修正理由がない failure → 専用 prompt への明示）。
16. 通常 chat の使い分け指示（送信 Prompt にない failure → 有効時の指示追加、Shell との共存と他 feature への非追加を検証）。
17. 操作への説明付与（reason を拒否する failure → 型・長さを検証して許可）。
18. 返答なし・サイズ超過・出力上限（理由を識別できない failure → 診断分離、出力上限時の再試行停止）。

各 Green 後に summary / progress の共通化、adapter 分離、resource 化などを整理。
追加の全 action / 実アプリ結合テストで回帰範囲を確認した。
実行ログは作業 worktree の target/tdd-logs（Git 対象外）に保存。

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

既存 suite には sqlite-vec の公開バイナリを download するテストがあるためネットワークが必要。
DB とログをテスト専用の書き込み可能な場所へ分離できる:

```powershell
$env:REI_DATA_DIR = Join-Path $PWD 'target/test-data'
$env:LOGGING_FILE_NAME = Join-Path $PWD 'target/test-data/rei.log'
.\mvnw.cmd test
```

## 最終検証結果

初回実装の検証記録（2026-09-12）: 既存 baseline は 1,496 件成功。Maven verify は
新規 40 件を含む 1,536 件が成功、failure / error / skipped はすべて 0。
パッケージングと git diff --check も成功。専用 formatter / lint / static analysis の
Maven 設定はない。全 suite で見つかった新規アプリテストの ProjectService 状態漏れは
テスト終了時の復元で修正し、既存の履歴検索テストも維持した。

通常 chat の Tool 登録修正後は 1,537 件成功（failure / error / skipped は 0）。
実際の client から、明示的な runtime options を渡した stream リクエストを検査し、
computerUse が CHAT にだけ 1 件含まれることを確認した。
標準 JAR の repackage は Windows のリネーム拒否により完了しなかったため、
同じ検証済み classes を使って target/computer-use-fixed/rei-0.0.1-SNAPSHOT.jar へ
別途 package した。実行可能 JAR 内の修正クラスと検証済み class の SHA-256 も一致確認済み。

Vision HTTP JSON 修正後は Maven verify で 1,539 件成功（failure / error / skipped は 0）。
初回は既存 ToolsTest の stdout 到着後の stderr 検査が 1 件失敗したが、コード変更なしの
単独再実行と全 suite 再実行で成功した。実行可能 JAR は
target/computer-use-http-fix/rei-0.0.1-SNAPSHOT.jar に出力し、修正した 2 クラスの
SHA-256 が検証済み class と一致することも確認した。実 SDK の送信 JSON は
in-memory HTTP transport で検証しており、実推論サーバーでの動作は未検証。

返答検証の診断・repair prompt 修正後は Maven verify で 1,542 件成功
（failure / error / skipped は 0）。実行可能 JAR は
target/computer-use-diagnostics/rei-0.0.1-SNAPSHOT.jar。
変更した 5 クラスの JAR 内 SHA-256 と検証済み class の一致を確認した。
実モデルでの再現と GUI smoke は未実施であり、利用者の過去の不正返答の原因は未特定。

Shell と画面操作の使い分け追加後も Maven verify で 1,542 件成功
（failure / error / skipped は 0）。実行可能 JAR は
target/computer-use-hybrid/rei-0.0.1-SNAPSHOT.jar。
変更した 3 クラスと orchestration.md の JAR 内 SHA-256 の一致を確認した。
実モデルによるツールの選択順序と GUI 操作は未検証。

操作 reason 対応・出力診断・runCommand 引数指示の修正後は Maven verify で
1,545 件成功（failure / error / skipped は 0）。実行可能 JAR は
target/computer-use-response-fix/rei-0.0.1-SNAPSHOT.jar。
変更した 3 クラスと 2 プロンプトの JAR 内 SHA-256 一致を確認した。
実推論サーバーと GUI での成功は未検証。

## Manual Windows smoke test（CI では実行しない）

1. API / model を上記の Vision + Structured Output 対応構成にする。
2. primary monitor に空のメモ帳を開く。Rei とメモ帳の双方が見えるよう配置する。
3. PowerShell から起動:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Djava.awt.headless=false -Drei.computer-use.enabled=true'
```

4. Rei の chat で依頼する:
   「computerUse を使い、画面上のメモ帳の空のテキスト領域に
   Hello Computer Use と入力してください。保存や送信はしないでください。」
5. `[computer_use]` の observed / decided / action_completed を確認。
   メモ帳に文字が表示され、後続観測で DONE になることを確認する。
6. 任意で日本語入力、既存の Esc cancellation、表示倍率 100/150/200%、
   secondary monitor を含む構成を確認。全操作対象は primary に置く。
7. 実行後に clipboard が元へ戻っていることを確認する。

GUI smoke は手順の提供まで。自動テスト実行では実 desktop / 実モデルの成功は検証しない。

## 現在の制約と改善候補

primary のみ、Windows の通常対話 desktop のみ。UAC secure desktop、
Remote Desktop、OCR、UIA、独自 detector、batching、複数プロセス間の desktop 排他は非対応。
ユーザーの並行操作で screenshot と実画面が変わる競合は完全には排除できない。

今後の候補は Human Approval UI と policy 強化、HTTP 推論の明示的 deadline / cancel、
対象付き scroll / hotkey / drag、monitor 選択、画像差分による stabilization。
画像差分は将来の observation metadata として検討し、goal 成功の判定器にはしない。
