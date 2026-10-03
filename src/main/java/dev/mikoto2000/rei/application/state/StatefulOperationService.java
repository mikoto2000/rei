package dev.mikoto2000.rei.application.state;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.feed.*;
import dev.mikoto2000.rei.reminder.*;
import dev.mikoto2000.rei.interest.*;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.EmptyResultDataAccessException;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.List;

/** Validated Web admission over shared domain services. Existing Shell behavior remains in those services. */
@Service
public record StatefulOperationService(FeedService feeds,ReminderService reminders,InterestUpdateService interests,
    MemoryService memories,AgentSkillRepository skills) {
  public Feed createFeed(String url,String displayName) {
    url(url); optionalText(displayName,200);
    try { return feeds.add(url,displayName); }
    catch(DuplicateFeedException | org.springframework.dao.DuplicateKeyException error) { throw new OperationConflictException(); }
  }
  public Feed updateFeed(long id,String displayName,Boolean enabled) {
    positiveId(id); optionalText(displayName,200);
    if(displayName==null && enabled==null) throw new IllegalArgumentException("Empty update");
    try { return feeds.update(id,displayName,enabled); }
    catch(EmptyResultDataAccessException error) { throw new ResourceNotFoundException("Feed"); }
  }
  public void deleteFeed(long id) { positiveId(id); feeds.delete(id); }
  public Reminder createReminder(String message,String at,String target,Integer minutesBefore) {
    text(message,2000);
    if(at!=null && target==null && minutesBefore==null) return reminders.addAt(message,OffsetDateTime.parse(at));
    if(at==null && target!=null && minutesBefore!=null && minutesBefore>=0 && minutesBefore<=525600)
      return reminders.addBefore(message,OffsetDateTime.parse(target),minutesBefore);
    throw new IllegalArgumentException("Specify at or target/minutesBefore");
  }
  public List<Reminder> remindersList() { return reminders.listActive(); }
  public Reminder reminder(long id) {
    positiveId(id);
    try { return reminders.findById(id); }
    catch(EmptyResultDataAccessException error) { throw new ResourceNotFoundException("Reminder"); }
  }
  public void deleteReminder(long id) { positiveId(id); reminders.delete(id); }
  public List<InterestUpdate> interestsList(int hours) {
    if(hours<1 || hours>8760) throw new IllegalArgumentException("Invalid hours");
    return interests.listRecent(hours);
  }
  public InterestUpdate createInterest(String topic,String reason,String query,String summary,List<String> urls) {
    text(topic,200); text(reason,2000); text(query,2000); text(summary,10000);
    if(urls==null || urls.size()>20) throw new IllegalArgumentException("Invalid sources");
    urls.forEach(StatefulOperationService::url);
    synchronized(interests) {
      if(interests.existsBySearchQuery(query)) throw new OperationConflictException();
      return interests.save(topic,reason,query,summary,List.copyOf(urls));
    }
  }
  public List<Memory> memoriesList() { return memories.listActive().stream().filter(StatefulOperationService::unscoped).toList(); }
  public Memory memory(String id) {
    text(id,128);
    return memories.findById(id).filter(StatefulOperationService::unscoped).orElseThrow(()->new ResourceNotFoundException("Memory"));
  }
  public Memory createMemory(String content,String type,String scope,Double confidence) {
    text(content,20000); text(type,64); text(scope,64);
    var memoryType=MemoryType.valueOf(type); var memoryScope=MemoryScope.valueOf(scope);
    // Legacy Memory has no project/session ownership columns. Do not publish contextual memories without verifiable ownership.
    if(memoryScope==MemoryScope.PROJECT || memoryScope==MemoryScope.SESSION) throw new IllegalArgumentException("Unsupported contextual scope");
    double score=confidence==null?0.8:confidence;
    if(!Double.isFinite(score) || score<0 || score>1) throw new IllegalArgumentException("Invalid confidence");
    return memories.save(new Memory(null,content,memoryType,memoryScope,MemoryStatus.ACTIVE,score,null,null,null));
  }
  public void deleteMemory(String id) {
    var current=memory(id);
    if(current.status()!=MemoryStatus.DELETED) memories.updateStatus(id,MemoryStatus.DELETED);
  }
  public int reloadSkills() { return new SkillReloadService(skills).reload(); }
  private static boolean unscoped(Memory memory) { return memory.scope()!=MemoryScope.PROJECT && memory.scope()!=MemoryScope.SESSION; }
  private static void text(String text,int max) { if(text==null || text.isBlank() || text.length()>max) throw new IllegalArgumentException("Invalid input"); }
  private static void optionalText(String value,int max) { if(value!=null) text(value,max); }
  private static void positiveId(long id) { if(id<1) throw new IllegalArgumentException("Invalid id"); }
  private static void url(String value) {
    text(value,4096); var uri=URI.create(value);
    if(!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null)
      throw new IllegalArgumentException("Invalid URL");
  }
}
