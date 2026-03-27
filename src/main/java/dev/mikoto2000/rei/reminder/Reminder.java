package dev.mikoto2000.rei.reminder;

import java.time.OffsetDateTime;

public record Reminder(
    long id,
    String message,
    ReminderType type,
    OffsetDateTime remindAt,
    OffsetDateTime targetAt,
    Integer minutesBefore,
    boolean notified,
    OffsetDateTime createdAt,
    OffsetDateTime notifiedAt
) {}
