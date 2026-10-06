package dev.mikoto2000.rei.web;

import java.util.ConcurrentModificationException;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import dev.mikoto2000.rei.activity.*;

/** Journal-wide explicit preferences. Reading/changing settings never assesses or reserves advice. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ActivityCoachingController {
  public record State(int schemaVersion,String scope,long revision,PeriodCoaching.Settings settings) {}
  public record ConfigureRequest(Long expectedRevision,PeriodCoaching.Settings settings) {}
  public record EnableRequest(Long expectedRevision,Boolean enabled) {}
  private final ObjectProvider<PeriodCoachingService> services;
  public ActivityCoachingController(ObjectProvider<PeriodCoachingService> services){this.services=services;}
  private PeriodCoachingService service(){var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Activity coaching is disabled");return service;}
  private State state(PeriodCoachingStore.Snapshot snapshot){return new State(1,"LOCAL_DEVICE_COACHING_SETTINGS",snapshot.revision(),snapshot.settings());}
  @GetMapping("/api/v1/activity/coaching") public State status(){return state(service().status());}
  @PostMapping("/api/v1/activity/coaching/settings") public State configure(@RequestBody ConfigureRequest request){
    if(request==null||request.settings()==null||request.settings().enabled())throw new IllegalArgumentException("Save disabled coaching settings before explicit enable");
    long revision=revision(request.expectedRevision());return update(()->service().configureExpected(request.settings(),revision));
  }
  @PostMapping("/api/v1/activity/coaching/enabled") public State enable(@RequestBody EnableRequest request){
    if(request==null||request.enabled()==null)throw new IllegalArgumentException("Require explicit enabled flag");
    long revision=revision(request.expectedRevision());return update(()->service().setEnabledExpected(request.enabled(),revision));
  }
  private long revision(Long revision){if(revision==null||revision<0||revision==Long.MAX_VALUE)throw new IllegalArgumentException("Require viewed coaching revision");return revision;}
  private State update(Supplier<PeriodCoachingStore.Snapshot> update){
    try{return state(update.get());}
    catch(ConcurrentModificationException conflict){throw new ResponseStatusException(HttpStatus.CONFLICT,"Coaching settings changed; reload before updating");}
    catch(UnsupportedOperationException unsupported){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Revision-checked coaching updates unavailable");}
  }
}
