package dev.mikoto2000.rei.topic;

public class TemplateTopicMessageGenerator implements TopicMessageGenerator {
  @Override
  public String generate(TopicCandidate candidate, TopicMessageContext context) {
    String prefix = switch (candidate.type()) {
      case FOLLOW_UP -> "先ほどの件で、";
      case DISCOVERY -> "関連しそうな更新として、";
      default -> "一点、";
    };
    return prefix + candidate.topic() + " はまだ確認余地があります。見てみますか？";
  }
}
