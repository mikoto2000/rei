package dev.mikoto2000.rei.web;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.temporal.*;
import dev.mikoto2000.rei.application.state.OperationConflictException;

/** Human controls for saved schedules; activation retains the dispatcher's opt-in gates. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/schedules")
public class ScheduleController {
 private final PersistentAgentScheduler repository;
 private final AgentScheduleDispatcher dispatcher;
 public ScheduleController(PersistentAgentScheduler repository,AgentScheduleDispatcher dispatcher) {
  this.repository=repository;this.dispatcher=dispatcher;
 }
 public record Interval(long intervalMillis,int remaining) {}
 public record Inspection(PersistentAgentScheduler.Entry schedule,Interval interval,
     PersistentAgentScheduler.Cron cron,PersistentAgentScheduler.EventTrigger eventTrigger) {}
 @GetMapping public List<PersistentAgentScheduler.Entry> list(@PathVariable String projectId) {
  return repository.list(projectId);
 }
 @GetMapping("/{id}") public Inspection show(@PathVariable String projectId,@PathVariable String id) {
  var schedule=repository.get(projectId,id);
  var interval=repository.interval(projectId,id).map(value->new Interval(value.interval().toMillis(),value.remaining())).orElse(null);
  return new Inspection(schedule,interval,repository.cron(projectId,id).orElse(null),repository.eventTrigger(projectId,id).orElse(null));
 }
 @GetMapping("/{id}/history") public List<PersistentAgentScheduler.History> history(@PathVariable String projectId,@PathVariable String id) {
  return repository.history(projectId,id);
 }
 @PostMapping("/{id}/activate") public Inspection activate(@PathVariable String projectId,@PathVariable String id) {
  return control(()->{repository.activate(projectId,id);return show(projectId,id);});
 }
 @PostMapping("/{id}/cancel") public Inspection cancel(@PathVariable String projectId,@PathVariable String id) {
  return control(()->{repository.cancel(projectId,id);return show(projectId,id);});
 }
 public record ReconcileRequest(String expectedRunId,boolean acknowledgeUncertainSideEffects) {}
 @PostMapping("/{id}/reconcile") public PersistentAgentScheduler.Entry reconcile(@PathVariable String projectId,
     @PathVariable String id,@RequestBody ReconcileRequest request) {
  if(request==null||!request.acknowledgeUncertainSideEffects()||request.expectedRunId()==null
      ||request.expectedRunId().isBlank()||request.expectedRunId().length()>128)
   throw new IllegalArgumentException("Inspect effects and acknowledge the exact saved Run before reconciliation");
  return control(()->dispatcher.reconcile(projectId,id,request.expectedRunId()));
 }
 private static <T> T control(Supplier<T> action) {
  try{return action.get();}catch(IllegalStateException conflict){throw new OperationConflictException();}
 }
}
