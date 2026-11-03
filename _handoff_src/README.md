# F:\ai-image — ローカル画像生成環境

RTX 4070 Ti SUPER (16GB VRAM) / Windows 11 / Python 3.12 向けの
アニメキャラ絵量産構成。

## 構成

| 要素 | 採用 |
|---|---|
| UI | ComfyUI（API で自動化しやすい） |
| 推論 | PyTorch 2.11.0 + CUDA 12.8 |
| ベースモデル | SDXL 系アニメ特化 finetune（Illustrious / NoobAI 系） |
| プロンプト | Danbooru タグ |
| 追加 | キャラ LoRA / ControlNet(OpenPose) / 4x アップスケーラ |

Flux は文字入れなど必要な場面に限定する方針。

## ディレクトリ

```
F:\ai-image\
  ComfyUI\            ComfyUI 本体（git clone）
  venv\               Python 仮想環境
  models\
    checkpoints\      ベースモデル (.safetensors)
    loras\            キャラ・絵柄 LoRA
    vae\              VAE
    controlnet\       ControlNet
    upscale_models\   アップスケーラ
    embeddings\       textual inversion
  output\
    raw\              生成直後
    selected\         選別後
    upscaled\         アップスケール後
  input\              入力画像（img2img / ControlNet 用）
  workflows\          ComfyUI ワークフロー JSON
  scripts\            量産・選別スクリプト
  prompts\            タグ組み合わせ CSV/JSON
  logs\               実行ログ
```

モデルは `ComfyUI\extra_model_paths.yaml` 経由で `F:\ai-image\models` を参照する。
ComfyUI 配下に二重に置かなくて済む。

## 起動

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File F:\ai-image\start-comfyui.ps1
```

ブラウザで http://127.0.0.1:8188 を開く。

## モデルの置き場所

| 種類 | 置く場所 |
|---|---|
| ベースモデル | `models\checkpoints\` |
| LoRA | `models\loras\` |
| VAE | `models\vae\` |
| ControlNet | `models\controlnet\` |
| アップスケーラ | `models\upscale_models\` |

配置後は ComfyUI の画面で **Refresh** を押すと認識される。

## 生成の初期設定

| 項目 | 値 |
|---|---|
| 解像度 | 832x1216（縦長）または 1024x1024 |
| サンプラー | Euler a / DPM++ 2M Karras |
| steps | 20〜30 |
| CFG | 4〜7 |
| バッチ | 4〜8 |

v-prediction モデルを使う場合はモデルカードの指定に従うこと。
設定を間違えると色が破綻する。

## 量産の考え方

ボトルネックは生成ではなく**選別**。

1. **生成** — タグの組み合わせを外部 CSV/JSON から回す。ComfyUI の `/prompt` API に投げる。
2. **選別** — 顔検出・美的スコア・CLIP 一致度・重複排除で自動的に絞る。上位 20〜30% だけ残す。
3. **後処理** — FaceDetailer で顔を描き直し、4x アップスケール後に目標サイズへ縮小。
4. **管理** — PNG メタデータと生成ログ（モデル・LoRA・タグ・シード）を必ず残す。

## 注意

- 夜間バッチは「生成 → 選別 → 後処理」を段ごとに分ける。一気にやると失敗時に全部やり直しになる。
- アップスケール込みだと 1 枚 20MB 超。1000 枚で 20GB 程度を見込む。
- Civitai のモデルは商用不可・再配布不可が多い。公開する場合はライセンスを確認する。
- JDK ではなく Python 環境なので、`cacerts` の話は無関係。ただしモデルのダウンロード元は HTTPS なので証明書は OS 側で管理される。
