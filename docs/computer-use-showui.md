# ShowUI grounding

## 起動オプション `--fullauto`

`java -jar target/rei-0.0.1-SNAPSHOT.jar --fullauto` で起動すると、Computer Use は `LOW` に加えて `CONFIRM_REQUIRED` も実行できます。投稿・送信・削除・購入なども対象です。`PROHIBITED` は常に拒否します。位置特定・検証・重複入力防止は引き続き適用します。Computer Use 自体の有効化設定は別途必要です。

省略時と `--fullauto=false` は従来どおり `LOW` のみです。`--fullauto=true` も使用できます。YAMLや環境変数では有効にせず、起動引数で指定します。起動時に有効・無効とポリシーを表示します。この指定はComputer Useの実行ポリシーにだけ適用し、他のツールの権限は変更しません。

## 操作履歴の詳細

次の判断へ渡す履歴には、クリック対象・座標、入力文字列・要素IDに加え、キー操作の `key`、スクロールの `amount` / `direction` / `unit`、待機の `millis` を記録します。スクロールはホイールのノッチ数で、正数が下、負数が上です。キー・スクロール・待機の値は診断の `decided.json` にも残し、実際に入力を送ったキー・スクロールは `dispatched.json` にも残します。履歴は操作の実施を示し、目的達成の証明にはしません。

## UI Automation によるフォーカス確認

`focus.json` と Shell の `focus_observed` には対象の `processId` / `processName`、取得プロセスの `probeProcessId`、編集可能判定の根拠 `editableReason` も記録します。失敗時は `reason` に `timeout` / `process_exit` / `invalid_json` / `invalid_snapshot` / `uia_exception` などを記録し、UIA例外には処理段階と例外情報も残します。取得プロセス自身へのフォーカスは `probe_owns_focus` として不明扱いにします。編集可能判定だけの失敗では、取得済みの要素情報を保持し `editable: null` とします。

検証モデルが出力上限に達した場合は、`point-verification reached output token limit (finish_reason=length)` のように段階名と原因を表示して失敗終了します。応答は診断に保存し、同じ要求の再試行や出力上限の自動引き上げは行いません。

Windows では各ステップの判断前に UI Automation の現在のフォーカス要素を読み取ります。前ステップのクリック後も再取得し、名前・コントロール種類・編集可能状態・有効状態・画面上の範囲を、画像と操作履歴と一緒に判断モデルへ渡します。ShowUI / UI-TARS / generic 共通です。対象欄へのフォーカスが確認できた場合は、キャレットが画像に写っていなくても同じ欄へのクリックを繰り返さず、必要な文字入力を選ぶよう指示します。UIA だけで入力を自動実行するものではありません。

UIA は非表示の Windows PowerShell 子プロセスで読み取り、最大5秒で打ち切ります。取得不能・タイムアウト・Windows以外では `unknown`、編集可能か取得できない場合は `editable: null` として扱います。ValuePattern の IsReadOnly または TextPattern の IsReadOnly 属性で判定し、TextPattern があるだけでは編集可能と断定しません。フォーカスされた編集可能な非パスワード欄に限り、現在値を最大10000文字取得します。`valueStatus` は `ok` / `truncated` / `unavailable` / `read_error`、`elementId` はプロセスIDとUIA RuntimeIdの組み合わせです。値は判断モデルと診断へ渡します。フォーカス変更やUIA経由の入力は行いません。状態は Shell の `focus_observed` イベントと診断の各 `step-*/focus.json` で確認できます。取得時点の情報のため、判断中のユーザー操作によるフォーカス変更までは保証しません。

## ShowUI の要求形式

`showui` の位置特定では、公式例と同じ「指示テキスト → 画像 → 対象説明」の順序を、1つの user メッセージ内で送信します。画面全体と切り出し画像の両方に適用します。Spring AI の標準メッセージ変換ではテキストが画像より前にまとめられるため、ShowUI の位置特定要求だけ HTTP 送信時に順序を整えます。診断 JSON の `contentOrder` でも順序を確認できます。

保存画像1枚・同一対象・temperature=0 の比較では、対象説明を画像より前に置く形式は2回とも検索欄付近、公式の順序は2回とも投稿入力欄内を返しました。他の画像や2段階処理全体の精度を保証する結果ではありません。UI-TARS の要求形式はこの変更の対象外です。

