package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record WorkingSetTopicItem(String identifier, String kind, Instant lastAccessedAt) {
}
