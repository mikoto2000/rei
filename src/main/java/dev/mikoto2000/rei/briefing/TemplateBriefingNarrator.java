package dev.mikoto2000.rei.briefing;

import java.util.List;

final class TemplateBriefingNarrator {

  private TemplateBriefingNarrator() {
  }

  static BriefingNarration fallback(String response) {
    return new BriefingNarration(response == null ? "" : response.trim(), List.of(), List.of());
  }
}
