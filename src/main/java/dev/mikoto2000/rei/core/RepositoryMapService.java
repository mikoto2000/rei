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

/** Bounded structural Java index. Parse only: never compile, execute processors or send source to an LLM. */
@Component
public final class RepositoryMapService {
  @FunctionalInterface public interface Inventory { List<String> files(Path root)throws IOException; }
  public record Symbol(String name,String kind,long line,boolean entryPoint) { }
  public record File(String path,String module,String packageName,String sha256,String status,List<Symbol> symbols,List<String> imports) { }
  public record Relation(String source,String target,String kind) { }
  public record View(String root,Instant scannedAt,String version,int filesScanned,boolean partial,List<String> warnings,List<File> items,List<Relation> relations) { }
  private record Parsed(String packageName,String status,List<Symbol> symbols,List<String> imports) { }
  private record Cached(String digest,Parsed parsed) { }
  private final Inventory inventory;
  private final LinkedHashMap<Path,Map<String,Cached>> caches=new LinkedHashMap<>(4,.75f,true);
  public RepositoryMapService(){this(RepositoryMapService::gitFiles);}
  RepositoryMapService(Inventory inventory){this.inventory=inventory;}
  public synchronized View map(Path directory,String query,int limit)throws IOException {
    RunCancellation.propagate(null);
    if(limit<1 || limit>100 || (query!=null && query.length()>256))throw new IllegalArgumentException("Map limit must be 1 to 100 and query at most 256 characters");
    Path root=directory.toRealPath();long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    var paths=inventory.files(root).stream().distinct().sorted().toList();
    var previous=caches.getOrDefault(root,Map.of());var next=new HashMap<String,Cached>();
    var files=new ArrayList<File>();var warnings=new LinkedHashSet<String>();
    if(paths.size()>1024)warnings.add("File inventory limited to 1024 entries");
    var compiler=ToolProvider.getSystemJavaCompiler();long bytesRead=0;
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
        if(name.endsWith(".java")) {
          try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            if(bytesRead+131073>16*1024*1024){warnings.add("Source byte budget exhausted");parsed=new Parsed("","SKIPPED_LIMIT",List.of(),List.of());}
            else {
              byte[] bytes=stream.readNBytes(131073);bytesRead+=bytes.length;
              if(bytes.length>131072){warnings.add("Oversized Java source skipped");parsed=new Parsed("","TOO_LARGE",List.of(),List.of());}
              else {
                digest=hash(bytes);var cached=previous.get(name);
                if(cached!=null && cached.digest().equals(digest))parsed=cached.parsed();
                else if(compiler==null)parsed=new Parsed("","COMPILER_UNAVAILABLE",List.of(),List.of());
                else parsed=parse(compiler,manager,path,StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
                next.put(name,new Cached(digest,parsed));
              }
            }
          }catch(IOException error){parsed=new Parsed("","UNREADABLE",List.of(),List.of());}
          if(!parsed.status().equals("PARSED"))warnings.add("Some Java sources could not be fully indexed: "+parsed.status());
        }
        files.add(new File(name,module(name),parsed.packageName(),digest,parsed.status(),parsed.symbols(),parsed.imports()));
      }
    }
    RunCancellation.propagate(null);caches.put(root,Map.copyOf(next));while(caches.size()>2)caches.remove(caches.keySet().iterator().next());
    String fingerprint=hash(files.stream().map(f->f.path()+":"+f.sha256()+":"+f.status()).reduce("",(a,b)->a+"\n"+b).getBytes(StandardCharsets.UTF_8));
    String filter=Objects.toString(query,"").toLowerCase(Locale.ROOT);
    var selected=files.stream().filter(f->(f.path()+" "+f.packageName()+" "+f.module()).toLowerCase(Locale.ROOT).contains(filter)
        || f.symbols().stream().anyMatch(s->s.name().toLowerCase(Locale.ROOT).contains(filter))).limit(limit).toList();
    Set<String> visible=new HashSet<>();selected.forEach(f->visible.add(f.path()));
    var relations=relations(files).stream().filter(r->visible.contains(r.source()) || visible.contains(r.target())).limit(200).toList();
    return new View(root.toString(),Instant.now(),fingerprint,files.size(),!warnings.isEmpty(),List.copyOf(warnings),selected,relations);
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
    var types=new HashMap<String,List<File>>();for(var file:files)for(var symbol:file.symbols())if(!symbol.kind().equals("METHOD"))types.computeIfAbsent(symbol.name(),k->new ArrayList<>()).add(file);
    var result=new LinkedHashSet<Relation>();
    for(var file:files) {
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
  private static boolean sensitive(Path path) {
    for(var part:path) {String name=part.toString().toLowerCase(Locale.ROOT);if(Set.of(".git",".codex",".agents",".aws",".m2","target","build","node_modules").contains(name)
        || name.matches("\\.env(?:\\..*)?|credentials(?:[._].*)?|secrets(?:[._].*)?|.*\\.(key|pem)"))return true;}return false;
  }
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
  private static List<String> gitFiles(Path root)throws IOException {
    var output=new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner().run(List.of("git","ls-files","-z","--cached","--others","--exclude-standard"),
        root,"",Duration.ofSeconds(5),Duration.ofSeconds(5),1048576,()->Thread.currentThread().isInterrupted());
    if(output.status()==dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException();
    if(output.status()!=dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status.SUCCESS || output.truncated())throw new IOException("Git inventory unavailable or exceeds byte limit");
    return Arrays.stream(output.stdout().split("\u0000")).filter(s->!s.isEmpty()).toList();
  }
}
