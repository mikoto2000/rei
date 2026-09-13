# ShowUI grounding

## 2段階の位置特定（ShowUI / UI-TARS 共通）

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
