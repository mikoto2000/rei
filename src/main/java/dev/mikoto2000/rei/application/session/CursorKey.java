package dev.mikoto2000.rei.application.session;

import java.time.Instant;

public record CursorKey(Instant time, String id) {}
