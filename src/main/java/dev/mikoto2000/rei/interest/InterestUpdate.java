package dev.mikoto2000.rei.interest;

import java.time.OffsetDateTime;
import java.util.List;

public record InterestUpdate(
    long id,
    String topic,
    String reason,
    String searchQuery,
    String summary,
    List<String> sourceUrls,
    OffsetDateTime createdAt) {
}
