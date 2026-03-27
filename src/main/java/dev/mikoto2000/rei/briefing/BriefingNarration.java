package dev.mikoto2000.rei.briefing;

import java.util.List;

public record BriefingNarration(
    String overview,
    List<String> cautionPoints,
    List<String> nextActions
) {}
