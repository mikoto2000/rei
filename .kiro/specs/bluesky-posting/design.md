# 設計書: Bluesky 投稿機能

## 概要

本機能は `BlueskyPostTools` から `BlueskyPostService` を呼び出し、Bluesky へテキスト投稿を行う。  
`rei.bluesky.enabled` による有効/無効制御、認証情報の利用、投稿本文バリデーション、例外処理とログ出力を提供する。

## 設計方針

- 投稿処理は `BlueskyPostService` に集約し、ツール層は委譲に徹する。
- 設定値は `BlueskyProperties` に集約し、外部依存を最小化する。
- バリデーションは API 呼び出し前に実施し、失敗理由を明確に返す。
- ログには機密情報（アプリパスワード、アクセストークン）を含めない。

---

## コンポーネント構成

- **BlueskyProperties**
  - `rei.bluesky` 配下の設定値を保持する。
  - 主な設定: `enabled`, `handle`, `appPassword`, `maxPostLength`

- **BlueskyPostService**
  - 投稿可否判定（enabled / 認証情報 / 本文）を行う。
  - Bluesky API 認証・投稿呼び出しを行う。
  - 結果を `BlueskyPostResult` で返す。

- **BlueskyPostTools**
  - AI ツールの入口。
  - `post(String text)` を公開し、`BlueskyPostService` に委譲する。

---

## クラス設計

### BlueskyProperties

```java
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.bluesky")
public class BlueskyProperties {
  private boolean enabled = false;
  private String handle = "";
  private String appPassword = "";
  private int maxPostLength = 300;
}
```

### BlueskyPostResult

```java
public record BlueskyPostResult(
    boolean success,
    String message,
    String postUri,
    String postUrl
) {}
```

### BlueskyPostService

```java
@Service
@RequiredArgsConstructor
public class BlueskyPostService {
  private final BlueskyProperties properties;

  public BlueskyPostResult post(String text) { ... }
}
```

### BlueskyPostTools

```java
@Component
@RequiredArgsConstructor
public class BlueskyPostTools {
  private final BlueskyPostService blueskyPostService;

  @Tool(name = "blueskyPost", description = "Blueskyへ投稿します")
  public String post(String text) { ... }
}
```

---

## 処理フロー

```mermaid
flowchart TD
    A[BlueskyPostTools.post(text)] --> B[BlueskyPostService.post(text)]
    B --> C{enabled=true?}
    C -- No --> R1[失敗結果: disabled]
    C -- Yes --> D{handle/appPassword 設定あり?}
    D -- No --> R2[失敗結果: auth config missing]
    D -- Yes --> E{text が空/空白?}
    E -- Yes --> R3[失敗結果: validation error]
    E -- No --> F{text length <= maxPostLength?}
    F -- No --> R4[失敗結果: too long]
    F -- Yes --> G[Bluesky API 認証]
    G --> H{認証成功?}
    H -- No --> R5[失敗結果: auth failed]
    H -- Yes --> I[投稿API実行]
    I --> J{投稿成功?}
    J -- Yes --> S1[成功結果: postUri/postUrl]
    J -- No --> R6[失敗結果: post failed]
    G -.例外.-> X[例外補足・warnログ]
    I -.例外.-> X
    X --> R7[失敗結果: exception]
```

---

## 要件トレーサビリティ

- **要件1（有効/無効制御）**
  - `BlueskyPostService.post()` 冒頭で `properties.enabled` を判定。

- **要件2（認証情報による投稿）**
  - `handle/appPassword` の存在確認。
  - 認証 API 呼び出し、失敗時の明示的エラー返却。

- **要件3（投稿本文バリデーション）**
  - `isBlank` 判定。
  - `maxPostLength` 超過判定。

- **要件4（AI ツールからの投稿）**
  - `BlueskyPostTools.post()` からサービスへ委譲し、成功/失敗結果を文字列化して返却。

- **要件5（例外処理とログ）**
  - API 呼び出しを `try-catch` で保護。
  - `warn` ログへ原因分類を出力（機密情報は非出力）。

---

## 設定

```yaml
rei:
  bluesky:
    enabled: false
    handle: ${REI_BLUESKY_HANDLE:}
    app-password: ${REI_BLUESKY_APP_PASSWORD:}
    max-post-length: ${REI_BLUESKY_MAX_POST_LENGTH:300}
```

---

## エラーメッセージ設計

- `Bluesky posting is disabled`
- `Bluesky credentials are not configured`
- `Post text must not be blank`
- `Post text exceeds max length: {max}`
- `Bluesky authentication failed`
- `Bluesky post failed`
- `Bluesky post failed due to unexpected error`
