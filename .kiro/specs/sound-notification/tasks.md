# 実装計画: 音声通知機能

## TDD の進め方

各タスクは **Red → Green → Refactor** サイクルで進める。

1. **Red**: 失敗するテストを先に書く。コンパイルエラーも Red として扱う
2. **Green**: テストが通る最小限の実装を書く。きれいさより動くことを優先する
3. **Refactor**: テストが通ったまま、重複排除・命名改善・設計整理を行う

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

---

## タスク

- [x] 1. SoundNotificationProperties を作成する（Red → Green）
  - [x] 1.1 **Red**: デフォルト値を検証するテストを書く
    - `enabled` のデフォルトが `false` であることを検証するテストを書く
    - `command` のデフォルトが空リストであることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 1.2 **Green**: クラスを実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/sound/SoundNotificationProperties.java` を作成する
    - `@ConfigurationProperties(prefix = "rei.sound-notification")` + Lombok `@Getter`/`@Setter` で実装する
    - `enabled`（boolean, デフォルト false）と `command`（List<String>, デフォルト空リスト）を追加する
    - テストが通ることを確認する
  - _Requirements: 1.1, 2.1_

- [x] 2. SoundNotificationService — `enabled=false` のフォールバックを TDD で実装する
  - [x] 2.1 **Red**: 失敗するテストを書く
    - `enabled=false` のとき `notify()` を呼び出すと標準出力にメッセージが出力されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 2.2 **Green**: 最小限の実装でテストを通す
    - `src/main/java/dev/mikoto2000/rei/sound/SoundNotificationService.java` を作成する
    - `notify(String message)` と `fallbackToConsole(String message)` を実装する
    - `enabled=false` のとき warn ログを出力して `fallbackToConsole()` を呼び出す
    - テストが通ることを確認する
  - [x] 2.3 **Refactor**: 標準出力のキャプチャ方法を整理する
  - _Requirements: 1.2, 1.3_

- [x] 3. SoundNotificationService — `command` 未設定のフォールバックを TDD で追加する
  - [x] 3.1 **Red**: 失敗するテストを書く
    - `enabled=true` かつ `command` が空リストのとき標準出力にフォールバックすることを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 3.2 **Green**: 最小限の実装でテストを通す
    - `command` が空のとき warn ログを出力して `fallbackToConsole()` を呼び出す
    - テストが通ることを確認する
  - _Requirements: 2.1, 2.2_

- [x] 4. SoundNotificationService — `{{MESSAGE}}` 置換を TDD で実装する
  - [x] 4.1 **Red**: 失敗するテストを書く
    - `{{MESSAGE}}` を含むコマンドで `notify("hello")` を呼び出すと、コマンドの `{{MESSAGE}}` が `"hello"` に置換されることを検証するテストを書く
    - `{{MESSAGE}}` がコマンドに含まれない場合に warn ログが出力されることを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 4.2 **Green**: 最小限の実装でテストを通す
    - コマンドリストの各要素の `{{MESSAGE}}` をメッセージに置換する
    - `{{MESSAGE}}` がない場合は warn ログを出力してそのまま実行する
    - テストが通ることを確認する
  - [x] 4.3 **Refactor**: 置換ロジックをプライベートメソッドに抽出する
  - _Requirements: 3.1, 3.2_

- [x] 5. SoundNotificationService — 外部プログラム実行（正常系）を TDD で実装する
  - [x] 5.1 **Red**: 失敗するテストを書く
    - `enabled=true` かつ有効なコマンドで `notify()` を呼び出すと外部プログラムが実行されることを検証するテストを書く（`ProcessBuilder` をモック）
    - この時点でテストが失敗することを確認する
  - [x] 5.2 **Green**: `ProcessBuilder` を使った外部プログラム実行を実装してテストを通す
    - テストが通ることを確認する
  - _Requirements: 1.1_

- [x] 6. SoundNotificationService — 外部プログラム失敗時のフォールバックを TDD で追加する
  - [x] 6.1 **Red**: 失敗するテストを書く
    - 外部プログラムが非ゼロ終了コードで終了したとき標準出力にフォールバックすることを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 6.2 **Green**: 非ゼロ終了コード時に warn ログ + `fallbackToConsole()` を呼び出す実装を追加してテストを通す
    - テストが通ることを確認する
  - _Requirements: 4.1, 4.2_

- [x] 7. SoundNotificationService — タイムアウト処理を TDD で追加する
  - [x] 7.1 **Red**: 失敗するテストを書く
    - 外部プログラムがタイムアウトしたとき、プロセスが強制終了されて標準出力にフォールバックすることを検証するテストを書く
    - タイムアウト値を短く設定するか `Process` をモックして実際に 30 秒待機しないようにする
    - この時点でテストが失敗することを確認する
  - [x] 7.2 **Green**: `process.waitFor(30, TimeUnit.SECONDS)` を使ったタイムアウト処理を実装してテストを通す
    - タイムアウト時は `process.destroyForcibly()` でプロセスを強制終了する
    - warn ログを出力して `fallbackToConsole()` を呼び出す
    - テストが通ることを確認する
  - [x] 7.3 **Refactor**: タイムアウト値を定数またはプロパティに抽出する
  - _Requirements: 5.1, 5.2_

- [x] 8. チェックポイント — ここまでのテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=SoundNotification*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 9. SoundNotificationService — プロパティテストを追加する
  - [x] 9.1 **Property 1: enabled=false のとき標準出力にフォールバックする**
    - 様々なメッセージ（空文字・日本語・記号・長文など 10 パターン）で `enabled=false` のとき標準出力フォールバックが呼ばれることを `@ParameterizedTest` で検証する
    - **Validates: 要件 1.2**
    - タグ: `sound-notification-property-1-enabledFalse`
  - [x] 9.2 **Property 2: command が空のとき標準出力にフォールバックする**
    - 様々なメッセージで `command=空リスト` のとき標準出力フォールバックが呼ばれることを `@ParameterizedTest` で検証する
    - **Validates: 要件 2.1**
    - タグ: `sound-notification-property-2-emptyCommand`
  - [x] 9.3 **Property 3: `{{MESSAGE}}` プレースホルダーが正しく置換される**
    - 様々なメッセージと様々なコマンドパターンで `{{MESSAGE}}` が正しく置換されることを `@ParameterizedTest` で検証する
    - **Validates: 要件 3.1**
    - タグ: `sound-notification-property-3-messagePlaceholder`
  - [x] 9.4 **Property 4: 外部プログラム実行失敗時に標準出力にフォールバックする**
    - 様々なメッセージで非ゼロ終了コード時に標準出力フォールバックが呼ばれることを `@ParameterizedTest` で検証する
    - **Validates: 要件 4.1**
    - タグ: `sound-notification-property-4-execFailure`

- [x] 10. SoundNotificationTools を TDD で実装する
  - [x] 10.1 **Red**: 失敗するテストを書く
    - `SoundNotificationTools.notify(message)` を呼び出すと `SoundNotificationService.notify(message)` が同じメッセージで呼ばれることを検証するテストを書く
    - 戻り値が null でない文字列であることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 10.2 **Green**: `SoundNotificationTools` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/sound/SoundNotificationTools.java` を作成する
    - `@Tool(name = "soundNotify", ...)` アノテーションを付与する
    - `SoundNotificationService.notify()` に委譲し、成否を文字列で返す
    - テストが通ることを確認する
  - [x] 10.3 **Refactor**: ツールの description を実用的な文言に整える
  - _Requirements: 6.1, 6.2_

