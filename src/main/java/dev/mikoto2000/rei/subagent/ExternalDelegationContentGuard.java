package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;

/**
 * 補助エージェントへの委譲に必要な外部委譲内容利用条件の確認を扱う。
 *
 * <p>Check decoded reply/condition values so escaped JSON syntax is not mistaken for a credential.
 */
public final class ExternalDelegationContentGuard {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalDelegationContentGuard() {}

  /** 補助エージェントへの委譲において、元の内容認証情報の有無を判定する。 */
  public static boolean hasUnmaskedCredentials(String content) {
    try {
      return hasUnmaskedCredentials(new SubAgentResultParser().parse(content));
    } catch (SubAgentValidationException invalid) {
      return ExternalSourceRedactor.hasUnmaskedCredentials(content);
    }
  }

  /** 補助エージェントへの委譲において、元の内容認証情報の有無を判定する。 */
  public static boolean hasUnmaskedCredentials(JsonNode value) {
    if (value == null || value.isNull()) return false;
    if (value.isString()) return ExternalSourceRedactor.hasUnmaskedCredentials(value.asString());
    if (value.isObject()) {
      for (var entry : value.properties()) {
        if (ExternalSourceRedactor.hasUnmaskedCredentials(entry.getKey())) return true;
        var child = entry.getValue();
        if ((child.isObject() || child.isArray())
            && ExternalSourceRedactor.hasUnmaskedCredentials(entry.getKey() + "=unmasked"))
          return true;
        if (child.isValueNode() && !child.isNull()) {
          String text = child.isString() ? child.asString() : child.toString();
          if (ExternalSourceRedactor.hasUnmaskedCredentials(entry.getKey() + "=" + text))
            return true;
        }
        if (hasUnmaskedCredentials(child)) return true;
      }
    } else if (value.isArray()) {
      for (var child : value) if (hasUnmaskedCredentials(child)) return true;
    }
    return false;
  }
}
