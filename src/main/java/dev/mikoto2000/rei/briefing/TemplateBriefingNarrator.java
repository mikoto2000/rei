package dev.mikoto2000.rei.briefing;

import java.util.List;

/** 日次情報の案内に必要な雛形日次案内読み上げの案内を扱う。 */
final class TemplateBriefingNarrator {

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private TemplateBriefingNarrator() {}

  /** 日次情報の案内で用いる代替手段を返す。 */
  static BriefingNarration fallback(String response) {
    return new BriefingNarration(response == null ? "" : response.trim(), List.of(), List.of());
  }
}
