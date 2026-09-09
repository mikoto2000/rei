# ストリーム出力の不自然な改行の修正

## 原因と修正

入力中のJLineShellEventOutputは、バッファに改行があれば未完成の次行もまとめてprintAboveへ渡していた。printAboveが末尾の改行を補うため、例えば `\n**embed` と `ding` が別々の行になった。幅によるflushもバッファ全体を出力しており、モデルのchunk分割位置に依存する改行になっていた。

完成した行だけをprintAboveへ渡し、次行の未完成部分は保持する。端末幅より長い行はWCWidthで表示列数を計算して折り返す。日本語の全角文字を2列として扱い、UTF-16のサロゲートペアを途中で分割しない。元の空行は維持し、CRLFは一つの改行として扱う。

入力受付が終了した時のflushでも保留テキストを出力する。入力中は行単位または端末幅に達した単位で更新するため、短い未完成行は次の改行またはメッセージ終了まで表示を待つ。

## 検証

先に再現テストを追加しREDを確認（target/shell-output-red.log）。修正後、表示関連28件が成功（target/shell-output-green.log）。

- 改行に続くMarkdownや単語の未完成部分を早まって確定しない。
- 長いストリームは回答終了前に表示する。
- 日本語を端末の表示列数で折り返し、不要な空行を追加しない。
- 入力終了後のflushで残りを失わない。
- 既存rendererとProject切替表示。

全体テストとJAR生成:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' package
```

全体テスト **1,405件、Failures 0、Errors 0、Skipped 0**。結果はtarget/shell-output-package.log。通常のrepackageで既存JARのrenameに失敗したため、検証済みソースをtarget/shell-fixedへ別ビルドし、実行可能JARを通常のtarget/rei-0.0.1-SNAPSHOT.jarへ差し替えた（target/shell-output-repackage.log）。JAR内の修正クラスとテスト済みクラスの一致も確認。

実際のWindows Terminalでの手動確認は未実施。通知イベントが回答途中に入る場合、その通知自体は従来どおり独立した行で表示する。
