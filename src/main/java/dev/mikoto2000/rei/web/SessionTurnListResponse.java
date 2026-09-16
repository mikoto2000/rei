package dev.mikoto2000.rei.web;

import java.util.List;

public record SessionTurnListResponse(String sessionId, List<SessionTurnResponse> items, String nextCursor) {}
