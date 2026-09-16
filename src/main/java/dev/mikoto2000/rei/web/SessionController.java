package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionQueryService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/sessions")
public class SessionController {
  private final SessionQueryService sessions;
  public SessionController(SessionQueryService sessions) { this.sessions = sessions; }
  @GetMapping
  public SessionListResponse list(@RequestParam(required = false) String projectId,
      @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
    var page = sessions.listSessions(projectId, limit, cursor);
    return new SessionListResponse(page.items().stream().map(SessionResponse::from).toList(), page.nextCursor());
  }
  @GetMapping("/{sessionId}")
  public SessionResponse detail(@PathVariable String sessionId) { return SessionResponse.from(sessions.getSession(sessionId)); }
  @GetMapping("/{sessionId}/turns")
  public SessionTurnListResponse turns(@PathVariable String sessionId, @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    var page = sessions.listTurns(sessionId, limit, cursor);
    return new SessionTurnListResponse(sessionId, page.items().stream().map(SessionTurnResponse::from).toList(), page.nextCursor());
  }
}
