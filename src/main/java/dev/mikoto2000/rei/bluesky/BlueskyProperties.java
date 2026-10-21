package dev.mikoto2000.rei.bluesky;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Blueskyの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.bluesky")
public class BlueskyProperties {

  /** 有効状態。 */
  private boolean enabled = false;

  /** 操作対象。 */
  private String handle = "";

  /** アプリケーションパスワード。 */
  private String appPassword = "";

  /** 最大投稿長さ。 */
  private int maxPostLength = 300;

  /** タイムアウトの秒数。 */
  private int timeoutSeconds = 30;

  /** 返信。 */
  private BlueskyReplyProperties reply = new BlueskyReplyProperties();

  /** Bluesky返信の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
  @Getter
  @Setter
  public static class BlueskyReplyProperties {
    /** 有効状態。 */
    private boolean enabled = false;

    /** dry実行。 */
    private boolean dryRun = false;

    /** 確認実行間隔秒数。 */
    private int checkIntervalSeconds = 300;

    /** 取得上限。 */
    private int fetchLimit = 30;

    /** 除外する対象Replies。 */
    private boolean excludeReplies = true;

    /** 除外する対象Reposts。 */
    private boolean excludeReposts = true;

    /** 最大投稿経過時間分数。 */
    private int maxPostAgeMinutes = 120;

    /** 生成タイムアウトの秒数。 */
    private int generationTimeoutSeconds = 1200;

    /** 利用者一覧。 */
    private List<ReplyUser> users = new ArrayList<>();
  }

  /** Blueskyとの連携に必要な返信利用者を扱う。 */
  @Getter
  @Setter
  public static class ReplyUser {
    /** 操作対象。 */
    private String handle;

    /** 対象を選ぶ確率。 */
    private double probability;

    /** 最大Repliesごとの日。 */
    private int maxRepliesPerDay = 0;
  }
}
