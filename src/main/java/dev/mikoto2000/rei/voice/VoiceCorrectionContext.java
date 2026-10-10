package dev.mikoto2000.rei.voice;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Bounded reference data captured before queueing. Never reads tools, long-term memory or project files. */
public final class VoiceCorrectionContext {
  public record Term(String canonical,String reading,List<String> aliases,String description) {}
  public record Snapshot(String projectId,String projectName,List<String> history,List<Term> terms) {}
  private final ConversationLogStore history;
  private final Path data;
  private final VoiceCorrectionProperties limits;
  public VoiceCorrectionContext(ConversationLogStore history,Path data,VoiceCorrectionProperties limits) {
    this.history=history;this.data=data;this.limits=limits;
  }
  public Snapshot capture(ConversationInput input) {
    var target=input.target();var recent=new ArrayList<String>();int remaining=limits.getMaxContextChars();
    if(history!=null && remaining>0) {
      var entries=history.recentConversation(target.project().id(),target.sessionId(),4);
      for(var entry:entries.reversed()) {
        if(!Set.of("user","assistant").contains(entry.speaker()))continue;
        String safe=clip(CredentialRedactor.redact(entry.speaker()+": "+entry.content()),remaining);
        if(!safe.isEmpty())recent.addFirst(safe);
        remaining-=safe.codePointCount(0,safe.length());if(remaining<=0)break;
      }
    }
    Path base=data==null?ReiDataDirectory.current():data;
    Path project=data==null?ProjectStorage.directory(target.project().id()):base.resolve("projects").resolve(target.project().id());
    return new Snapshot(target.project().id(),clip(CredentialRedactor.redact(target.project().name()),80),List.copyOf(recent),
        dictionaries(base.resolve("voice/terms.json"),project.resolve("voice/terms.json"),limits.getMaxDictionaryEntries()));
  }
  static List<Term> dictionaries(Path common,Path project,int max) {
    var merged=new LinkedHashMap<String,Term>();
    // Project entries precede common entries and override by canonical spelling or reading/alias collision.
    var occupied=new HashSet<String>();
    for(Path file:List.of(project,common)) for(Term term:read(file,max)) {
      var keys=new HashSet<String>(term.aliases());keys.add(term.canonical());if(!term.reading().isBlank())keys.add(term.reading());
      if(keys.stream().anyMatch(occupied::contains))continue;
      if(merged.size()>=max)break;merged.put(term.canonical(),term);occupied.addAll(keys);
    }
    return List.copyOf(merged.values());
  }
  private static List<Term> read(Path file,int max) {
    var result=new ArrayList<Term>();
    try {
      if(max==0 || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return List.of();
      byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(65537);}
      if(bytes.length>65536)return List.of();
      var array=new ObjectMapper().readTree(bytes);if(!array.isArray() || array.size()>128)return List.of();
      for(var item:array) {
        if(!item.isObject() || !item.path("canonical").isTextual())continue;
        String canonical=safe(item.path("canonical").asText(),80);if(canonical.isBlank())continue;
        var aliases=new ArrayList<String>();var values=item.path("aliases");
        if(values.isArray())for(var alias:values){if(aliases.size()>=8)break;if(alias.isTextual())aliases.add(safe(alias.asText(),80));}
        result.add(new Term(canonical,safe(item.path("reading").asText(""),80),List.copyOf(aliases),safe(item.path("description").asText(""),160)));
        if(result.size()>=max)break;
      }
    }catch(Exception ignored){return List.of();} // Fixed diagnostics in the caller; no filenames or contents in logs.
    return List.copyOf(result);
  }
  private static String safe(String text,int max) { return clip(CredentialRedactor.redact(text),max); }
  static String clip(String text,int max) { int count=text.codePointCount(0,text.length());return count<=max?text:text.substring(0,text.offsetByCodePoints(0,max)); }
}
