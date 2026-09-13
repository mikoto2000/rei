# 画面操作（Computer Use）

[README に戻る](../README.md) · [設定ガイド](configuration.md)

Windows の画面をスクリーンショットで確認し、クリックや文字入力を行います。既定では無効です。複数のディスプレイに対応します。

有効化後は通常のチャットで画面操作を依頼できます。以下は ShowUI を使用する設定例です。モデル名と接続先を手元のサーバーに合わせて変更してください。

## 有効化する

Windows では `%LOCALAPPDATA%\Rei\application.yaml` の既存設定に以下を統合し、Rei を再起動してください。`REI_DATA_DIR` を指定している場合は、そのディレクトリの設定ファイルを使用します。

```yaml
rei:
  computer-use:
    enabled: true
    grounding: showui
  llm:
    features:
      computer-use:
        base-url: http://localhost:8888
        api-key: ${REI_COMPUTER_USE_API_KEY:dummy-key}
        model: showlab/ShowUI-2B
      # 操作判断の接続先を分ける場合に指定します。
      # 未指定なら spring.ai.openai の接続先・モデルを使用します。
      # computer-use-planner:
      #   base-url: http://localhost:8000
      #   api-key: dummy-key
      #   model: your-vision-model
```

`grounding` は、画面上の対象を座標に結び付ける処理の方式です。

| 値 | 動作 |
| --- | --- |
| `generic`（既定） | 一つの画像対応モデルで操作判断とクリック位置の特定を行う方式 |
| `showui` | 画像対応の判断モデルが操作・対象画面を選び、ShowUI がクリック位置を特定する方式 |
| `uitars` | 画像対応の判断モデルが操作・対象画面を選び、UI-TARS がクリック位置を特定する方式 |

UI-TARS-2B-SFT を使う場合は `grounding: uitars` に変更し、`rei.llm.features.computer-use.model` にサーバーで公開したモデル名を指定します。接続先・判断モデルの設定は ShowUI モードと共通です。モデル名の変更だけでは出力形式が切り替わらないため、`grounding` も必ず変更してください。

ShowUI / UI-TARS を使う場合、操作を判断するモデルは画像入力に対応している必要があります。位置特定や検証に失敗した場合は操作を停止します。

## 投稿・送信などの操作を許可する

通常は低リスクと判定された操作だけを実行します。投稿・送信・削除・購入なども許可する場合は、起動引数に `--fullauto` を指定します。

Windows（PowerShell）:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--fullauto"
```

Linux / macOS:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--fullauto"
```

この指定で許可されるのは Computer Use の操作です。禁止操作は引き続き拒否され、位置特定と結果の検証も行われます。起動時に有効状態を表示します。引数を省略するか `--fullauto=false` を指定すると無効になります。YAML や環境変数では有効化できません。

## 問題を調べる

診断ログには画面に表示された内容が含まれることがあります。

詳しい構成は [ShowUI grounding](computer-use-showui.md) を参照してください。
