package dev.mikoto2000.rei.interest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.search.SearchKnowledgeService;

/**
 * Feature: interest-discovery
 * Property 3: 頻度制限内のトピックはスキップされる
 * Property 4: 既存検索クエリはスキップされる
 * Property 5: 保存データの完全性
 */
class InterestDiscoveryJobPropertyTest {

  @TempDir
  java.nio.file.Path tempDir;

  private static int dbCounter = 0;

  private InterestUpdateService createService() {
    return new InterestUpdateService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("prop-" + (++dbCounter) + ".db")));
  }

  // --- Property 3: 頻度制限内のトピックはスキップされる ---

  static Stream<String> topicVariants() {
    return Stream.of(
        "Neovim 開発環境",
        "Java 25 features",
        "Spring Boot 4",
        "Rust async",
        "Docker compose",
        "Kubernetes",
        "Machine Learning",
        "TypeScript",
        "PostgreSQL",
        "Redis cluster"
    );
  }

  @ParameterizedTest(name = "topic={0}")
  @MethodSource("topicVariants")
  @Tag("interest-discovery-property-3-topicFrequencyLimit")
  void topicWithinFrequencyLimitIsSkipped(String topic) throws Exception {
    ConversationInterestService conversationInterestService = mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService = mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService = createService();
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job = new InterestDiscoveryJob(
        conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    // 頻度制限内（1時間前）に同一トピックを保存
    interestUpdateService.saveWithCreatedAt(
        topic, "reason", "existing-query-" + topic, "summary", List.of(),
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(1));

    when(conversationInterestService.discoverCandidates(anyList())).thenReturn(List.of(
        new InterestTopicCandidate(topic, "reason", "new-query-" + topic, 0.9)));

    job.discoverNow();

    // 頻度制限内のため search が呼ばれないこと
    verify(searchKnowledgeService, never()).search(anyString(), anyInt(), anyInt(), any(), any());
  }

  // --- Property 4: 既存検索クエリはスキップされる ---

  static Stream<String> queryVariants() {
    return Stream.of(
        "neovim devcontainer best practices",
        "java 25 new features",
        "spring boot 4 migration guide",
        "rust async programming tutorial",
        "docker compose v2 tips",
        "kubernetes helm charts best practices",
        "machine learning pytorch tutorial",
        "typescript generics advanced",
        "postgresql performance tuning",
        "redis cluster setup guide"
    );
  }

  @ParameterizedTest(name = "query={0}")
  @MethodSource("queryVariants")
  @Tag("interest-discovery-property-4-existingQuerySkipped")
  void existingSearchQueryIsSkipped(String searchQuery) throws Exception {
    ConversationInterestService conversationInterestService = mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService = mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService = createService();
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    // 頻度制限を無効化（十分長い間隔）するため、別トピック名を使う
    InterestDiscoveryJob job = new InterestDiscoveryJob(
        conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    // 同一クエリを事前に保存（別トピック名で頻度制限を回避）
    interestUpdateService.save("OtherTopic-" + searchQuery, "reason", searchQuery, "summary", List.of());

    when(conversationInterestService.discoverCandidates(anyList())).thenReturn(List.of(
        new InterestTopicCandidate("NewTopic-" + searchQuery, "reason", searchQuery, 0.9)));

    job.discoverNow();

    // 既存クエリのため search が呼ばれないこと
    verify(searchKnowledgeService, never()).search(eq(searchQuery), anyInt(), anyInt(), any(), any());
  }

  // --- Property 5: 保存データの完全性 ---

  static Stream<InterestTopicCandidate> candidateVariants() {
    return Stream.of(
        new InterestTopicCandidate("Neovim", "vim 話題", "neovim query", 0.9),
        new InterestTopicCandidate("Java 25", "java 話題", "java 25 query", 0.85),
        new InterestTopicCandidate("Spring Boot", "spring 話題", "spring boot query", 0.8),
        new InterestTopicCandidate("Rust", "rust 話題", "rust async query", 0.75),
        new InterestTopicCandidate("Docker", "docker 話題", "docker compose query", 0.7)
    );
  }

  @ParameterizedTest(name = "topic={0}")
  @MethodSource("candidateVariants")
  @Tag("interest-discovery-property-5-savedDataIntegrity")
  void savedDataMatchesCandidate(InterestTopicCandidate candidate) throws Exception {
    ConversationInterestService conversationInterestService = mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService = mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService = createService();
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job = new InterestDiscoveryJob(
        conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    when(conversationInterestService.discoverCandidates(anyList())).thenReturn(List.of(candidate));
    when(searchKnowledgeService.search(eq(candidate.searchQuery()), anyInt(), anyInt(), any(), any()))
        .thenReturn(new dev.mikoto2000.rei.search.SearchKnowledgeResult(
            candidate.searchQuery(),
            List.of(),
            dev.mikoto2000.rei.websearch.WebSearchContext.primaryOnly(List.of(
                new dev.mikoto2000.rei.websearch.WebSearchPage(
                    "title", "https://example.com", "snippet", "2026-04-29", "content"))),
            null));

    List<InterestUpdate> saved = job.discoverNow();

    // 保存されたデータが候補と一致すること
    org.junit.jupiter.api.Assertions.assertEquals(1, saved.size());
    org.junit.jupiter.api.Assertions.assertEquals(candidate.topic(), saved.getFirst().topic());
    org.junit.jupiter.api.Assertions.assertEquals(candidate.reason(), saved.getFirst().reason());
    org.junit.jupiter.api.Assertions.assertEquals(candidate.searchQuery(), saved.getFirst().searchQuery());
  }
}
