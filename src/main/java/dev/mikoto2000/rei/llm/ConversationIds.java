package dev.mikoto2000.rei.llm;

/**
 * 用途ごとの conversation ID を生成する共通ヘルパー。
 *
 * <p>Spring AI の ChatMemory は conversation ID をキーとして履歴を管理するため、
 * 用途ごとに prefix を分離して混在を防ぐ。同じ論理会話には常に同じ ID を返す。</p>
 */
public final class ConversationIds {

  public static final String CHAT_PREFIX = "chat:";
  public static final String BLUESKY_REPLY_PREFIX = "bluesky-reply:";
  public static final String BLUESKY_MANUAL_PREFIX = "bluesky-manual:";
  public static final String TOOL_PREFIX = "tool:";

  private ConversationIds() {
  }

  /**
   * 通常チャット用の conversation ID。
   *
   * <p>単一の通常チャットしか存在しない設計のため {@code chat:main} を返す。</p>
   */
  public static String chat() {
    return CHAT_PREFIX + "main";
  }

  /**
   * 通常チャット用の conversation ID（既存 conversation ID を保持する場合）。
   */
  public static String chat(String id) {
    return CHAT_PREFIX + requireNonBlank(id, "chat");
  }

  /**
   * Bluesky 自動返信・手動返信（handle が取得できる場合）用の conversation ID。
   */
  public static String blueskyReply(String handle) {
    return BLUESKY_REPLY_PREFIX + requireNonBlank(handle, "bluesky-reply");
  }

  /**
   * Bluesky 手動返信（handle が取得できない場合）用の conversation ID。
   */
  public static String blueskyManual(String identifier) {
    return BLUESKY_MANUAL_PREFIX + requireNonBlank(identifier, "bluesky-manual");
  }

  /**
   * ツール経由の LLM 会話用の conversation ID。
   */
  public static String tool(String toolName) {
    return TOOL_PREFIX + requireNonBlank(toolName, "tool");
  }

  private static String requireNonBlank(String value, String fallback) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fallback + " conversation id must not be blank");
    }
    return value.strip();
  }
}
