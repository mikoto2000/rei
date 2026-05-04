# 設計書: 音声通知機能

## 概要

本機能は、AI エージェント「rei」に音声通知機能を追加する。
`SoundNotificationTools` が AI エージェントのツールとして登録され、エージェントが `notify(message)` を呼び出すと `SoundNotificationService` が外部プログラムを実行して音声通知を行う。
外部プログラムが未設定・無効・実行失敗・タイムアウトの場合は、標準出力への通知にフォールバックする。

### 設計方針

- 既存コードのパターン（`@ConfigurationProperties` + Lombok、`@Component` + `@Tool`）に準拠する
- パッケージは `dev.mikoto2000.rei.sound` とする
- 外部プログラムの実行には `ProcessBuilder` を使用する
- フォールバック時は必ず warn ログを出力し、標準出力に通知メッセージを出力する

---

## アーキテクチャ

```mermaid
graph TD
    AI[AI エージェント] -->|notify(message)| SoundNotificationTools
    SoundNotificationTools -->|notify(message)| SoundNotificationService
    SoundNotificationService -->|enabled=false| Fallback[標準出力フォールバック]
    SoundNotificationService -->|command 空| Fallback
    SoundNotificationService -->|ProcessBuilder| ExternalProgram[外部プログラム]
    ExternalProgram -->|タイムアウト / 失敗| Fallback
    ExternalProgram -->|成功| Done[通知完了]
    SoundNotificationProperties -->|設定値提供| SoundNotificationService
    AiConfiguration -->|Bean 登録| SoundNotificationTools

    InterestNotificationJob -->|notifyUpdate| SoundInterestNotifier
    SoundInterestNotifier -->|notify(topic + summary)| SoundNotificationService
    SoundInterestNotifier -->|notifyUpdate| ConsoleInterestNotifier
```

---

## コンポーネントとインターフェース

### SoundNotificationProperties

Spring Boot の `@ConfigurationProperties` を使用して `rei.sound-notification` 配下の設定値を保持する。

```java
package dev.mikoto2000.rei.sound;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.sound-notification")
public class SoundNotificationProperties {

    /** 音声通知の有効/無効。デフォルト: false */
    private boolean enabled = false;

    /** 実行するコマンドとその引数のリスト。デフォルト: 空リスト */
    private List<String> command = new ArrayList<>();
}
```

**設定例（application.yml）:**

```yaml
rei:
  sound-notification:
    enabled: true
    command:
      - say
      - "{{MESSAGE}}"
```

---

### SoundNotificationService

音声通知の実行ロジックを担うサービスクラス。

```java
package dev.mikoto2000.rei.sound;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoundNotificationService {

    private final SoundNotificationProperties properties;

    /**
     * 通知メッセージを使って音声通知を実行する。
     * 失敗時は標準出力にフォールバックする。
     *
     * @param message 通知メッセージ
     */
    public void notify(String message) { ... }

    /** 標準出力フォールバック */
    private void fallbackToConsole(String message) { ... }
}
```

**メソッドシグネチャ一覧:**

| メソッド | 引数 | 戻り値 | 説明 |
|---|---|---|---|
| `notify(String message)` | message: 通知メッセージ | void | 音声通知を実行する。失敗時は標準出力にフォールバック |
| `fallbackToConsole(String message)` | message: 通知メッセージ | void | 標準出力に通知メッセージを出力する（private） |

---

### SoundNotificationTools

AI エージェントから音声通知を呼び出すためのツールクラス。

```java
package dev.mikoto2000.rei.sound;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SoundNotificationTools {

    private final SoundNotificationService soundNotificationService;

    /**
     * AI エージェントから音声通知を実行するツール。
     *
     * @param message 通知メッセージ
     * @return 通知の成否を示す文字列
     */
    @Tool(name = "soundNotify", description = "音声通知を実行します。重要な通知をユーザーに音声で伝えたいときに使用します。")
    public String notify(String message) { ... }
}
```

**メソッドシグネチャ一覧:**

| メソッド | 引数 | 戻り値 | 説明 |
|---|---|---|---|
| `notify(String message)` | message: 通知メッセージ | String | 通知を実行し、成否を文字列で返す |

---

### AiConfiguration への登録

`AiConfiguration` に `SoundNotificationProperties` と `SoundNotificationTools` を追加する。

```java
// @EnableConfigurationProperties に SoundNotificationProperties を追加
@EnableConfigurationProperties({
    CoreProperties.class,
    // ... 既存 ...
    SoundNotificationProperties.class   // 追加
})

// フィールドに SoundNotificationTools を追加
private final SoundNotificationTools soundNotificationTools;

// chatClient() の defaultTools に追加
.defaultTools(tools, googleCalendarTools, taskTools, briefingTools,
              feedTools, reminderTools, searchTools, webSearchTools,
              soundNotificationTools);  // 追加
```

---

### SoundInterestNotifier

`InterestNotifier` を実装し、興味関心更新情報を音声通知で伝えるクラス。
`@Primary` として登録することで `ConsoleInterestNotifier` より優先して使用される。
音声通知後に `ConsoleInterestNotifier` にも委譲してコンソール出力を維持する。

