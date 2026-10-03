package dev.mikoto2000.rei.web;
import java.util.Map;
import java.util.stream.Collectors;
public record ProfileResponse(int total, String first, String last, Map<String, Long> countsByType, Map<String, DurationResponse> durationsByType) {
  public record DurationResponse(long count, long totalMillis, long minMillis, long maxMillis, long averageMillis) {}
  public static ProfileResponse from(dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileSummary p) {
    return new ProfileResponse(p.total(), p.first() == null ? null : p.first().toString(), p.last() == null ? null : p.last().toString(), p.countsByType(),
        p.durationsByType().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
          var d = e.getValue(); return new DurationResponse(d.count(), d.totalMillis(), d.minMillis(), d.maxMillis(), d.averageMillis());
        })));
  }
}