## 判断モデルによる検証

最終候補点の検証では、選択ディスプレイの座標から UI Automation `FromPoint` で要素を取得し、種類・名前・範囲を画像検証の補助情報として渡します。フォーカス要素の取得とは独立した読み取りで、対象説明や目標はこの要素識別要求に渡しません。候補点のUIA取得ではテキスト値は読みません。取得不能は不明とし、画像と矛盾する場合や要素を特定できない場合は引き続き拒否します。診断は `point-description-uia.json` に保存します。AWTのDPI拡大率が1以外の場合は物理座標との混同を避けるため、今回の候補点UIA情報は `unsupported_point_dpi` として不明扱いにします。

`showui` / `uitars` の両方で、`computer-use-planner` に以下の検証を要求します。

1. 切り出し画像内で対象が明確に識別できるかを、再推定の前に確認する。
2. 再推定後の座標を赤いリングで示した全画面画像と、候補点周辺の拡大画像を渡し、目標・履歴を伏せた独立の要求でリング中心の要素を説明させる。拡大画像は縮小前の元画像から最大960×540ピクセルを切り出し、最大1200×675ピクセル（拡大率は最大2倍）に拡大する。画面端では範囲を画面内へ移し、リングも対応する位置へ移す。`elementType`（input/button/link/text/other/unknown）、`label`、`certain` を要求し、不明・不確実なら停止する。
3. 画像を渡さない別の要求で観察結果と目標を照合する。`approved`、`expectedType`、`reason` を要求し、目標種別と観察種別が一致する場合だけ承認を受け入れる。

切り出し検証と最後の照合で厳密な JSON の `approved: true` と空でない理由が返り、独立した観察結果も明確で種別が一致する場合だけクリックへ進みます。拒否・不正応答・出力打ち切り・通信例外は MODEL_ERROR で停止します。キャンセルはキャンセルとして扱います。検証では新しい座標や操作は受け付けません。成功時のモデル呼び出しは計6回です。モデルの誤承認までは防げないため、位置精度は実環境での確認が必要です。

診断有効時は `crop-verification-*` と `point-description-*` に画像・要求・応答、`point-verification-*` に文字だけの照合要求・応答を保存します。接尾辞は `input.png` / `request.txt` / `response.txt`、通信例外は `error.txt` です。目印付き全画面画像は `point-description-input.png`、拡大画像は `point-description-detail.png`、切り出し範囲と座標は `point-description-geometry.json` に保存します。元画像や画面自体は変更しません。

## 2段階の位置特定（ShowUI / UI-TARS 共通）

`CONFIRM_REQUIRED` のクリック／ダブルクリックも、`LOW` と同じ2段階の位置特定と検証を通します。返却する操作のリスク分類は保持し、実行可否はその後の安全ポリシーで判断します。この変更では投稿などの実行許可は追加しません。`PROHIBITED` は引き続き位置特定を行わず実行を拒否します。

選択画面全体で粗い位置を推定した後、その周辺を元画像の縦横それぞれ1/2の大きさで切り出し、同じ対象説明で再推定します。切り出し範囲は画面端で画面内に収めます。2回目も画像サイズ上限を適用し、返却座標を切り出し画像から元画像の座標へ変換します。各クリック／ダブルクリックでは判断モデル1回と位置特定モデル2回が必要です。

2回目の失敗・不正な応答・キャンセルではクリックしません。1回目や判断モデルの座標へのフォールバックはありません。ただし、1回目が大きく外れて対象が切り出し範囲に入らない場合や、モデルが別の対象の有効な座標を返した場合に精度を保証するものではありません。

診断の2回目のファイル名は `<mode>-refinement-input.png`、`<mode>-refinement-request.json`、`<mode>-refinement-response.json`、通信例外時は `<mode>-refinement-error.txt` です。`<mode>` は `showui` または `uitars`。request JSON の `cropLeft` / `cropTop` / `cropWidth` / `cropHeight` は元画像上の範囲、`sentWidth` / `sentHeight` は実際に送った画像サイズです。

## UI-TARS-2B-SFT を使う場合

`rei.computer-use.grounding: uitars` を指定し、`rei.llm.features.computer-use` に UI-TARS の接続先と公開モデル名を設定してください。判断モデルは引き続き `computer-use-planner`（未指定時は通常の画像対応モデル）を使います。

