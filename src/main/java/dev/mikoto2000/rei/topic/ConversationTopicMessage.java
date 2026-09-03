package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record ConversationTopicMessage(String role, String content, Instant timestamp) {
}
