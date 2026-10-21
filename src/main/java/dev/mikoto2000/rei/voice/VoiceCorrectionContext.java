package dev.mikoto2000.rei.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 音声による対話に必要な音声補正文脈を扱う。
 *
 * <p>Bounded reference data captured before queueing. Never reads tools, long-term memory or
 * project files.
 */
public final class VoiceCorrectionContext {
  /**
   * 音声による対話で受け渡すTermを表す。
   *
   * @param canonical 正規
   * @param reading 読み取りの状態
   * @param aliases 別名一覧
   * @param description 説明
   */
  public record Term(String canonical, String reading, List<String> aliases, String description) {}

  /**
   * 音声による対話で受け渡す状態の記録を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param projectName プロジェクト名前
   * @param history 履歴
   * @param terms 検索語句一覧
   */
  public record Snapshot(
      String projectId, String projectName, List<String> history, List<Term> terms) {}

  /** 履歴。 */
  private final ConversationLogStore history;

  /** データ。 */
  private final Path data;

  /** 上限一覧。 */
  private final VoiceCorrectionProperties limits;

  /** 指定された値や利用先を使って、VoiceCorrectionContextを初期化する。 */
  public VoiceCorrectionContext(
      ConversationLogStore history, Path data, VoiceCorrectionProperties limits) {
    this.history = history;
    this.data = data;
    this.limits = limits;
  }

  /** 後から状況を確認できるよう、音声補正文脈を記録する。 */
  public Snapshot capture(ConversationInput input) {
    var target = input.target();
    var recent = new ArrayList<String>();
    int remaining = limits.getMaxContextChars();
    if (history != null && remaining > 0) {
      var entries = history.recentConversation(target.project().id(), target.sessionId(), 4);
      for (var entry : entries.reversed()) {
        if (!Set.of("user", "assistant").contains(entry.speaker())) continue;
        String safe =
            clip(CredentialRedactor.redact(entry.speaker() + ": " + entry.content()), remaining);
        if (!safe.isEmpty()) recent.addFirst(safe);
        remaining -= safe.codePointCount(0, safe.length());
        if (remaining <= 0) break;
      }
    }
    Path base = data == null ? ReiDataDirectory.current() : data;
    Path project =
        data == null
            ? ProjectStorage.directory(target.project().id())
            : base.resolve("projects").resolve(target.project().id());
    return new Snapshot(
        target.project().id(),
        clip(CredentialRedactor.redact(target.project().name()), 80),
        List.copyOf(recent),
        dictionaries(
            base.resolve("voice/terms.json"),
            project.resolve("voice/terms.json"),
            limits.getMaxDictionaryEntries()));
  }

  /** 音声による対話で用いるdictionariesを返す。 */
  static List<Term> dictionaries(Path common, Path project, int max) {
    var merged = new LinkedHashMap<String, Term>();
    // Project entries precede common entries and override by canonical spelling or reading/alias
    // collision.
    var occupied = new HashSet<String>();
    for (Path file : List.of(project, common))
      for (Term term : read(file, max)) {
        var keys = new HashSet<String>(term.aliases());
        keys.add(term.canonical());
        if (!term.reading().isBlank()) keys.add(term.reading());
        if (keys.stream().anyMatch(occupied::contains)) continue;
        if (merged.size() >= max) break;
        merged.put(term.canonical(), term);
        occupied.addAll(keys);
      }
    return List.copyOf(merged.values());
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private static List<Term> read(Path file, int max) {
    var result = new ArrayList<Term>();
    try {
      if (max == 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
      byte[] bytes;
      try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = stream.readNBytes(65537);
      }
      if (bytes.length > 65536) return List.of();
      var array = new ObjectMapper().readTree(bytes);
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (!array.isArray() || array.size() > 128) return List.of();
      for (var item : array) {
        if (!item.isObject() || !item.path("canonical").isTextual()) continue;
        String canonical = safe(item.path("canonical").asText(), 80);
        if (canonical.isBlank()) continue;
        var aliases = new ArrayList<String>();
        var values = item.path("aliases");
        if (values.isArray())
          for (var alias : values) {
            if (aliases.size() >= 8) break;
            if (alias.isTextual()) aliases.add(safe(alias.asText(), 80));
          }
        result.add(
            new Term(
                canonical,
                safe(item.path("reading").asText(""), 80),
                List.copyOf(aliases),
                safe(item.path("description").asText(""), 160)));
        if (result.size() >= max) break;
      }
    } catch (Exception ignored) {
      return List.of();
    } // Fixed diagnostics in the caller; no filenames or contents in logs.
    return List.copyOf(result);
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  private static String safe(String text, int max) {
    return clip(CredentialRedactor.redact(text), max);
  }

  /** 利用先の許容量を超えないよう、音声補正文脈を上限内に収める。 */
  static String clip(String text, int max) {
    int count = text.codePointCount(0, text.length());
    return count <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
  }
}
