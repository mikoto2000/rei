package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.read.ReadQueryService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class ReadController {
  private final ReadQueryService queries;
  public ReadController(ReadQueryService queries) { this.queries = queries; }
  @GetMapping("/feed") public List<FeedResponse> feeds() { return queries.feedsList().stream().map(FeedResponse::from).toList(); }
  @GetMapping("/feed/{id}") public FeedResponse feed(@PathVariable long id) { return FeedResponse.from(queries.feed(id)); }
  @GetMapping("/skills") public List<SkillResponse> skills() { return queries.skillsList().stream().map(SkillResponse::from).toList(); }
  @GetMapping("/skills/{name}") public SkillResponse skill(@PathVariable String name) { return SkillResponse.from(queries.skill(name)); }
  @GetMapping("/profile") public ProfileResponse profile() { return ProfileResponse.from(queries.profile()); }
  @GetMapping("/briefing") public BriefingResponse briefing() throws Exception { return BriefingResponse.from(queries.briefing()); }
  @PostMapping("/search") public SearchResponse search(@RequestBody SearchRequest request) throws Exception {
    return SearchResponse.from(queries.search(request.query(), request.vectorTopK(), request.webTopK(), request.threshold()));
  }
}
