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
  private dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle;
  private dev.mikoto2000.rei.core.project.ProjectRegistry projects;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setProjects(dev.mikoto2000.rei.core.project.ProjectRegistry projects){this.projects=projects;}
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setLifecycle(dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle){this.lifecycle=lifecycle;}
  public SessionController(SessionQueryService sessions) { this.sessions = sessions; }
  public record CreateRequest(String projectId,String title) implements StrictApiRequest {}
  @PostMapping
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public SessionResponse create(@RequestBody CreateRequest request) {
    if(request==null||request.title()!=null&&request.title().length()>2000)throw new IllegalArgumentException("Invalid Session request");
    return SessionResponse.from(lifecycle().create(project(request.projectId()),request.title()));
  }
  public record StyleRequest(String projectId,dev.mikoto2000.rei.core.chat.ResponseStyle style,Boolean voiceOnly) implements StrictApiRequest {}
  public record StyleResponse(String sessionId,String projectId,dev.mikoto2000.rei.core.chat.ResponseStyle style,boolean voiceOnly) {
    static StyleResponse from(dev.mikoto2000.rei.application.session.SessionMetadata value){return new StyleResponse(value.sessionId(),value.projectId(),value.responseStyle(),value.voiceOnly());}
  }
  @GetMapping("/{sessionId}/response-style")
  public StyleResponse style(@PathVariable String sessionId,@RequestParam String projectId) {
    return StyleResponse.from(lifecycle().validate(sessionId,project(projectId).id()));
  }
  @PatchMapping("/{sessionId}/response-style")
  public StyleResponse style(@PathVariable String sessionId,@RequestBody StyleRequest request) {
    if(request==null||request.style()==null||Boolean.TRUE.equals(request.voiceOnly())&&request.style()!=dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION)throw new IllegalArgumentException("Invalid response style");
    return StyleResponse.from(lifecycle().responseStyle(project(request.projectId()),sessionId,request.style(),Boolean.TRUE.equals(request.voiceOnly())));
  }
  private dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle(){if(lifecycle==null)throw new IllegalStateException("Session lifecycle unavailable");return lifecycle;}
  private dev.mikoto2000.rei.core.project.ProjectContext project(String id) {
    if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("Project ID required");
    if(projects==null)throw new IllegalStateException("Project registry unavailable");
    return projects.resolveById(id).orElseThrow(()->new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
  }
  @GetMapping
  public SessionListResponse list(@RequestParam(required = false) String projectId,
      @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
    var page = sessions.listSessions(projectId, limit, cursor);
    return new SessionListResponse(page.items().stream().map(SessionResponse::from).toList(), page.nextCursor());
  }
  @GetMapping("/{sessionId}")
  public SessionResponse detail(@PathVariable String sessionId) { return SessionResponse.from(sessions.getSession(sessionId)); }
  public record EndRequest(String projectId) {}
  @PostMapping("/{sessionId}/end")
  public SessionResponse end(@PathVariable String sessionId,@RequestBody EndRequest request) {
    if(request==null||request.projectId()==null||request.projectId().isBlank())throw new IllegalArgumentException("Project ID is required");
    if(lifecycle==null)throw new IllegalStateException("Session lifecycle unavailable");
    return SessionResponse.from(lifecycle.end(sessionId,request.projectId()));
  }
  @GetMapping("/{sessionId}/turns")
  public SessionTurnListResponse turns(@PathVariable String sessionId, @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    var page = sessions.listTurns(sessionId, limit, cursor);
    return new SessionTurnListResponse(sessionId, page.items().stream().map(SessionTurnResponse::from).toList(), page.nextCursor());
  }
}