```java
package dev.mikoto2000.rei.sound;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.interest.ConsoleInterestNotifier;
import dev.mikoto2000.rei.interest.InterestNotifier;
import dev.mikoto2000.rei.interest.InterestUpdate;
import lombok.RequiredArgsConstructor;

@Primary
@Component
@RequiredArgsConstructor
public class SoundInterestNotifier implements InterestNotifier {

    private final SoundNotificationService soundNotificationService;
    private final ConsoleInterestNotifier consoleInterestNotifier;

    @Override
    public void notifyUpdate(InterestUpdate update) {
        String message = update.topic() + " " + update.summary();
        soundNotificationService.notify(message);
        consoleInterestNotifier.notifyUpdate(update);
    }
}
```

**メソッドシグネチャ一覧:**

| メソッド | 引数 | 戻り値 | 説明 |
|---|---|---|---|
| `notifyUpdate(InterestUpdate update)` | update: 興味関心更新情報 | void | 音声通知後にコンソールにも出力する |

**設計上の注意:**
- `@Primary` を付与することで Spring が `InterestNotifier` の注入先として `SoundInterestNotifier` を優先する
- `ConsoleInterestNotifier` は `@Primary` なしで残し、`SoundInterestNotifier` から委譲先として使用する
- 音声通知の成否に関わらず（`SoundNotificationService` が内部でフォールバック処理するため）コンソール出力は常に実行される

---

## データモデル

本機能は永続化データを持たない。設定値は `SoundNotificationProperties` が保持し、通知メッセージは `notify()` の引数として渡される。

### 設定プロパティ

| プロパティキー | 型 | デフォルト値 | 説明 |
|---|---|---|---|
| `rei.sound-notification.enabled` | `boolean` | `false` | 音声通知の有効/無効 |
| `rei.sound-notification.command` | `List<String>` | `[]` | 実行するコマンドと引数のリスト |

### プレースホルダー

| プレースホルダー | 置換内容 |
|---|---|
| `{{MESSAGE}}` | `notify()` に渡された通知メッセージ文字列 |

---

## notify() 処理フロー

```mermaid
flowchart TD
    Start([notify 呼び出し]) --> CheckEnabled{enabled = true?}
    CheckEnabled -- No --> WarnDisabled[warn ログ: 無効化されています]
    WarnDisabled --> FallbackConsole1[標準出力に通知]
    FallbackConsole1 --> End([終了])

    CheckEnabled -- Yes --> CheckCommand{command が空?}
    CheckCommand -- Yes --> WarnNoCommand[warn ログ: コマンド未設定]
    WarnNoCommand --> FallbackConsole2[標準出力に通知]
    FallbackConsole2 --> End

    CheckCommand -- No --> CheckPlaceholder{command に\n{{MESSAGE}} が含まれる?}
    CheckPlaceholder -- No --> WarnNoPlaceholder[warn ログ: {{MESSAGE}} なし]
    WarnNoPlaceholder --> BuildCommand[コマンドをそのまま使用]
    CheckPlaceholder -- Yes --> ReplaceMessage[{{MESSAGE}} をメッセージに置換]
    ReplaceMessage --> BuildCommand

    BuildCommand --> Execute[ProcessBuilder でコマンド実行]
    Execute --> WaitResult{5 分以内に完了?}
    WaitResult -- No --> ForceKill[プロセス強制終了]
    ForceKill --> WarnTimeout[warn ログ: タイムアウト]
    WarnTimeout --> FallbackConsole3[標準出力に通知]
    FallbackConsole3 --> End

    WaitResult -- Yes --> CheckExitCode{終了コード = 0?}
    CheckExitCode -- No --> WarnFailed[warn ログ: 非ゼロ終了コード]
    WarnFailed --> FallbackConsole4[標準出力に通知]
    FallbackConsole4 --> End

    CheckExitCode -- Yes --> Success([通知完了])
```

---

## 正確性プロパティ

*プロパティとは、システムのすべての有効な実行において成立すべき特性または振る舞いのことです。プロパティは人間が読める仕様と機械で検証可能な正確性保証の橋渡しをします。*

### プロパティ 1: enabled=false のとき標準出力にフォールバックする

*任意の* メッセージ文字列に対して、`enabled=false` の設定で `notify()` を呼び出したとき、外部プログラムは実行されず、標準出力に通知メッセージが出力される。

**Validates: 要件 1.2**

---

### プロパティ 2: command が空のとき標準出力にフォールバックする

*任意の* メッセージ文字列に対して、`enabled=true` かつ `command=空リスト` の設定で `notify()` を呼び出したとき、外部プログラムは実行されず、標準出力に通知メッセージが出力される。

**Validates: 要件 2.1**

---

### プロパティ 3: {{MESSAGE}} プレースホルダーが正しく置換される

