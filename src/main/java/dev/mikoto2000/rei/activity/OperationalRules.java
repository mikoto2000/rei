package dev.mikoto2000.rei.activity;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parsing/compilation occurs only on reload. Readers hold one immutable generation. */
public final class OperationalRules {
  static final Set<String> CATEGORIES=Set.of("development","research","documentation","communication","social","media","shopping","gaming","monitoring","navigation","idle","other","unknown");
  private static final Set<String> MATCH=Set.of("processRegex","titleRegex","serviceRegex","applicationRegex","contentRegex","activityCategoryRegex");
  private static final Set<String> CLASSIFY=Set.of("category","service","application","projectCandidate","contentCandidate","categoryConfidence","serviceConfidence","applicationConfidence","projectConfidence","contentConfidence","entertainmentDisposition","confidence");
  public record Rule(String id,String type,String source,int priority,boolean enabled,Map<String,String> match,Map<String,Object> classify,Map<String,Pattern> compiled) {
    boolean matches(String process,String title,String service,String app,String content,String category) {
      var values=Map.of("processRegex",safe(process),"titleRegex",safe(title),"serviceRegex",safe(service),"applicationRegex",safe(app),"contentRegex",safe(content),"activityCategoryRegex",safe(category));
      return enabled && compiled.entrySet().stream().allMatch(e->e.getValue().matcher(values.get(e.getKey())).find());
    }
    int specificity(){return match.size();}
  }
  public record Snapshot(List<Rule> rules,Instant loadedAt) {}
  public record Entertainment(EntertainmentDisposition disposition,double confidence,String ruleId,String source,String reason) {}
  private final Path path;
  private final AtomicReference<Snapshot> current=new AtomicReference<>(new Snapshot(builtIns(),Instant.now()));
  private volatile Instant lastAttempt=Instant.now();
  private volatile String lastStatus="BUILT_IN";
  private volatile String fingerprint="";
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OperationalRules.class);
  public OperationalRules(Path path){this.path=path;reload();}
  public Path path(){return path;}
  public Snapshot snapshot(){return current.get();}
  public String reloadStatus(){return lastStatus+" at "+lastAttempt;}
  public synchronized boolean reload() {
    lastAttempt=Instant.now();
    try {
      if(Files.exists(path) && Files.size(path)>262144)throw invalid();
      boolean exists=Files.exists(path);String text=exists?Files.readString(path):"";
      fingerprint=exists?digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)):"missing";
      var users=compile(text,false);var rules=new ArrayList<>(builtIns());
      var ids=new HashSet<String>();rules.forEach(r->ids.add(r.id()));
      for(var rule:users)if(!ids.add(rule.id()))throw invalid();else rules.add(rule);
      rules.sort(order());current.set(new Snapshot(List.copyOf(rules),lastAttempt));
      lastStatus="SUCCESS";log.info("Activity classification rules loaded/reloaded: rules={}",rules.size());return true;
    }catch(Exception e){lastStatus="FAILED (invalid or unreadable rules; previous rules retained)";log.warn("Activity classification rules reload failed; previous rules retained");return false;}
  }
  public void poll(){if(!Objects.equals(fingerprint,fingerprint()))reload();}
  private String fingerprint(){try{return Files.exists(path)?Files.size(path)>262144?"oversize":digest(Files.readAllBytes(path)):"missing";}catch(Exception e){return "unreadable";}}
  private static String digest(byte[] bytes) throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
  static Comparator<Rule> order(){return Comparator.comparingInt(Rule::priority).reversed().thenComparing(Comparator.comparingInt(Rule::specificity).reversed()).thenComparing(Rule::id);}
  public ActivityClassification classify(ActivityEvidence evidence) {
    var base=new ActivityClassifier().classify(evidence);var a=base.inference().activities().getFirst();
    var generation=snapshot();
    var candidates=new ArrayList<>(matching(generation,"classification",evidence.foreground().processName(),evidence.foreground().windowTitle(),a.service(),a.application(),a.contentTitle(),a.type()).stream().filter(r->r.source().equals("user")).toList());
    generation.rules().stream().filter(r->r.source().equals("built-in") && r.id().equals(base.reason())).findFirst().ifPresent(candidates::add);
    candidates.sort(order());
    // Phase 3.7's dynamic built-ins (project/title capture) keep their existing inference.
    if(candidates.isEmpty())return base;
    var r=candidates.getFirst();var v=r.classify();
    boolean conflict=candidates.stream().skip(1).anyMatch(other->other.priority()==r.priority() && other.specificity()==r.specificity()
        && !string(other.classify(),"category",a.type()).equals(string(v,"category",a.type())));
    if(r.source().equals("built-in")) {
      if(!conflict)return base;
      var fields=base.fieldConfidence();var uncertain=new ActivityFieldConfidence(0,fields.application(),fields.service(),fields.project(),fields.content());
      return new ActivityClassification(base.inference(),0,base.sources(),base.sourceConfidence(),base.reason()+"|CONFLICTING_RULES",uncertain,base.secondaryConfidence());
    }
    var primary=new ActivityRecord.Activity(a.monitor(),string(v,"category",a.type()),string(v,"application",a.application()),string(v,"service",a.service()),string(v,"contentCandidate",a.contentTitle()),string(v,"projectCandidate",a.projectCandidate()));
    var f=base.fieldConfidence();
    var axes=new ActivityFieldConfidence(conflict || primary.type().equals("unknown")?0:conf(v,"categoryConfidence",.8),
        primary.application().isBlank()?0:conf(v,"applicationConfidence",v.containsKey("application")?.9:f.application()),
        primary.service().isBlank()?0:conf(v,"serviceConfidence",v.containsKey("service")?.9:f.service()),
        primary.projectCandidate().isBlank()?0:conf(v,"projectConfidence",v.containsKey("projectCandidate")?.8:f.project()),
        primary.contentTitle().isBlank()?0:conf(v,"contentConfidence",v.containsKey("contentCandidate")?.8:f.content()));
    var activities=new ArrayList<>(base.inference().activities());activities.set(0,primary);
    var sources=new LinkedHashSet<>(base.sources());sources.add("USER_RULE");var weights=new LinkedHashMap<>(base.sourceConfidence());weights.put("USER_RULE",axes.overall());
    return new ActivityClassification(new ActivityRecord.Inference(base.inference().summary(),activities),axes.overall(),List.copyOf(sources),Map.copyOf(weights),r.id()+(conflict?"|CONFLICTING_RULES":""),axes,base.secondaryConfidence());
  }
  public Entertainment entertainment(String service,String app,String title,String content,String category) {
    var hits=matching(snapshot(),"entertainment",app,title,service,app,content,category);
    if(hits.isEmpty())return new Entertainment(EntertainmentDisposition.UNCERTAIN,0,"","none",content.isBlank()?"INSUFFICIENT_CONTENT_CONTEXT":"NO_ENTERTAINMENT_RULE_MATCH");
    var r=hits.getFirst();double confidence=conf(r.classify(),"confidence",0);
    boolean conflict=hits.stream().skip(1).anyMatch(h->h.priority()==r.priority() && h.specificity()==r.specificity() && !h.classify().get("entertainmentDisposition").equals(r.classify().get("entertainmentDisposition")));
    return new Entertainment(conflict || confidence<.8?EntertainmentDisposition.UNCERTAIN:EntertainmentDisposition.valueOf(string(r.classify(),"entertainmentDisposition","UNCERTAIN")),confidence,r.id(),r.source(),conflict?"CONFLICTING_ENTERTAINMENT_RULES":confidence<.8?"LOW_ENTERTAINMENT_CONFIDENCE":"MATCHED");
  }
  public static List<Rule> matching(Snapshot snapshot,String type,String process,String title,String service,String app,String content,String category) {
    return snapshot.rules().stream().filter(r->r.type().equals(type) && r.matches(process,title,service,app,content,category)).sorted(order()).toList();
  }
  public static List<Rule> compile(String text,boolean proposal) {
    if(text.length()>262144)throw invalid();
    var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(262144);
    Object loaded=new Yaml(new SafeConstructor(options)).load(text);if(loaded==null)return List.of();
    var root=map(loaded);keys(root,Set.of("classificationRules","entertainmentRules"));
    var result=new ArrayList<Rule>();var ids=new HashSet<String>();
    for(String type:List.of("classification","entertainment")) {
      Object list=root.get(type+"Rules");if(list==null)continue;if(!(list instanceof List<?> entries) || entries.size()>500)throw invalid();
      for(Object entry:entries) {
        var r=map(entry);keys(r,Set.of("id","enabled","priority","match","classify","confidence"));
        if(!(r.get("id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id))throw invalid();
        int priority=integer(r.getOrDefault("priority",100));if(priority<0 || priority>10000)throw invalid();
        Object enabled=r.getOrDefault("enabled",true);if(!(enabled instanceof Boolean))throw invalid();
        var match=map(r.get("match"));keys(match,MATCH);if(match.isEmpty())throw invalid();
        if(type.equals("classification") && match.keySet().stream().anyMatch(k->!Set.of("processRegex","titleRegex").contains(k)))throw invalid();
        var patterns=new LinkedHashMap<String,Pattern>();var strings=new LinkedHashMap<String,String>();
        for(var e:match.entrySet()) {
          if(!(e.getValue() instanceof String regex) || regex.isBlank() || regex.length()>256)throw invalid();
          // Disallow backreferences and nested repetitions: untrusted rules must not stall capture.
          if(regex.matches(".*\\\\[1-9].*") || Pattern.compile("\\)[+*{]").matcher(regex).find() || Pattern.compile("[+*]").matcher(regex).results().count()>2)throw invalid();
          var pattern=Pattern.compile(regex);patterns.put(e.getKey(),pattern);strings.put(e.getKey(),regex);
        }
        var classify=new LinkedHashMap<>(map(r.get("classify")));keys(classify,CLASSIFY);
        if(r.containsKey("confidence"))for(var e:map(r.get("confidence")).entrySet()) {
          if(!Set.of("category","service","application","project","content").contains(e.getKey()))throw invalid();
          if(classify.putIfAbsent(e.getKey()+"Confidence",e.getValue())!=null)throw invalid();
        }
        for(var e:classify.entrySet()) {
          if(e.getKey().endsWith("Confidence") || e.getKey().equals("confidence"))number(e.getValue());
          else if(!(e.getValue() instanceof String s) || s.length()>256)throw invalid();
        }
        if(type.equals("classification")) {
          if(!CATEGORIES.contains(string(classify,"category","")) || classify.containsKey("entertainmentDisposition") || classify.containsKey("confidence"))throw invalid();
          if(string(classify,"category","").equals("unknown") && conf(classify,"categoryConfidence",0)>0)throw invalid();
        }else {
          keys(classify,Set.of("entertainmentDisposition","confidence"));
          try{EntertainmentDisposition.valueOf(string(classify,"entertainmentDisposition",""));}catch(Exception e){throw invalid();}
          if(!classify.containsKey("confidence"))throw invalid();
        }
        if(proposal) {
          boolean contextual=patterns.entrySet().stream().anyMatch(e->Set.of("titleRegex","contentRegex").contains(e.getKey()) && !broad(e.getValue()));
          boolean scoped=patterns.entrySet().stream().anyMatch(e->Set.of("processRegex","serviceRegex","applicationRegex").contains(e.getKey()) && !broad(e.getValue()));
          if(!contextual || !scoped || patterns.values().stream().anyMatch(OperationalRules::broad))throw invalid();
        }
        result.add(new Rule(id,type,"user",priority,(Boolean)enabled,Map.copyOf(strings),Map.copyOf(classify),Map.copyOf(patterns)));
      }
    }
    result.sort(order());return List.copyOf(result);
  }
  private static boolean broad(Pattern p){return p.matcher("").find() || List.of("ordinary unrelated page","random video","nothing relevant").stream().allMatch(s->p.matcher(s).find());}
  static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> m))throw invalid();var result=new LinkedHashMap<String,Object>();for(var e:m.entrySet()){if(!(e.getKey() instanceof String s))throw invalid();result.put(s,e.getValue());}return result;}
  private static void keys(Map<String,?> map,Set<String> allowed){if(!allowed.containsAll(map.keySet()))throw invalid();}
  static double number(Object value){if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0 || n.doubleValue()>1)throw invalid();return n.doubleValue();}
  private static int integer(Object value){if(!(value instanceof Number n) || n.doubleValue()!=n.intValue())throw invalid();return n.intValue();}
  static double conf(Map<String,Object> m,String key,double fallback){return m.containsKey(key)?number(m.get(key)):fallback;}
  static String string(Map<String,Object> m,String key,String fallback){return m.containsKey(key)?Objects.toString(m.get(key)):fallback;}
  private static String safe(String value){return value==null?"":value.substring(0,Math.min(512,value.length()));}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid rule definition (id, match, classify, regex, priority or confidence)");}
  private static List<Rule> builtIns() {
    try(var in=OperationalRules.class.getResourceAsStream("/activity/operational-rules.yaml")) {
      var rules=compile(new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),false);
      var result=new ArrayList<>(BrowserTitleRules.catalog());
      for(String id:List.of("EDITOR_TITLE","CHATGPT_PROCESS","GENERIC_TERMINAL","GENERIC_BROWSER","UNRECOGNIZED_PROCESS"))result.add(new Rule(id,"classification","built-in",100,true,Map.of(),Map.of(),Map.of()));
      rules.stream().filter(r->r.type().equals("entertainment")).map(r->new Rule(r.id(),r.type(),"built-in",r.priority(),r.enabled(),r.match(),r.classify(),r.compiled())).forEach(result::add);
      return List.copyOf(result);
    }catch(Exception e){throw new IllegalStateException("Bundled rules unavailable",e);}
  }
}
