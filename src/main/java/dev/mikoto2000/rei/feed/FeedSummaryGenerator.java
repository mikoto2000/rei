package dev.mikoto2000.rei.feed;

/** フィード要約の内容を生成する。 */
@FunctionalInterface
public interface FeedSummaryGenerator {
  /** 指定された条件に従って、依頼された内容を生成する。 */
  String generate(String prompt);
}
