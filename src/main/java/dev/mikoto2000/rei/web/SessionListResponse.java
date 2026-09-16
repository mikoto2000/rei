package dev.mikoto2000.rei.web;

import java.util.List;

public record SessionListResponse(List<SessionResponse> items, String nextCursor) {}
