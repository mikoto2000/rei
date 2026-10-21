package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.planning.DailyPlanningService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 日次計画をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.today.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DailyPlanningController {
  /** サービス。 */
  private final DailyPlanningService service;

  /** 指定された値や利用先を使って、DailyPlanningControllerを初期化する。 */
  public DailyPlanningController(DailyPlanningService service) {
    this.service = service;
  }

  /** Web APIによる操作で用いる今日を返す。 */
  @GetMapping("/api/v1/today")
  public DailyPlanningService.Plan today(
      @RequestParam(required = false, name = "projectId") List<String> projectIds) {
    return service.today(projectIds);
  }
}