- [x] 11. SoundNotificationTools — プロパティテストを追加する
  - [x] 11.1 **Property 6: SoundNotificationTools が SoundNotificationService に委譲し結果を返す**
    - 様々なメッセージで `SoundNotificationTools.notify()` が `SoundNotificationService.notify()` に委譲し、null でない文字列を返すことを `@ParameterizedTest` で検証する
    - **Validates: 要件 6.1, 6.2**
    - タグ: `sound-notification-property-6-toolsDelegation`

- [x] 12. AiConfiguration に SoundNotificationTools を登録する
  - [x] 12.1 **Red**: `SoundNotificationTools` が `chatClient()` の `defaultTools` に含まれることを検証するテストを書く（または既存の AiConfiguration テストを更新する）
  - [x] 12.2 **Green**: `AiConfiguration` を変更してテストを通す
    - `@EnableConfigurationProperties` に `SoundNotificationProperties.class` を追加する
    - `SoundNotificationTools` フィールドを追加する
    - `chatClient()` の `defaultTools` に `soundNotificationTools` を追加する
    - テストが通ることを確認する
  - _Requirements: 6.1_

- [x] 13. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 14. SoundInterestNotifier を TDD で実装する
  - [x] 14.1 **Red**: 失敗するテストを書く
    - `SoundInterestNotifier.notifyUpdate(update)` を呼び出すと `SoundNotificationService.notify()` がトピック名と要約を結合したメッセージで呼ばれることを検証するテストを書く
    - `ConsoleInterestNotifier.notifyUpdate()` も同じ `update` で呼ばれることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 14.2 **Green**: `SoundInterestNotifier` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/sound/SoundInterestNotifier.java` を作成する
    - `@Primary` + `@Component` + `@RequiredArgsConstructor` で実装する
    - `notifyUpdate()` で `soundNotificationService.notify(topic + " " + summary)` を呼び出す
    - その後 `consoleInterestNotifier.notifyUpdate(update)` に委譲する
    - テストが通ることを確認する
  - [x] 14.3 **Refactor**: メッセージ生成ロジックを整理する
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 15. SoundInterestNotifier — プロパティテストを追加する
  - [x] 15.1 **Property 7: SoundInterestNotifier が音声通知とコンソール通知の両方を実行する**
    - 様々な `InterestUpdate`（トピック名・要約のバリエーション）で `SoundNotificationService.notify()` と `ConsoleInterestNotifier.notifyUpdate()` の両方が呼ばれることを `@ParameterizedTest` で検証する
    - **Validates: 要件 7.2, 7.3**
    - タグ: `sound-notification-property-7-soundInterestNotifier`

- [x] 16. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- **標準出力のキャプチャ**: `System.setOut(new PrintStream(outputStream))` でキャプチャするか、`fallbackToConsole()` の出力先を `PrintStream` として注入可能にして差し替える
- **ProcessBuilder のモック**: `ProcessBuilder` は直接モックできないため、`ProcessBuilder` の生成を `protected` メソッドに抽出してサブクラスでオーバーライドするか、`ProcessBuilderFactory` インターフェースを導入して差し替える
- プロパティテストのタグ名は英数字のみ（JUnit 6 のタグ制約に準拠）
