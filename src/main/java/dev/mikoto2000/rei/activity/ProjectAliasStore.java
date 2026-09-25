package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
/** Demand reload of one alias/group snapshot. Invalid edits retain the entire last good configuration. */
public final class ProjectAliasStore implements java.util.function.Supplier<ProjectNameNormalizer> {
  private final Path file;
  private SummaryThemeConfiguration current=new SummaryThemeConfiguration(new ProjectNameNormalizer(Map.of()),SummaryThemeGroups.empty());
  private String previous;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ProjectAliasStore.class);
  public ProjectAliasStore(Path file){this.file=file;}
  @Override public ProjectNameNormalizer get(){return snapshot().projects();}
  public synchronized SummaryThemeConfiguration snapshot() {
    try {
      if(Files.exists(file) && Files.size(file)>65536)throw new IllegalArgumentException("Summary configuration too large");
      String text=Files.exists(file)?Files.readString(file):"";
      if(Objects.equals(text,previous))return current;
      previous=text;
      var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(65536);
      Object data=new Yaml(new SafeConstructor(options)).load(text);
      var aliases=new LinkedHashMap<String,List<String>>();Object groupData=null;
      if(data!=null) {
        if(!(data instanceof Map<?,?> root) || root.isEmpty() || !Set.of("projectAliases","themeGroups").containsAll(root.keySet()))
          throw new IllegalArgumentException("Invalid summary configuration");
        if(root.containsKey("projectAliases")) {
          if(!(root.get("projectAliases") instanceof Map<?,?> entries))throw new IllegalArgumentException("Invalid aliases");
          for(var e:entries.entrySet()) {
            if(!(e.getKey() instanceof String name) || !(e.getValue() instanceof List<?> values)
                || values.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("Invalid alias values");
            aliases.put(name,values.stream().map(String.class::cast).toList());
          }
        }
        groupData=root.get("themeGroups");
        if(root.containsKey("themeGroups") && groupData==null)throw new IllegalArgumentException("Invalid groups");
      }
      var names=new ProjectNameNormalizer(aliases);
      current=new SummaryThemeConfiguration(names,SummaryThemeGroups.parse(groupData,names));
      log.debug("[summary-theme] config file={} exists={} canonicalAndAliasEntries={} groups={}",
          file.toAbsolutePath().normalize(),Files.exists(file),names.aliasCount(),current.groups().groups());
    }catch(Exception error){log.warn("Daily summary configuration unavailable; last valid alias/group snapshot retained");}
    return current;
  }
}
