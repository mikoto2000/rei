package dev.mikoto2000.rei.application.state;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.feed.DuplicateFeedException;
import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.interest.InterestUpdate;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.reminder.Reminder;
import dev.mikoto2000.rei.reminder.ReminderService;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * 状態変更の調整で受け渡すStateful操作サービスを表す。
 *
 * <p>Validated Web admission over shared domain services. Existing Shell behavior remains in those
 * services.
 *
 * @param feeds フィード一覧
 * @param reminders リマインダー一覧
 * @param interests 関心一覧
 * @param memories 記憶一覧
 * @param skills スキル一覧
 */
@Service
public record StatefulOperationService(
    FeedService feeds,
    ReminderService reminders,
    InterestUpdateService interests,
    MemoryService memories,
    AgentSkillRepository skills) {
  /** 新たな対象を利用できるよう、フィードを作成する。 */
  public Feed createFeed(String url, String displayName) {
    url(url);
    optionalText(displayName, 200);
    try {
      return feeds.add(url, displayName);
    } catch (DuplicateFeedException | org.springframework.dao.DuplicateKeyException error) {
      throw new OperationConflictException();
    }
  }

  /** 最新の内容を後続の処理に反映するため、フィードを更新する。 */
  public Feed updateFeed(long id, String displayName, Boolean enabled) {
    positiveId(id);
    optionalText(displayName, 200);
    if (displayName == null && enabled == null) throw new IllegalArgumentException("Empty update");
    try {
      return feeds.update(id, displayName, enabled);
    } catch (EmptyResultDataAccessException error) {
      throw new ResourceNotFoundException("Feed");
    }
  }

  /** 対象を管理から外すため、フィードを削除する。 */
  public void deleteFeed(long id) {
    positiveId(id);
    feeds.delete(id);
  }

  /** 新たな対象を利用できるよう、リマインダーを作成する。 */
  public Reminder createReminder(String message, String at, String target, Integer minutesBefore) {
    text(message, 2000);
    if (at != null && target == null && minutesBefore == null)
      return reminders.addAt(message, OffsetDateTime.parse(at));
    if (at == null
        && target != null
        && minutesBefore != null
        && minutesBefore >= 0
        && minutesBefore <= 525600)
      return reminders.addBefore(message, OffsetDateTime.parse(target), minutesBefore);
    throw new IllegalArgumentException("Specify at or target/minutesBefore");
  }

  /** 状態変更の調整で用いるリマインダー一覧一覧を返す。 */
  public List<Reminder> remindersList() {
    return reminders.listActive();
  }

  /** 状態変更の調整で用いるリマインダーを返す。 */
  public Reminder reminder(long id) {
    positiveId(id);
    try {
      return reminders.findById(id);
    } catch (EmptyResultDataAccessException error) {
      throw new ResourceNotFoundException("Reminder");
    }
  }

  /** 対象を管理から外すため、リマインダーを削除する。 */
  public void deleteReminder(long id) {
    positiveId(id);
    reminders.delete(id);
  }

  /** 状態変更の調整で用いる関心一覧一覧を返す。 */
  public List<InterestUpdate> interestsList(int hours) {
    if (hours < 1 || hours > 8760) throw new IllegalArgumentException("Invalid hours");
    return interests.listRecent(hours);
  }

  /** 新たな対象を利用できるよう、関心を作成する。 */
  public InterestUpdate createInterest(
      String topic, String reason, String query, String summary, List<String> urls) {
    text(topic, 200);
    text(reason, 2000);
    text(query, 2000);
    text(summary, 10000);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (urls == null || urls.size() > 20) throw new IllegalArgumentException("Invalid sources");
    urls.forEach(StatefulOperationService::url);
    synchronized (interests) {
      if (interests.existsBySearchQuery(query)) throw new OperationConflictException();
      return interests.save(topic, reason, query, summary, List.copyOf(urls));
    }
  }

  /** 状態変更の調整で用いる記憶一覧一覧を返す。 */
  public List<Memory> memoriesList() {
    return memories.listActive().stream().filter(StatefulOperationService::unscoped).toList();
  }

  /** 状態変更の調整で用いる記憶を返す。 */
  public Memory memory(String id) {
    text(id, 128);
    return memories
        .findById(id)
        .filter(StatefulOperationService::unscoped)
        .orElseThrow(() -> new ResourceNotFoundException("Memory"));
  }

  /** 新たな対象を利用できるよう、記憶を作成する。 */
  public Memory createMemory(String content, String type, String scope, Double confidence) {
    text(content, 20000);
    text(type, 64);
    text(scope, 64);
    var memoryType = MemoryType.valueOf(type);
    var memoryScope = MemoryScope.valueOf(scope);
    // Legacy Memory has no project/session ownership columns. Do not publish contextual memories
    // without verifiable ownership.
    if (memoryScope == MemoryScope.PROJECT || memoryScope == MemoryScope.SESSION)
      throw new IllegalArgumentException("Unsupported contextual scope");
    double score = confidence == null ? 0.8 : confidence;
    if (!Double.isFinite(score) || score < 0 || score > 1)
      throw new IllegalArgumentException("Invalid confidence");
    return memories.save(
        new Memory(
            null, content, memoryType, memoryScope, MemoryStatus.ACTIVE, score, null, null, null));
  }

  /** 対象を管理から外すため、記憶を削除する。 */
  public void deleteMemory(String id) {
    var current = memory(id);
    if (current.status() != MemoryStatus.DELETED) memories.updateStatus(id, MemoryStatus.DELETED);
  }

  /** 変更された設定や記録を反映するため、スキル一覧を読み直す。 */
  public int reloadSkills() {
    return new SkillReloadService(skills).reload();
  }

  /** 状態変更の調整において、unscopedに該当するか判定する。 */
  private static boolean unscoped(Memory memory) {
    return memory.scope() != MemoryScope.PROJECT && memory.scope() != MemoryScope.SESSION;
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static void text(String text, int max) {
    if (text == null || text.isBlank() || text.length() > max)
      throw new IllegalArgumentException("Invalid input");
  }

  /** 状態変更の調整に関する任意テキストを処理する。 */
  private static void optionalText(String value, int max) {
    if (value != null) text(value, max);
  }

  /** 状態変更の調整に関する正の識別子を処理する。 */
  private static void positiveId(long id) {
    if (id < 1) throw new IllegalArgumentException("Invalid id");
  }

  /** 状態変更の調整に関する参照先のURLを処理する。 */
  private static void url(String value) {
    text(value, 4096);
    var uri = URI.create(value);
    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid URL");
  }
}
