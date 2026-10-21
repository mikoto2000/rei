package dev.mikoto2000.rei.topic;

/** 話題生成文脈を用途や設定に応じて提供する窓口。 */
public interface TopicGenerationContextProvider {
  /** 話題の管理で用いる現在文脈を返す。 */
  TopicGenerationContext currentContext();
}
