package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryFollowups;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  public record Generate(String projectId, LocalDate date, Boolean review, String key)
      implements StrictApiRequest {}

  public record MeetingConfirm(
      String projectId, LocalDate date, Boolean review, String key, List<String> selected)
      implements StrictApiRequest {
    public MeetingConfirm {
      selected = selected == null ? null : List.copyOf(selected);
    }
  }

  @PostMapping("/meetings/confirm")
  public dev.mikoto2000.rei.secretary.SecretaryMeeting.Decision confirmMeeting(
      @RequestBody MeetingConfirm request) {
    if (request.projectId() == null)
      return service()
          .globalBriefings()
          .confirmMeeting(
              request.date(),
              Boolean.TRUE.equals(request.review()),
              request.key(),
              request.selected());
    return service()
        .confirmMeeting(
            request.projectId(),
            request.date(),
            Boolean.TRUE.equals(request.review()),
            request.key(),
            request.selected());
  }

  @PostMapping("/briefings/generate")
  public Object generate(@RequestBody Generate request) {
    if (request.projectId() == null)
      return service()
          .globalBriefings()
          .generate(request.date(), Boolean.TRUE.equals(request.review()), request.key());
    return service()
        .generate(
            request.projectId(),
            request.date(),
            Boolean.TRUE.equals(request.review()),
            request.key());
  }

  @GetMapping("/briefings")
  public Object briefings(
      @RequestParam(required = false) String projectId,
      @RequestParam(defaultValue = "20") int limit) {
    if (projectId == null) return service().globalBriefings().history(limit);
    return service().briefingHistory(projectId, limit);
  }

  @GetMapping("/briefings/{id}")
  public Object briefingHistory(
      @PathVariable String id, @RequestParam(required = false) String projectId) {
    if (projectId == null) return service().globalBriefings().show(id);
    return service().briefing(projectId, id);
  }

  @PostMapping("/briefings/delete")
  public java.util.Map<String, Integer> deleteBriefings(@RequestBody Check request) {
    if (request.projectId() == null)
      return java.util.Map.of("deleted", service().globalBriefings().deleteHistory());
    return java.util.Map.of("deleted", service().deleteBriefings(request.projectId()));
  }

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
  public Object briefing(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) LocalDate date) {
    if (projectId == null) return service().globalBriefings().report(date, false);
    return service().report(projectId, date, false);
  }

  @GetMapping("/review")
  public Object review(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) LocalDate date) {
    if (projectId == null) return service().globalBriefings().report(date, true);
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

  @GetMapping("/briefings/settings")
  public dev.mikoto2000.rei.secretary.SecretaryGlobalBriefings.Settings globalSettings() {
    return service().globalBriefings().settings();
  }

  public record GlobalCheck() implements StrictApiRequest {}

  @PostMapping("/briefings/check")
  public List<SecretaryFollowups.Delivery> globalCheck(@RequestBody GlobalCheck request) {
    return service().checkGlobalNow();
  }

  @GetMapping("/briefings/notifications")
  public List<SecretaryFollowups.Delivery> globalNotifications(
      @RequestParam(defaultValue = "100") int limit) {
    return service()
        .history(dev.mikoto2000.rei.secretary.SecretaryNotifications.GLOBAL_SCOPE, limit);
  }
}
