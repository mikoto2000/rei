package dev.mikoto2000.rei.chatgpt;

import java.util.List;

/**
 * ChatGPTとの連携に必要なチャットGPTモデル選択肢を扱う。
 *
 * <p>Bounded administrator-owned model IDs shared by guidance, schemas and argument correction.
 */
public final class ChatGptModelChoices {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ChatGptModelChoices() {}

  /** ChatGPTとの連携で用いるモデル一覧を返す。 */
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
