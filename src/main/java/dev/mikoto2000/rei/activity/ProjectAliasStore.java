package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
/** Demand reload: no watcher or mutation of operational classification rules. Last good snapshot survives errors. */
public final class ProjectAliasStore implements java.util.function.Supplier<ProjectNameNormalizer> {
  private final Path file;
  private ProjectNameNormalizer current=new ProjectNameNormalizer(Map.of());
  private String previous;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ProjectAliasStore.class);
  public ProjectAliasStore(Path file){this.file=file;}
  @Override public synchronized ProjectNameNormalizer get() {
    try {
      if(Files.exists(file) && Files.size(file)>65536)throw new IllegalArgumentException("Alias file too large");
      String text=Files.exists(file)?Files.readString(file):"";
      if(Objects.equals(text,previous))return current;
      previous=text;
      var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(65536);
      Object data=new Yaml(new SafeConstructor(options)).load(text);
      var aliases=new LinkedHashMap<String,List<String>>();
      if(data!=null) {
        if(!(data instanceof Map<?,?> root) || !root.keySet().equals(Set.of("projectAliases"))
            || !(root.get("projectAliases") instanceof Map<?,?> entries))throw new IllegalArgumentException("Invalid alias document");
        for(var e:entries.entrySet()) {
          if(!(e.getKey() instanceof String name) || !(e.getValue() instanceof List<?> values)
              || values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("Invalid alias values");
          aliases.put(name,values.stream().map(String.class::cast).toList());
        }
      }
      current=new ProjectNameNormalizer(aliases);
      log.debug("Daily summary aliases file={} exists={} canonicalAndAliasEntries={}",file,Files.exists(file),current.aliasCount());
    }catch(Exception error){log.warn("Daily summary aliases unavailable; last valid aliases retained");}
    return current;
  }
}
