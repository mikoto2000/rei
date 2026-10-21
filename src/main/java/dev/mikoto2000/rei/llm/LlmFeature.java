package dev.mikoto2000.rei.llm;

/** 用途に応じたモデル利用に必要な言語モデル機能を扱う。 */
public final class LlmFeature {
  /** 音声補正。 */
  public static final String VOICE_CORRECTION = "voice-correction";

  /** 作業状況。 */
  public static final String ACTIVITY = "activity";

  /** 作業状況作業習慣。 */
  public static final String ACTIVITY_BEHAVIOR = "activity-behavior";

  /** 画面操作利用。 */
  public static final String COMPUTER_USE = "computer-use";

  /** 画面操作利用計画の作成。 */
  public static final String COMPUTER_USE_PLANNER = "computer-use-planner";

  /** チャット。 */
  public static final String CHAT = "chat";

  /** 検索。 */
  public static final String SEARCH = "search";

  /** 記憶。 */
  public static final String MEMORY = "memory";

  /** Bluesky返信。 */
  public static final String BLUESKY_REPLY = "bluesky-reply";

  /** フィード要約。 */
  public static final String FEED_SUMMARY = "feed-summary";

  /** 日次案内。 */
  public static final String BRIEFING = "briefing";

  /** 関心利用先の検出。 */
  public static final String INTEREST_DISCOVERY = "interest-discovery";

  /** エージェントスキル一覧。 */
  public static final String AGENT_SKILLS = "agent-skills";

  /** 出力上限計画の作成。 */
  public static final String OUTPUT_LIMIT_PLANNER = "output-limit-planner";

  /** 画像プロンプト。 */
  public static final String IMAGE_PROMPT = "image-prompt";

  /** 画像生成。 */
  public static final String IMAGE_GENERATION = "image-generation";

  /** Webページ要約。 */
  public static final String WEB_PAGE_SUMMARY = "web-page-summary";

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private LlmFeature() {}
}