*任意の* メッセージ文字列と `{{MESSAGE}}` を含む *任意の* コマンドリストに対して、置換後のコマンドリストの各要素に元のメッセージが含まれ、`{{MESSAGE}}` という文字列が残っていない。

**Validates: 要件 3.1**

---

### プロパティ 4: 外部プログラム実行失敗時に標準出力にフォールバックする

*任意の* メッセージ文字列に対して、外部プログラムが非ゼロ終了コードで終了したとき、標準出力に通知メッセージが出力される。

**Validates: 要件 4.1**

---

### プロパティ 5: タイムアウト時に標準出力にフォールバックする

*任意の* メッセージ文字列に対して、外部プログラムがタイムアウトしたとき、プロセスが強制終了され、標準出力に通知メッセージが出力される。

**Validates: 要件 5.1**

---

### プロパティ 6: SoundNotificationTools が SoundNotificationService に委譲し結果を返す

*任意の* メッセージ文字列に対して、`SoundNotificationTools.notify()` を呼び出したとき、`SoundNotificationService.notify()` が同じメッセージで呼び出され、null でない文字列が返される。

**Validates: 要件 6.1, 6.2**

---

### プロパティ 7: SoundInterestNotifier が音声通知とコンソール通知の両方を実行する

*任意の* `InterestUpdate` に対して、`SoundInterestNotifier.notifyUpdate()` を呼び出したとき、`SoundNotificationService.notify()` がトピック名と要約を結合したメッセージで呼び出され、かつ `ConsoleInterestNotifier.notifyUpdate()` も同じ `InterestUpdate` で呼び出される。

**Validates: 要件 7.2, 7.3**

---

## エラーハンドリング

| 状況 | 対応 | ログレベル |
|---|---|---|
| `enabled=false` | 標準出力フォールバック | WARN |
| `command` が空リスト | 標準出力フォールバック | WARN |
| `{{MESSAGE}}` がコマンドに含まれない | warn ログ出力後、そのままコマンド実行 | WARN |
| 外部プログラムが例外をスロー | 標準出力フォールバック | WARN |
| 外部プログラムが非ゼロ終了コードで終了 | 標準出力フォールバック | WARN |
| 外部プログラムが 5 分以内に完了しない | プロセス強制終了 + 標準出力フォールバック | WARN |

**設計方針:**
- すべてのフォールバックは `fallbackToConsole()` に集約し、重複コードを排除する
- 例外は `SoundNotificationService` 内で捕捉し、呼び出し元（`SoundNotificationTools`）には伝播させない
- `SoundNotificationTools.notify()` は常に文字列を返し、例外をスローしない

---

## テスト戦略

### 単体テスト（例ベース）

`SoundNotificationServiceTest` および `SoundNotificationToolsTest` として実装する。

- `enabled=false` のとき標準出力フォールバックが呼ばれること
- `command` が空のとき標準出力フォールバックが呼ばれること
- `{{MESSAGE}}` がないとき warn ログが出力されてコマンドが実行されること
- 外部プログラムが非ゼロ終了コードのとき標準出力フォールバックが呼ばれること
- タイムアウト時にプロセスが強制終了されて標準出力フォールバックが呼ばれること
- `SoundNotificationTools.notify()` が成功・失敗どちらでも null でない文字列を返すこと

### プロパティベーステスト（PBT）

Spring Boot 4.0.x では jqwik が JUnit 6 と互換性がないため、JUnit 6 の `@ParameterizedTest` + `@MethodSource` を使用してプロパティテストに相当するテストを実装する。
各プロパティテストは複数の代表的な入力パターン（最低 10 パターン）で実行する。

`SoundNotificationServicePropertyTest` として実装する。

| テスト | 対応プロパティ | タグ |
|---|---|---|
| 様々なメッセージで `enabled=false` のとき標準出力フォールバック | プロパティ 1 | `sound-notification-property-1-enabledFalse` |
| 様々なメッセージで `command` 空のとき標準出力フォールバック | プロパティ 2 | `sound-notification-property-2-emptyCommand` |
| 様々なメッセージと様々なコマンドで `{{MESSAGE}}` が正しく置換される | プロパティ 3 | `sound-notification-property-3-messagePlaceholder` |
| 様々なメッセージで外部プログラム失敗時に標準出力フォールバック | プロパティ 4 | `sound-notification-property-4-execFailure` |
| 様々なメッセージで `SoundNotificationTools` が委譲し結果を返す | プロパティ 6 | `sound-notification-property-6-toolsDelegation` |

**テスト実装上の注意:**
- `@ParameterizedTest` の入力は `@MethodSource` で `Stream<String>` または `Stream<Arguments>` として定義する
- `ProcessBuilder` の呼び出しはモック（`Mockito`）で差し替えてテストする
- タイムアウトテストでは実際に 30 秒待機せず、タイムアウト値を短く設定するか `Process` をモックする
- 標準出力の検証には `System.setOut()` でキャプチャするか、出力先を抽象化して差し替え可能にする
- プロパティ 5（タイムアウト）は `@ParameterizedTest` ではなく通常の `@Test` で実装する（タイムアウト制御が複雑なため）
