package dev.mikoto2000.rei.chatgpt;

import java.util.List;

/** Bounded administrator-owned model IDs shared by guidance, schemas and argument correction. */
public final class ChatGptModelChoices {
  private ChatGptModelChoices() {}

  public static List<String> models(PlanDelegationProperties automatic) {
    if (automatic == null
        || !automatic.enabled()
        || automatic.allowedModels().size() > 16
        || automatic.allowedModels().stream().anyMatch(id -> !id.matches("[A-Za-z0-9_.-]{1,128}")))
      return List.of();
    // Never advertise a partial enum: it would exclude models the administrator actually permits.
    return automatic.allowedModels().stream().sorted().toList();
  }
}
