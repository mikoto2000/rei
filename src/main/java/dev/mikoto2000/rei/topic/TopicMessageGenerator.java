package dev.mikoto2000.rei.topic;

/** 話題メッセージの内容を生成する。 */
public interface TopicMessageGenerator {
  /** 指定された条件に従って、依頼された内容を生成する。 */
  String generate(TopicCandidate candidate, TopicMessageContext context);
}
