package dev.mikoto2000.rei.web;

import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ActivityObservationContextController {
 public record Report(int schemaVersion,String scope,String projectId,LocalDate date,String zone,boolean partial,int missingContextRecords,int linkedObservations,String report) {}
 private final ProjectRegistry projects;private final ObjectProvider<ActivityWorkContextService> services;
 public ActivityObservationContextController(ProjectRegistry projects,ObjectProvider<ActivityWorkContextService> services){this.projects=projects;this.services=services;}
 @GetMapping("/api/v1/projects/{projectId}/activity/observation-context")
 public Report report(@PathVariable String projectId,@RequestParam(defaultValue="today") String date){
  projects.resolveById(projectId).orElseThrow(()->new ResourceNotFoundException("Project"));
  var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Activity is disabled");
  try {var links=service.observationLinksBounded(projectId,date);String report=dev.mikoto2000.rei.event.CredentialRedactor.redact(service.formatObservationBounded(links.links()));
    if(report.length()>32768)throw new ActivityQueryLimitException();
    return new Report(1,"PROJECT_OBSERVATION_CONTEXT",projectId,links.date(),links.zone(),links.partial(),links.missingContextRecords(),links.links().size(),report);
  }catch(DateTimeException invalid){throw new IllegalArgumentException("Invalid Activity calendar date");}
   catch(ActivityQueryLimitException limit){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Activity context evidence limit exceeded");}
   catch(UnsupportedOperationException unsupported){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Bounded Activity context unavailable");}
 }
}
