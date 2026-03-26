package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record Task(
    long id,
    String title,
    LocalDate dueDate,
    int priority,
    TaskStatus status,
    List<String> tags,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt
) {}