```yaml
rei:
  computer-use:
    enabled: true
    grounding: uitars
  llm:
    features:
      computer-use:
        base-url: http://gx10-6acc.local:8888
        api-key: ${REI_COMPUTER_USE_API_KEY:dummy-key}
        model: ui-tars  # サーバーの served-model-name に合わせる
```

UI-TARS の公式位置特定プロンプトで `(x,y)` を要求し、0〜1000の各値を1000で割って割合座標に変換します。例: `(281,659)` → `[0.281,0.659]`。座標尺度の自動推測は行いません。複数点、範囲外、説明付き応答、アクション式は受け付けず MODEL_ERROR とします。モデルが返すコードを実行することはありません。

画像縮小・対象画面の選択・出力上限128・フォールバック禁止は ShowUI と共通です。診断ファイルの接頭辞は `showui-` ではなく `uitars-` になります。位置精度は実サーバーでの確認が必要です。

公式仕様: https://github.com/bytedance/UI-TARS/blob/main/README_v1.md （single step grounding / Coordinate Mapping）

## ShowUI の設定

ShowUI mode separates visual action planning from click localization. Enable it in the external application.yaml:

```yaml
rei:
  computer-use:
    enabled: true
    grounding: showui
  llm:
    features:
      computer-use:
        base-url: http://gx10-6acc.local:8888
        api-key: ${REI_COMPUTER_USE_API_KEY:dummy-key}
        model: showlab/ShowUI-2B
      # Optional: otherwise the default spring.ai.openai connection/model is used.
      # computer-use-planner:
      #   base-url: http://gx10-707e.local:8888
      #   api-key: dummy-key
      #   model: deepseek-v4-flash-vision-exp
```

The planner must support images. It sees bounded screenshots of the attached displays and chooses an action and a short English target description. ShowUI receives only the selected display and target description, without task history, the action JSON schema, or tools. It returns a strict normalized `[x, y]` pair. Output is capped at 128 tokens in this mode. Each image is resized with its aspect ratio preserved to at most `1344 * 28 * 28` pixels; original screenshots and desktop coordinates are retained for dispatch and diagnostics.

Grounding failures stop the Computer Use workflow as MODEL_ERROR. Neither the planner nor grounding connection falls back to another endpoint. Invalid grounding coordinates never fall back to planner coordinates. Non-click actions and completion checks remain the planner's responsibility. Planner confidence is retained; ShowUI does not provide a confidence score.

`grounding: generic` (default) preserves the previous schema-based model and crop refinement workflow. Changing configuration requires restarting Rei. Server-side image preprocessing can affect token counts; the bounded image does not guarantee that every model server fits a 4096-token context.

Reference: https://huggingface.co/showlab/ShowUI-2B

## 診断ファイル

`rei.computer-use.diagnostics.enabled: true` の場合、既存の診断実行ディレクトリの `step-NNN` に次を保存します。

| ファイル | 内容 |
| --- | --- |
| `showui-input.png` | ShowUI に送った縮小画像と同じ PNG バイト列 |
| `showui-request.json` | 対象画面 ID、元画像・送信画像サイズ、対象説明、実際のプロンプト、モデル、出力上限 |
| `showui-response.json` | モデルが返したテキスト（`texts`）と Spring AI のレスポンス表現。座標の検証前に保存 |
| `showui-error.txt` | 通信呼び出しが例外になった場合のスタックトレース |

`showui-response.json` は HTTP 応答全体の生バイト列ではありません。`texts` はパース・補正前のモデル出力をそのまま保持します。診断無効時は保存せず、保存失敗は操作の再試行や推論の中断を引き起こしません。対象説明や応答には画面由来の内容が含まれます。

## テキストの重複入力防止

入力した文字列を typedText として操作履歴に残します。TYPE_TEXT の直前にもUIAを再取得して typing-focus.json に保存し、同じ文字列を同じ欄へ再入力しようとした場合は、現在値を確認します。同じ文字列が既に含まれる場合、部分入力などで欄が空ではない場合、値や対象欄を確認できない場合は、重複の可能性がある入力を MODEL_ERROR で停止します。同一の要素IDで欄が空と確認できた場合は再試行を許容します。別の要素IDへの同じ文字列の入力は区別します。この記録はタスク内で保持し、モデルの履歴件数制限による削除とは独立しています。
