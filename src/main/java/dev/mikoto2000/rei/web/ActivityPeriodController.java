package dev.mikoto2000.rei.web;

import java.time.*;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import dev.mikoto2000.rei.activity.*;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ActivityPeriodController {
  public record Report(int schemaVersion,String scope,String period,LocalDate anchorDate,String zone,boolean partial,String report) {}
  private final ObjectProvider<ActivityTimeline> timeline;
  public ActivityPeriodController(ObjectProvider<ActivityTimeline> timeline){this.timeline=timeline;}
  @GetMapping("/api/v1/activity/period")
  public Report period(@RequestParam(defaultValue="WEEK") String period,@RequestParam(required=false) String date) {
    var selected=ActivityPeriodAnalysis.Period.valueOf(period.toUpperCase(Locale.ROOT));
    if(date!=null){if(date.length()!=10)throw new IllegalArgumentException("Use an ISO calendar date");date=LocalDate.parse(date).toString();}
    var source=timeline.getIfAvailable();if(source==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Activity is disabled");
    try {
      var comparison=source.periodComparisonBounded(selected,date);String report=dev.mikoto2000.rei.event.CredentialRedactor.redact(source.formatPeriodComparison(comparison));
      if(report.length()>32768)throw new ActivityQueryLimitException();
      return new Report(1,"LOCAL_DEVICE_OBSERVATIONS",selected.name(),comparison.current().range().firstDate(),comparison.zone().toString(),comparison.current().range().partial(),report);
    }catch(DateTimeException invalid){throw new IllegalArgumentException("Invalid Activity calendar date");}
    catch(ActivityQueryLimitException limit){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Activity analysis evidence limit exceeded");}
    catch(UnsupportedOperationException unsupported){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Bounded Activity analysis is unavailable");}
  }
}
