package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Task(
    long id,
    String title,
    LocalDate dueDate,
    int priority,
    TaskStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt) {}
