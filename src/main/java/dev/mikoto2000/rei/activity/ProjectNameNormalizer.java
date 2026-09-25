package dev.mikoto2000.rei.activity;
import java.util.*;
/** Summary-only normalization. Never merges by edit distance or updates classification evidence. */
public final class ProjectNameNormalizer {
  private final Map<String,String> aliases;
  public ProjectNameNormalizer(Map<String,List<String>> configured) {
    var result=new HashMap<String,String>();
    if(configured==null || configured.size()>200)throw new IllegalArgumentException("Invalid project aliases");
    var canonicalNames=new HashSet<String>();
    for(var entry:configured.entrySet()) {
      var canonical=key(entry.getKey());
      if(!canonicalNames.add(canonical) || canonical.isBlank() || canonical.length()>60 || entry.getValue()==null || entry.getValue().size()>100)
        throw new IllegalArgumentException("Invalid canonical project");
      claim(result,canonical,canonical);
    }
    for(var entry:configured.entrySet()) {
      var canonical=key(entry.getKey());var seen=new HashSet<String>();
      for(var alias:entry.getValue()) {
        var normalized=key(alias);
        if(normalized.isBlank() || normalized.length()>60 || !seen.add(normalized))throw new IllegalArgumentException("Empty or duplicate project alias");
        claim(result,normalized,canonical);
      }
    }
    aliases=Map.copyOf(result);
  }
  private static void claim(Map<String,String> map,String alias,String canonical) {
    var existing=map.putIfAbsent(alias,canonical);
    if(existing!=null && !existing.equals(canonical))throw new IllegalArgumentException("Conflicting project alias");
  }
  private static String key(String value) {
    return ActivityRolePolicy.normalize(value).replaceAll("[\\s_-]+","-");
  }
  public String normalize(String value) {
    var normalized=key(value);
    return aliases.getOrDefault(normalized,normalized);
  }
  public String project(String value) {
    var normalized=key(value);
    if(aliases.containsKey(normalized))return aliases.get(normalized);
    if(normalized.isBlank() || normalized.length()>60 || !normalized.matches("[\\p{L}][\\p{L}\\p{N}-]*"))return "";
    // Implementation identifiers are not projects; an explicit alias may intentionally override this filter.
    if(Set.of("repl","build-and-chat","software-development","suggest-rules","unknown","other","local","browser","terminal","editor","github","chatgpt",
        "youtube","firefox","chrome","powershell","x","code","vscode").contains(normalized)
        || ActivityVocabulary.CATEGORIES.contains(normalized)
        || normalized.matches(".*(factory|controller|configuration)$"))return "";
    return normalized;
  }
}
