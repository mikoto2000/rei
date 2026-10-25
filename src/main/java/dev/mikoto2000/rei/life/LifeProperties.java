package dev.mikoto2000.rei.life;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit YAML fields override persisted settings; absent fields retain their existing owner. */
@ConfigurationProperties(prefix = "rei.life", ignoreUnknownFields = false)
public record LifeProperties(
    String zone,
    Set<DayOfWeek> workDays,
    String workStart,
    String workEnd,
    List<LifeContext.Window> notifications,
    List<LifeContext.Window> rest,
    List<LifeContext.Window> personal,
    List<LifeContext.Background> background,
    LifeContext.ExceptionWindow exception,
    Set<String> emergencyNoticeIds) {
  public LifeProperties {
    workDays = workDays == null ? null : Set.copyOf(workDays);
    notifications = notifications == null ? null : List.copyOf(notifications);
    rest = rest == null ? null : List.copyOf(rest);
    personal = personal == null ? null : List.copyOf(personal);
    background = background == null ? null : List.copyOf(background);
    emergencyNoticeIds = emergencyNoticeIds == null ? null : Set.copyOf(emergencyNoticeIds);
  }

  static LifeProperties empty() {
    return new LifeProperties(null, null, null, null, null, null, null, null, null, null);
  }

  LifeContext.Settings apply(LifeContext.Settings saved) {
    return new LifeContext.Settings(
        saved.revision(),
        pick(zone, saved.zone()),
        pick(workDays, saved.workDays()),
        pick(workStart, saved.workStart()),
        pick(workEnd, saved.workEnd()),
        pick(notifications, saved.notifications()),
        pick(rest, saved.rest()),
        pick(personal, saved.personal()),
        pick(background, saved.background()),
        pick(exception, saved.exception()),
        pick(emergencyNoticeIds, saved.emergencyNoticeIds()));
  }

  private static <T> T pick(T configured, T saved) {
    return configured == null ? saved : configured;
  }
}
