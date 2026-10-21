package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 話題候補入力の解釈の結果と状態の契約を検証するテスト。 */
class TopicCandidateParserTest {
  /** 検証に使う入力の解釈。 */
  private final TopicCandidateParser parser =
      new TopicCandidateParser(
          new JsonMapper(), Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));

  /** 話題の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesUnfinishedWorkCandidate() {
    var candidates =
        parser.parse(
            """
        {"candidates":[{"topic":"Working Set 改善後の探索時間評価","reason":"効果測定が未実施","type":"unfinished_work","source":"working_set","priority":0.8,"freshness":0.9,"usefulness":0.8,"intrusiveness":0.2,"confidence":0.9}]}
        """,
            5);

    assertEquals(1, candidates.size());
    assertEquals(TopicType.UNFINISHED_WORK, candidates.getFirst().type());
    assertEquals(TopicSource.WORKING_SET, candidates.getFirst().source());
  }

  /** 話題の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesFollowUpCandidate() {
    var candidates =
        parser.parse(
            """
        {"candidates":[{"topic":"明日の確認","reason":"後で確認すると言っていた","type":"follow_up","source":"conversation","priority":0.7,"freshness":0.8,"usefulness":0.7,"intrusiveness":0.2,"confidence":0.8}]}
        """,
            5);

    assertEquals(1, candidates.size());
    assertEquals(TopicType.FOLLOW_UP, candidates.getFirst().type());
  }

  /** 話題の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void ignoresCompletedOrIrrelevantUnsupportedCandidates() {
    var candidates =
        parser.parse(
            """
        {"candidates":[
          {"topic":"完了済みメモ","reason":"done","type":"casual","source":"conversation","priority":1,"freshness":1,"usefulness":1,"intrusiveness":0,"confidence":1},
          {"topic":"","reason":"blank","type":"unfinished_work","source":"conversation","priority":1,"freshness":1,"usefulness":1,"intrusiveness":0,"confidence":1}
        ]}
        """,
            5);

    assertTrue(candidates.isEmpty());
  }

  /** 空のを返すことを確認する。 */
  @Test
  void returnsEmptyWhenNoCandidates() {
    assertTrue(parser.parse("{\"candidates\":[]}", 5).isEmpty());
  }

  /** 話題の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void limitsMaximumCandidates() {
    var candidates =
        parser.parse(
            """
        {"candidates":[
          {"topic":"a","reason":"","type":"unfinished_work","source":"conversation","priority":1,"freshness":1,"usefulness":1,"intrusiveness":0,"confidence":1},
          {"topic":"b","reason":"","type":"follow_up","source":"conversation","priority":1,"freshness":1,"usefulness":1,"intrusiveness":0,"confidence":1}
        ]}
        """,
            1);

    assertEquals(1, candidates.size());
  }

  /** 話題の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidJsonFailsClosed() {
    assertTrue(parser.parse("not json", 5).isEmpty());
  }
}
