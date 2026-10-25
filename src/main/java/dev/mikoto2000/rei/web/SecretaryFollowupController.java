package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryFollowups;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated local secretary operations. Configuration never grants delivery permissions. */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.secretary.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/secretary")
public class SecretaryFollowupController {
  private final org.springframework.beans.factory.ObjectProvider<SecretaryFollowups> services;

  public SecretaryFollowupController(
      org.springframework.beans.factory.ObjectProvider<SecretaryFollowups> services) {
    this.services = services;
  }

  private SecretaryFollowups service() {
    return services.getObject();
  }

  public record Configure(
      String projectId,
      Boolean enabled,
      Boolean notifications,
      String briefingTime,
      String reviewTime,
      String zone,
      Long repeatSeconds,
      Integer notificationPriority,
      String destination)
      implements StrictApiRequest {}

  public record Check(String projectId) implements StrictApiRequest {}

  @GetMapping("/settings")
  public SecretaryFollowups.Settings settings(@RequestParam String projectId) {
    return service().settings(projectId);
  }

  @PutMapping("/settings")
  public SecretaryFollowups.Settings configure(@RequestBody Configure request) {
    return service()
        .configure(
            new SecretaryFollowups.Settings(
                request.projectId(),
                Boolean.TRUE.equals(request.enabled()),
                Boolean.TRUE.equals(request.notifications()),
                request.briefingTime() == null ? "" : request.briefingTime(),
                request.reviewTime() == null ? "" : request.reviewTime(),
                request.zone() == null
                    ? service().settings(request.projectId()).zone()
                    : request.zone(),
                request.repeatSeconds() == null ? 86400 : request.repeatSeconds(),
                request.notificationPriority() == null ? 3 : request.notificationPriority(),
                request.destination() == null ? "INBOX" : request.destination()));
  }

  @GetMapping("/briefing")
  public SecretaryFollowups.Report briefing(
      @RequestParam String projectId, @RequestParam(required = false) LocalDate date) {
    return service().report(projectId, date, false);
  }

  @GetMapping("/review")
  public SecretaryFollowups.Report review(
      @RequestParam String projectId, @RequestParam(required = false) LocalDate date) {
    return service().report(projectId, date, true);
  }

  @GetMapping("/notifications")
  public List<SecretaryFollowups.Delivery> history(
      @RequestParam String projectId, @RequestParam(defaultValue = "100") int limit) {
    return service().history(projectId, limit);
  }

  @GetMapping("/followup/status")
  public SecretaryFollowups.Status status(@RequestParam String projectId) {
    return service().status(projectId);
  }

  @PostMapping("/check")
  public List<SecretaryFollowups.Delivery> check(@RequestBody Check request) {
    return service().checkNow(request.projectId());
  }
}
