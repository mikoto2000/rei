package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.read.ReadQueryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 読み取りをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class ReadController {
  /** 検索条件一覧。 */
  private final ReadQueryService queries;

  /** 指定された値や利用先を使って、ReadControllerを初期化する。 */
  public ReadController(ReadQueryService queries) {
    this.queries = queries;
  }

  /** Web APIによる操作で用いるフィード一覧を返す。 */
  @GetMapping("/feed")
  public List<FeedResponse> feeds() {
    return queries.feedsList().stream().map(FeedResponse::from).toList();
  }

  /** Web APIによる操作で用いるフィードを返す。 */
  @GetMapping("/feed/{id}")
  public FeedResponse feed(@PathVariable long id) {
    return FeedResponse.from(queries.feed(id));
  }

  /** Web APIによる操作で用いるスキル一覧を返す。 */
  @GetMapping("/skills")
  public List<SkillResponse> skills() {
    return queries.skillsList().stream().map(SkillResponse::from).toList();
  }

  /** Web APIによる操作で用いるスキルを返す。 */
  @GetMapping("/skills/{name}")
  public SkillResponse skill(@PathVariable String name) {
    return SkillResponse.from(queries.skill(name));
  }

  /** Web APIによる操作で用いる利用者情報を返す。 */
  @GetMapping("/profile")
  public ProfileResponse profile() {
    return ProfileResponse.from(queries.profile());
  }

  /** Web APIによる操作で用いる日次案内を返す。 */
  @GetMapping("/briefing")
  public BriefingResponse briefing() throws Exception {
    return BriefingResponse.from(queries.briefing());
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  @PostMapping("/search")
  public SearchResponse search(@RequestBody SearchRequest request) throws Exception {
    return SearchResponse.from(
        queries.search(
            request.query(), request.vectorTopK(), request.webTopK(), request.threshold()));
  }
}
