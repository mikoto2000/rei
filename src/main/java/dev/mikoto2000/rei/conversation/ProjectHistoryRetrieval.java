package dev.mikoto2000.rei.conversation;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import dev.mikoto2000.rei.core.project.*;
import static dev.mikoto2000.rei.conversation.HistoryRetrievalPolicy.*;

/** Two-stage, bounded read-only retrieval over project stores. No repository paths are resolved. */
final class ProjectHistoryRetrieval {
  record Outcome(List<ConversationSearchResult> results, int searchedProjectCount) {}
  private final ConversationLogStore logs;
  private final ProjectRegistry registry;
  ProjectHistoryRetrieval(ConversationLogStore logs, ProjectRegistry registry) {
    this.logs = logs; this.registry = registry;
  }
  List<ProjectContext> projects() { return registry.list(); }

  Outcome search(HistorySearchRequest request, Predicate<ConversationLogEntry> filter, int limit) {
    // Snapshot once; neither a Shell switch nor a Registry change can reorder an in-flight search.
    List<ProjectContext> projects = projects();
    String preferred = request.preferredProjectId();
    ProjectContext current = projects.stream().filter(p -> p.id().equals(preferred)).findFirst().orElse(null);
    String query = normalized(request.query());
    ProjectContext explicit = null;
    if (request.retrievalScope() != HistorySearchScope.CURRENT_PROJECT_ONLY) {
      explicit = resolveReference(request.referencedProject(), query, projects);
      if (explicit != null) query = mentionPattern(explicit.name()).matcher(query).replaceAll(" ").strip();
    }
    List<String> terms = Arrays.stream(query.split("[\\s\\p{P}]+"))
        .filter(t -> !t.isBlank()).distinct().toList();
    if (terms.isEmpty()) return new Outcome(List.of(), 0);
    Map<String, List<ConversationSearchResult>> searched = new LinkedHashMap<>();
    if (request.retrievalScope() == HistorySearchScope.ALL_PROJECTS) {
      for (var project : projects) scan(project, preferred, terms, filter, searched);
      return new Outcome(bounded(searched.values().stream().flatMap(List::stream).sorted(RANK).toList(), preferred, limit), searched.size());
    }
    if (explicit != null) scan(explicit, preferred, terms, filter, searched);
    if (current != null) scan(current, preferred, terms, filter, searched);
    List<ConversationSearchResult> candidates = new ArrayList<>();
    if (explicit != null) candidates.addAll(searched.get(explicit.id()));
    if (current != null && (explicit == null || !current.id().equals(explicit.id())))
      candidates.addAll(searched.get(current.id()));
    boolean enough = bounded(candidates, preferred, limit).stream().filter(r -> r.relevanceScore() >= STRONG_RELEVANCE)
        .count() >= Math.min(SUFFICIENT_STRONG_RESULTS, limit);
    if (request.retrievalScope() == HistorySearchScope.CURRENT_PROJECT_PREFERRED && !enough) {
      List<ConversationSearchResult> other = new ArrayList<>();
      for (var project : projects) if (!searched.containsKey(project.id()))
        other.addAll(scan(project, preferred, terms, filter, searched));
      other.sort(RANK);
      candidates.addAll(other);
    }
    return new Outcome(bounded(candidates, preferred, limit), searched.size());
  }

  private static final Comparator<ConversationSearchResult> RANK = Comparator
      .comparingDouble(ConversationSearchResult::relevanceScore).reversed()
      .thenComparing(r -> java.time.Instant.parse(r.timestamp()), Comparator.reverseOrder())
      .thenComparing(ConversationSearchResult::conversationId);

  private List<ConversationSearchResult> scan(ProjectContext project, String preferred, List<String> terms,
      Predicate<ConversationLogEntry> filter, Map<String, List<ConversationSearchResult>> searched) {
    if (searched.containsKey(project.id())) return searched.get(project.id());
    int budget = project.id().equals(preferred) ? CURRENT_MAX_RESULTS : CROSS_PROJECT_MAX_RESULTS;
    var best = new PriorityQueue<ConversationSearchResult>(RANK.reversed());
    logs.visitProject(project.id(), e -> {
      if (!filter.test(e) || e.content() == null) return;
      String text = normalized(e.content());
      double score = (double) terms.stream().filter(text::contains).count() / terms.size();
      if (score < MIN_RELEVANCE) return;
      String safe = dev.mikoto2000.rei.event.CredentialRedactor.redact(e.content());
      best.add(new ConversationSearchResult(e.conversationId(), e.scope(), e.speaker(), e.timestamp().toInstant().toString(),
          clip(safe, 120), excerpt(safe, terms), project.id(), project.name(),
          project.id().equals(preferred) ? LOCAL_BOUNDARY : FOREIGN_BOUNDARY, score));
      if (best.size() > budget) best.remove();
    });
    var found = best.stream().sorted(RANK).toList();
    searched.put(project.id(), found);
    return found;
  }

  private List<ConversationSearchResult> bounded(List<ConversationSearchResult> candidates, String preferred, int limit) {
    List<ConversationSearchResult> results = new ArrayList<>();
    int local = 0, foreign = 0;
    for (var result : candidates) {
      if (results.size() >= limit) break;
      if (result.sourceProjectId().equals(preferred)) {
        if (local >= CURRENT_MAX_RESULTS) continue;
        local++;
      } else {
        if (foreign >= CROSS_PROJECT_MAX_RESULTS) continue;
        foreign++;
      }
      results.add(result);
    }
    return List.copyOf(results);
  }

  private ProjectContext resolveReference(String reference, String query, List<ProjectContext> projects) {
    if (reference != null && !reference.isBlank()) {
      var matches = projects.stream().filter(p -> p.id().equals(reference)
          || normalized(p.name()).equals(normalized(reference))).toList();
      if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous project reference: " + reference);
      return matches.getFirst();
    }
    var mentions = projects.stream().filter(p -> mentionPattern(p.name()).matcher(query).find()).toList();
    // Ambiguous or multiple mentions never select an arbitrary project.
    return mentions.size() == 1 ? mentions.getFirst() : null;
  }
  private Pattern mentionPattern(String name) {
    // ASCII boundaries avoid matching 'rei' inside 'freight'; Japanese particles may follow a name.
    return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(normalized(name)) + "(?![a-z0-9_])");
  }
  private static String normalized(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
  }
  private String excerpt(String content, List<String> terms) {
    if (content.length() <= CONTENT_MAX_CHARS) return content;
    String lower = content.toLowerCase(Locale.ROOT);
    int first = terms.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(0);
    return clip(content.substring(Math.max(0, first - 80)), CONTENT_MAX_CHARS);
  }
}
