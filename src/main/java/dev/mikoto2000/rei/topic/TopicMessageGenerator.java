package dev.mikoto2000.rei.topic;

public interface TopicMessageGenerator {
  String generate(TopicCandidate candidate, TopicMessageContext context);
}
