package dev.mikoto2000.rei.task;

import java.time.LocalDate;

public record TaskQuery(
    Integer priority,
    String tag,
    LocalDate dueBefore
) {}
