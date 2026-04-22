package dev.mikoto2000.rei.feed;

@FunctionalInterface
public interface FeedSummaryGenerator {
  String generate(String prompt);
}
