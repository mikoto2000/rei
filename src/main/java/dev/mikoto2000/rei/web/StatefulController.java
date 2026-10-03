package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.state.StatefulOperationService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class StatefulController {
  private final StatefulOperationService operations;
  public StatefulController(StatefulOperationService operations) { this.operations=operations; }
  public record CreateFeedRequest(String url,String displayName) implements StrictApiRequest {}
  public record UpdateFeedRequest(String displayName,Boolean enabled) implements StrictApiRequest {}
  public record CreateReminderRequest(String message,String at,String target,Integer minutesBefore) implements StrictApiRequest {}
  public record CreateInterestRequest(String topic,String reason,String searchQuery,String summary,List<String> sourceUrls) implements StrictApiRequest {}
  public record CreateMemoryRequest(String content,String type,String scope,Double confidence) implements StrictApiRequest {}
  public record ReloadSkillsRequest() implements StrictApiRequest {}
  public record SkillReloadResponse(int count) {}
  @PostMapping("/feed") public ResponseEntity<FeedResponse> createFeed(@RequestBody CreateFeedRequest r) {
    var response=FeedResponse.from(operations.createFeed(r.url(),r.displayName()));
    return ResponseEntity.created(URI.create("/api/v1/feed/"+response.id())).body(response);
  }
  @PatchMapping("/feed/{id}") public FeedResponse updateFeed(@PathVariable long id,@RequestBody UpdateFeedRequest r) {
    return FeedResponse.from(operations.updateFeed(id,r.displayName(),r.enabled()));
  }
  @DeleteMapping("/feed/{id}") public ResponseEntity<Void> deleteFeed(@PathVariable long id) { operations.deleteFeed(id); return ResponseEntity.noContent().build(); }
  @GetMapping("/reminders") public List<ReminderResponse> reminders() { return operations.remindersList().stream().map(ReminderResponse::from).toList(); }
  @GetMapping("/reminders/{id}") public ReminderResponse reminder(@PathVariable long id) { return ReminderResponse.from(operations.reminder(id)); }
  @PostMapping("/reminders") public ResponseEntity<ReminderResponse> createReminder(@RequestBody CreateReminderRequest r) {
    var response=ReminderResponse.from(operations.createReminder(r.message(),r.at(),r.target(),r.minutesBefore()));
    return ResponseEntity.created(URI.create("/api/v1/reminders/"+response.id())).body(response);
  }
  @DeleteMapping("/reminders/{id}") public ResponseEntity<Void> deleteReminder(@PathVariable long id) { operations.deleteReminder(id); return ResponseEntity.noContent().build(); }
  @GetMapping("/interests") public List<InterestResponse> interests(@RequestParam(defaultValue="24") int hours) { return operations.interestsList(hours).stream().map(InterestResponse::from).toList(); }
  @PostMapping("/interests") public ResponseEntity<InterestResponse> createInterest(@RequestBody CreateInterestRequest r) {
    return ResponseEntity.status(201).body(InterestResponse.from(operations.createInterest(r.topic(),r.reason(),r.searchQuery(),r.summary(),r.sourceUrls())));
  }
  @GetMapping("/memories") public List<MemoryResponse> memories() { return operations.memoriesList().stream().map(MemoryResponse::from).toList(); }
  @GetMapping("/memories/{id}") public MemoryResponse memory(@PathVariable String id) { return MemoryResponse.from(operations.memory(id)); }
  @PostMapping("/memories") public ResponseEntity<MemoryResponse> createMemory(@RequestBody CreateMemoryRequest r) {
    var response=MemoryResponse.from(operations.createMemory(r.content(),r.type(),r.scope(),r.confidence()));
    return ResponseEntity.created(URI.create("/api/v1/memories/"+response.id())).body(response);
  }
  @DeleteMapping("/memories/{id}") public ResponseEntity<Void> deleteMemory(@PathVariable String id) { operations.deleteMemory(id); return ResponseEntity.noContent().build(); }
  @PostMapping("/skills/reload") public SkillReloadResponse reload(@RequestBody(required=false) ReloadSkillsRequest r) { return new SkillReloadResponse(operations.reloadSkills()); }
}
