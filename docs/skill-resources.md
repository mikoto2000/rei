# Agent Skill の参照資料

`SKILL.md` と参照資料を分けたスキルに対応します。

```text
skills/review-learning-materials/
├── SKILL.md
└── references/
    ├── learning-design.md
    └── exercise-review.md
```

選択されたスキルの本文と絶対パスをモデルへ渡します。参照資料は自動的に全件展開せず、必要なものを `readSkillResource` で読みます。

```json
{
  "skillName": "review-learning-materials",
  "files": [
    { "path": "references/learning-design.md" },
    { "path": "references/exercise-review.md" }
  ]
}
```

`files[].path` は現在の Project ではなくスキルフォルダ基準です。参照資料内のリンクはその資料の親フォルダ基準で解決し、スキルフォルダ基準のパスに直して呼び出します。

行範囲は `files[].startLine` / `files[].endLine` で指定できます。長い資料で `truncated` が返った場合は、同じ `skillName` と各結果の `nextRead` をそのまま `files` 配列に指定して続きを読みます。読み取り中に内容が変わった場合はバージョン不一致として再確認が必要になります。

1回の呼び出しで同じスキルの資料を1〜20件指定でき、結果は入力順の配列で返ります。1件の読み込みも `files` 配列で指定します（従来の `file` 引数から変更）。バッチ全体で読み取りのバイト数・行数・結果トークン数の予算を共有します。

有効なスキルだけを読み取れます。絶対パス、スキルフォルダ外のパス、秘密情報に該当するファイル、リンク経由のパスは拒否します。通常の Project ファイル読み取り制限と既存の MCP 制限は維持します。読み取りは READ 能力として既存のツール権限管理に従い、スクリプトの実行やファイル変更の許可を与えません。

スキルはキャッシュされるため、追加・変更後は Skills の再読込が必要です。この機能の利用には対応コードでビルドしたアプリが必要です。
