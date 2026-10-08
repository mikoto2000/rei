package dev.mikoto2000.rei.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import org.springframework.stereotype.Component;

/** Bounded Java AST and explicit multilingual heuristic index; never executes source or build configuration. */
@Component
public final class RepositoryMapService {
  @FunctionalInterface public interface Inventory { List<String> files(Path root)throws IOException; }
  public record Symbol(String name,String kind,long line,boolean entryPoint) { }
  public record File(String path,String module,String packageName,String sha256,String status,List<Symbol> symbols,List<String> imports,String language,String analysisMode) {
    public File(String path,String module,String packageName,String sha256,String status,List<Symbol> symbols,List<String> imports){this(path,module,packageName,sha256,status,symbols,imports,HeuristicSourceIndex.language(path),path.endsWith(".java")?"JAVA_AST":HeuristicSourceIndex.source(path)?"HEURISTIC":"INVENTORY_ONLY");}
  }
  public record Relation(String source,String target,String kind) { }
  public record ModuleSummary(String name,int files,int parsedJavaFiles,int testFiles) { }
  public record PackageSummary(String name,int files) { }
  public record EntryPoint(String path,String symbol,long line) { }
  public record Summary(int files,int parsedJavaFiles,int unparsedJavaFiles,int inventoryOnlyFiles,
      List<ModuleSummary> modules,List<PackageSummary> packages,List<EntryPoint> entryPoints,
      int importRelations,int testNameCandidates,boolean partial,int heuristicFiles) {
    public Summary(int files,int parsedJavaFiles,int unparsedJavaFiles,int inventoryOnlyFiles,List<ModuleSummary> modules,List<PackageSummary> packages,List<EntryPoint> entryPoints,int importRelations,int testNameCandidates,boolean partial){this(files,parsedJavaFiles,unparsedJavaFiles,inventoryOnlyFiles,modules,packages,entryPoints,importRelations,testNameCandidates,partial,0);}
  }
  public record View(String root,Instant scannedAt,String version,int filesScanned,boolean partial,List<String> warnings,List<File> items,List<Relation> relations,Summary summary) { }
  private record Parsed(String packageName,String status,List<Symbol> symbols,List<String> imports) { }
  private record Cached(String digest,Parsed parsed) { }
  private final Inventory inventory;
  private final JavaCompiler compiler;
  private SqliteRepositoryMapIndex persistentIndex;
  private final LinkedHashMap<Path,Map<String,Cached>> caches=new LinkedHashMap<>(4,.75f,true);
  public RepositoryMapService(){this(RepositoryMapService::gitFiles);}
  RepositoryMapService(Inventory inventory){this(inventory,ToolProvider.getSystemJavaCompiler());}
  RepositoryMapService(Inventory inventory,JavaCompiler compiler){this.inventory=inventory;this.compiler=compiler;}
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void configurePersistentIndex(javax.sql.DataSource data,@org.springframework.beans.factory.annotation.Value("${rei.repository-map.persistent-index-enabled:false}") boolean enabled){if(enabled)persistentIndex=new SqliteRepositoryMapIndex(data);}
  void setPersistentIndex(SqliteRepositoryMapIndex index){persistentIndex=index;}
  public synchronized View map(Path directory,String query,int limit)throws IOException {
    return build(directory,query,limit,false);
  }
  synchronized View snapshot(Path directory)throws IOException { return build(directory,"",100,true); }
  /** Reuse the same Java AST and hash-keyed metadata cache without rereading source bytes. */
  synchronized File describeSnapshot(Path directory,FileSnapshots.Snapshot snapshot)throws IOException {
    RunCancellation.propagate(null);Path root=directory.toRealPath();Path path=snapshot.path();
    if(!path.startsWith(root) || sensitive(root.relativize(path)))throw new IOException("Outside/excluded repository snapshot");
    String name=root.relativize(path).toString().replace('\\','/');byte[] bytes=snapshot.bytes();
    Parsed parsed;
    var previous=caches.getOrDefault(root,Map.of());var cached=previous.get(name);
    if(bytes.length>131072)parsed=new Parsed("","TOO_LARGE",List.of(),List.of());
    else if(cached!=null && cached.digest().equals(snapshot.version()))parsed=cached.parsed();
    else if(compiler==null)parsed=new Parsed("","COMPILER_UNAVAILABLE",List.of(),List.of());
    else try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)){
      parsed=parse(compiler,manager,path,StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }catch(java.nio.charset.CharacterCodingException invalid){parsed=new Parsed("","UNREADABLE",List.of(),List.of());}
    var next=new TreeMap<>(previous);next.put(name,new Cached(snapshot.version(),parsed));while(next.size()>1024)next.pollFirstEntry();
    caches.put(root,Map.copyOf(next));while(caches.size()>2)caches.remove(caches.keySet().iterator().next());
    return new File(name,module(name),parsed.packageName(),snapshot.version(),parsed.status(),parsed.symbols(),parsed.imports());
  }
  private View build(Path directory,String query,int limit,boolean complete)throws IOException {
    RunCancellation.propagate(null);
    if(limit<1 || limit>100 || (query!=null && query.length()>256))throw new IllegalArgumentException("Map limit must be 1 to 100 and query at most 256 characters");
    Path root=directory.toRealPath();long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    var paths=inventory.files(root).stream().distinct().sorted().toList();
    var previous=new HashMap<>(caches.getOrDefault(root,Map.of()));var next=new HashMap<String,Cached>();
    if(persistentIndex!=null)try{persistentIndex.load(root).forEach((path,entry)->previous.putIfAbsent(path,new Cached(entry.digest(),new Parsed(entry.packageName(),"PARSED",entry.symbols(),entry.imports()))));}
    catch(RuntimeException error){RunCancellation.propagate(error);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Repository metadata index read failed ({})",error.getClass().getSimpleName());}
    var files=new ArrayList<File>();var warnings=new LinkedHashSet<String>();
    if(paths.size()>1024)warnings.add("File inventory limited to 1024 entries");
    var boundaries=HeuristicSourceIndex.boundaries(root,paths,deadline);warnings.addAll(boundaries.warnings());long bytesRead=boundaries.bytes();
    try(var manager=compiler==null?null:compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)) {
      for(String relative:paths.stream().limit(1024).toList()) {
        RunCancellation.propagate(null);
        if(System.nanoTime()-deadline>=0){warnings.add("Index time budget exhausted");break;}
        Path path;
        try {
          var input=Path.of(relative);if(input.isAbsolute() || input.normalize().startsWith("..") || sensitive(input))continue;
          path=root.resolve(input).normalize();
          if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root))continue;
        }catch(IOException | InvalidPathException error){warnings.add("Some inventory paths were unavailable");continue;}
        String name=root.relativize(path).toString().replace('\\','/');
        String digest="";Parsed parsed=new Parsed("","INVENTORY_ONLY",List.of(),List.of());
        if(HeuristicSourceIndex.source(name)) {
          try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            if(bytesRead+131073>16*1024*1024){warnings.add("Source byte budget exhausted");parsed=new Parsed("","SKIPPED_LIMIT",List.of(),List.of());}
            else {
              byte[] bytes=stream.readNBytes(131073);bytesRead+=bytes.length;
              if(bytes.length>131072){warnings.add("Oversized source skipped");parsed=new Parsed("","TOO_LARGE",List.of(),List.of());}
              else {
                digest=hash(bytes);String cacheKey=name.endsWith(".java")?digest:digest+":"+boundaries.forPath(name).root();var cached=previous.get(name);
                if(cached!=null && cached.digest().equals(cacheKey))parsed=cached.parsed();
                else if(name.endsWith(".java")){if(compiler==null)parsed=new Parsed("","COMPILER_UNAVAILABLE",List.of(),List.of());else parsed=parse(compiler,manager,path,StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());}
                else {var result=HeuristicSourceIndex.parse(name,StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(),boundaries.forPath(name));parsed=new Parsed(result.packageName(),result.status(),result.symbols(),result.imports());}
                next.put(name,new Cached(cacheKey,parsed));
              }
            }
          }catch(IOException error){parsed=new Parsed("","UNREADABLE",List.of(),List.of());}
          if(!name.endsWith(".java"))warnings.add("Multilingual heuristic analysis is incomplete; no semantic compiler/module resolver, dynamic import or alias guarantee");
          if(!Set.of("PARSED","HEURISTIC").contains(parsed.status()))warnings.add("Some sources could not be fully indexed: "+parsed.status());
        }
        files.add(new File(name,boundaries.forPath(name).root(),parsed.packageName(),digest,parsed.status(),parsed.symbols(),parsed.imports()));
      }
    }
    RunCancellation.propagate(null);caches.put(root,Map.copyOf(next));while(caches.size()>2)caches.remove(caches.keySet().iterator().next());
    if(persistentIndex!=null && warnings.stream().allMatch(value->value.startsWith("Multilingual heuristic analysis")))try{persistentIndex.replace(root,next.entrySet().stream().filter(e->e.getValue().parsed().status().equals("PARSED")).map(e->new SqliteRepositoryMapIndex.Entry(e.getKey(),e.getValue().digest(),e.getValue().parsed().packageName(),e.getValue().parsed().symbols(),e.getValue().parsed().imports())).toList());}
    catch(RuntimeException error){RunCancellation.propagate(error);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Repository metadata index write failed ({})",error.getClass().getSimpleName());}
    String fingerprint=hash((boundaries.values()+files.stream().map(f->f.path()+":"+f.sha256()+":"+f.status()).reduce("",(a,b)->a+"\n"+b)).getBytes(StandardCharsets.UTF_8));
    String filter=Objects.toString(query,"").toLowerCase(Locale.ROOT);
    var selected=files.stream().filter(f->(f.path()+" "+f.packageName()+" "+f.module()).toLowerCase(Locale.ROOT).contains(filter)
        || f.symbols().stream().anyMatch(s->s.name().toLowerCase(Locale.ROOT).contains(filter))).limit(complete?1024:limit).toList();
    Set<String> visible=new HashSet<>();selected.forEach(f->visible.add(f.path()));
    var combinedRelations=new LinkedHashSet<>(relations(files));combinedRelations.addAll(HeuristicSourceIndex.relations(files,boundaries));var allRelations=List.copyOf(combinedRelations);
    var relations=allRelations.stream().filter(r->visible.contains(r.source()) || visible.contains(r.target())).limit(complete?Long.MAX_VALUE:200).toList();
    return new View(root.toString(),Instant.now(),fingerprint,files.size(),!warnings.isEmpty(),List.copyOf(warnings),selected,relations,summary(files,allRelations,!warnings.isEmpty()));
  }
  /** Observed structure over the bounded scan, independent of the caller's result filter. */
  private static Summary summary(List<File> files,List<Relation> relations,boolean partial) {
    var modules=new TreeMap<String,List<File>>();var packages=new TreeMap<String,Integer>();
    var entries=new ArrayList<EntryPoint>();int parsed=0,unparsed=0,inventory=0,heuristic=0;
    for(var file:files) {
      modules.computeIfAbsent(file.module(),key->new ArrayList<>()).add(file);
      if(file.status().equals("PARSED")) {
        parsed++;packages.merge(file.packageName().isEmpty()?"(default)":file.packageName(),1,Integer::sum);
        for(var symbol:file.symbols())if(symbol.entryPoint())entries.add(new EntryPoint(file.path(),symbol.name(),symbol.line()));
      } else if(file.path().endsWith(".java"))unparsed++;
      else if(file.status().equals("HEURISTIC")){heuristic++;for(var symbol:file.symbols())if(symbol.entryPoint())entries.add(new EntryPoint(file.path(),symbol.name(),symbol.line()));}
      else inventory++;
      if(file.symbols().size()>=64)partial=true;
    }
    boolean truncated=modules.size()>100||packages.size()>100||entries.size()>100;
    var moduleSummaries=modules.entrySet().stream().limit(100).map(entry->new ModuleSummary(entry.getKey(),entry.getValue().size(),
        (int)entry.getValue().stream().filter(file->file.status().equals("PARSED")).count(),
        (int)entry.getValue().stream().filter(file->HeuristicSourceIndex.test(file.path())).count())).toList();
    var packageSummaries=packages.entrySet().stream().limit(100).map(entry->new PackageSummary(entry.getKey(),entry.getValue())).toList();
    return new Summary(files.size(),parsed,unparsed,inventory,moduleSummaries,packageSummaries,
        entries.stream().sorted(Comparator.comparing(EntryPoint::path).thenComparing(EntryPoint::symbol)).limit(100).toList(),
        (int)relations.stream().filter(relation->Set.of("IMPORT","HEURISTIC_IMPORT").contains(relation.kind())).count(),
        (int)relations.stream().filter(relation->Set.of("TEST_NAME_CANDIDATE","HEURISTIC_TEST_NAME_CANDIDATE").contains(relation.kind())).count(),partial||truncated,heuristic);
  }
  private static Parsed parse(JavaCompiler compiler,StandardJavaFileManager manager,Path path,String text) {
    var diagnostics=new DiagnosticCollector<JavaFileObject>();
    var source=new SimpleJavaFileObject(path.toUri(),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};
    var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,List.of(source));
    try {
      var unit=task.parse().iterator().next();
      if(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR))return new Parsed("","SYNTAX_ERROR",List.of(),List.of());
      String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();var symbols=new ArrayList<Symbol>();
      var owners=new ArrayDeque<String>();var positions=Trees.instance(task).getSourcePositions();
      new TreeScanner<Void,Void>() {
        String owner(){return (pkg.isEmpty()?"":pkg+".")+String.join(".",owners);}
        void add(Tree tree,String name,String kind,boolean entry){if(symbols.size()<64)symbols.add(new Symbol(name,kind,unit.getLineMap().getLineNumber(positions.getStartPosition(unit,tree)),entry));}
        @Override public Void visitClass(ClassTree tree,Void ignored) {
          if(tree.getSimpleName().isEmpty())return null;
          owners.addLast(tree.getSimpleName().toString());add(tree,owner(),tree.getKind().name(),false);super.visitClass(tree,ignored);owners.removeLast();return null;
        }
        @Override public Void visitMethod(MethodTree tree,Void ignored) {
          boolean main=tree.getName().contentEquals("main") && tree.getReturnType()!=null && tree.getReturnType().toString().equals("void")
              && tree.getModifiers().getFlags().containsAll(Set.of(javax.lang.model.element.Modifier.PUBLIC,javax.lang.model.element.Modifier.STATIC))
              && tree.getParameters().size()==1 && Set.of("String[]","java.lang.String[]").contains(tree.getParameters().getFirst().getType().toString());
          add(tree,owner()+"."+tree.getName(),"METHOD",main);return super.visitMethod(tree,ignored);
        }
      }.scan(unit,null);
      var imports=unit.getImports().stream().limit(128).map(i->(i.isStatic()?"static ":"")+i.getQualifiedIdentifier()).toList();
      return new Parsed(pkg,symbols.size()==64 || unit.getImports().size()>128?"DECLARATION_LIMIT":"PARSED",List.copyOf(symbols),imports);
    }catch(IOException | RuntimeException error){RunCancellation.propagate(error);return new Parsed("","SYNTAX_ERROR",List.of(),List.of());}
  }
  private static List<Relation> relations(List<File> files) {
    var types=new HashMap<String,List<File>>();for(var file:files)if(file.language().equals("JAVA"))for(var symbol:file.symbols())if(!symbol.kind().equals("METHOD"))types.computeIfAbsent(symbol.name(),k->new ArrayList<>()).add(file);
    var result=new LinkedHashSet<Relation>();
    for(var file:files) {
      if(!file.language().equals("JAVA"))continue;
      for(String imported:file.imports()) {
        String name=imported;if(name.startsWith("static ")){name=name.substring(7);int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);}
        var targets=types.get(name);if(targets!=null && targets.size()==1 && !targets.getFirst().path().equals(file.path()))result.add(new Relation(file.path(),targets.getFirst().path(),"IMPORT"));
      }
      if(file.path().contains("/test/") || file.path().startsWith("test/"))for(var symbol:file.symbols())if(!symbol.kind().equals("METHOD") && symbol.name().endsWith("Test")) {
        var targets=types.get(symbol.name().substring(0,symbol.name().length()-4));if(targets!=null && targets.size()==1)result.add(new Relation(file.path(),targets.getFirst().path(),"TEST_NAME_CANDIDATE"));
      }
    }
    return List.copyOf(result);
  }
  private static String module(String path){int marker=path.indexOf("/src/");return marker<0?".":path.substring(0,marker);}
  static boolean sensitive(Path path) {
    for(var part:path) {String name=part.toString().toLowerCase(Locale.ROOT);if(Set.of(".git",".codex",".agents",".aws",".m2","target","build","node_modules").contains(name)
        || name.matches("\\.env(?:\\..*)?|credentials(?:[._].*)?|secrets(?:[._].*)?|.*\\.(key|pem)"))return true;}return false;
  }
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
  private static List<String> gitFiles(Path root)throws IOException {
    var output=new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner().run(List.of("git","--no-optional-locks","-c","core.fsmonitor=false","ls-files","-z","--cached","--others","--exclude-standard"),
        root,"",Duration.ofSeconds(5),Duration.ofSeconds(5),1048576,()->Thread.currentThread().isInterrupted());
    if(output.status()==dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException();
    if(output.status()!=dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.SUCCESS || output.truncated())throw new IOException("Git inventory unavailable or exceeds byte limit");
    return Arrays.stream(output.stdout().split("\u0000")).filter(s->!s.isEmpty()).toList();
  }
}
