package dev.mikoto2000.rei.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import com.google.re2j.Pattern;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Historical report observations. Does not execute tests or certify current-source coverage. */
public final class CoverageImpactService {
  public record Range(String path,int first,int last) {}
  public record Report(String path,String format,String status,String sha256,Instant modifiedAt,int measuredLines,int unmappedLines,List<String> warnings) {public Report{warnings=List.copyOf(warnings);}}
  public record Line(int number,String status,List<String> reportedTests,List<String> reports) {public Line{reportedTests=List.copyOf(reportedTests);reports=List.copyOf(reports);}}
  public record Area(String path,String sourceSha256,List<Line> lines) {public Area{lines=List.copyOf(lines);}}
  public record Result(String status,boolean partial,List<Report> reports,List<Area> areas,List<String> warnings) {public Result{reports=List.copyOf(reports);areas=List.copyOf(areas);warnings=List.copyOf(warnings);}}
  private record Row(String source,String packageName,int line,Boolean covered,String test) {}
  private record Parsed(String format,List<String> roots,List<Row> rows) {}
  private record Snapshot(byte[] bytes,String sha,Instant modified) {}
  private record Evidence(String path,int line,Boolean covered,String test,String report,Instant modified) {}
  private final RepositoryMapService maps;
  public CoverageImpactService(RepositoryMapService maps){this.maps=maps;}
  public static Result absent(){return new Result("NO_REPORTS",true,List.of(),List.of(),List.of("No coverage report supplied; structural candidates are not coverage"));}
  public Result analyze(Path directory,List<Range> ranges,List<String> reports)throws IOException{return analyze(maps.snapshot(directory),ranges,reports);}
  Result analyze(RepositoryMapService.View view,List<Range> ranges,List<String> reportPaths)throws IOException {
    RunCancellation.propagate(null);Path root=Path.of(view.root()).toRealPath();var requested=new LinkedHashMap<String,SortedSet<Integer>>();
    if(ranges==null||ranges.size()>64)throw new IllegalArgumentException("At most 64 changed ranges required");int count=0;
    for(var range:ranges){if(range==null||range.first()<1||range.last()<range.first()||range.last()>1000000||range.last()-range.first()>255)throw new IllegalArgumentException("Invalid changed line range");String path=relative(range.path());var numbers=requested.computeIfAbsent(path,key->new TreeSet<>());for(int line=range.first();line<=range.last();line++)if(numbers.add(line)&&++count>256)throw new IllegalArgumentException("Changed lines exceed 256");}
    if(reportPaths==null)reportPaths=List.of();if(reportPaths.size()>8||new HashSet<>(reportPaths).size()!=reportPaths.size())throw new IllegalArgumentException("At most eight distinct coverage reports required");
    var receipts=new ArrayList<Report>();var facts=new ArrayList<Evidence>();var warnings=new LinkedHashSet<String>();
    var indexed=new HashMap<String,RepositoryMapService.File>();view.items().forEach(file->indexed.put(file.path(),file));
    long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    for(String input:reportPaths) {
      String path=reportPath(input);RunCancellation.propagate(null);Snapshot snapshot;
      try{if(System.nanoTime()-deadline>=0)throw new IOException("Coverage observation deadline exceeded");snapshot=read(root,path);}
      catch(IOException unavailable){receipts.add(new Report(path,"UNKNOWN","UNAVAILABLE","",null,0,0,List.of("Report unavailable, changed or exceeds bounds")));continue;}
      Parsed parsed;
      try{parsed=parse(snapshot.bytes(),path,deadline);}
      catch(IOException invalid){receipts.add(new Report(path,"UNKNOWN","INVALID",snapshot.sha(),snapshot.modified(),0,0,List.of("Coverage report invalid or unsupported; no partial evidence retained")));continue;}
      int unmapped=0;var reportWarnings=new LinkedHashSet<String>();
      for(var row:parsed.rows()) {RunCancellation.propagate(null);String source=mapSource(root,path,row,parsed,indexed);
        if(source==null){unmapped++;continue;}
        facts.add(new Evidence(source,row.line(),row.covered(),row.test(),path,snapshot.modified()));
      }
      if(unmapped>0)reportWarnings.add("Some source paths were missing, outside scope or ambiguous; no guessed mapping");
      receipts.add(new Report(path,parsed.format(),"OBSERVED",snapshot.sha(),snapshot.modified(),parsed.rows().size(),unmapped,List.copyOf(reportWarnings)));
    }
    var areas=new ArrayList<Area>();
    for(var entry:requested.entrySet()) {
      RunCancellation.propagate(null);var file=indexed.get(entry.getKey());Instant sourceTime=null;
      if(file!=null)try {Path source=root.resolve(file.path());if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&source.toRealPath().startsWith(root))sourceTime=Files.getLastModifiedTime(source,LinkOption.NOFOLLOW_LINKS).toInstant();}catch(IOException unavailable){warnings.add("Some changed sources became unavailable");}
      var lines=new ArrayList<Line>();
      for(int number:entry.getValue()) {
        var observed=facts.stream().filter(fact->fact.path().equals(entry.getKey())&&fact.line()==number&&fact.covered()!=null).toList();
        Instant modified=sourceTime;var fresh=observed.stream().filter(fact->modified!=null&&!fact.modified().isBefore(modified)&&!fact.modified().isAfter(Instant.now().plusSeconds(60))).toList();
        String status=fresh.stream().anyMatch(fact->Boolean.TRUE.equals(fact.covered()))?"REPORTED_COVERED":!fresh.isEmpty()?"REPORTED_UNCOVERED":!observed.isEmpty()?"STALE_REPORT":"UNMEASURED";
        var tests=fresh.stream().filter(fact->Boolean.TRUE.equals(fact.covered())&&!fact.test().isEmpty()).map(Evidence::test).distinct().sorted().toList();
        lines.add(new Line(number,status,tests,observed.stream().map(Evidence::report).distinct().sorted().toList()));
      }
      areas.add(new Area(entry.getKey(),file==null?"":file.sha256(),lines));
    }
    if(requested.isEmpty())warnings.add("Changed lines unspecified; file-level candidates cannot imply changed-line coverage");
    if(view.partial())warnings.add("Source index partial; source mappings may be incomplete");
    warnings.add("Saved report observations only; current source revision and test execution are not independently verified");
    String status=reportPaths.isEmpty()?"NO_REPORTS":receipts.stream().anyMatch(report->report.status().equals("OBSERVED"))?"REPORT_OBSERVATIONS":"NO_USABLE_REPORTS";
    return new Result(status,true,receipts,areas,List.copyOf(warnings));
  }
  private static String relative(String input){if(input==null||input.isBlank()||input.length()>1024||input.contains(":")||input.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Project-relative path required");Path path=Path.of(input.replace('\\','/'));if(path.isAbsolute()||path.getRoot()!=null||path.normalize().startsWith(".."))throw new IllegalArgumentException("Project-relative path required");for(var part:path)if(part.toString().equals(".."))throw new IllegalArgumentException("Path traversal rejected");return path.normalize().toString().replace('\\','/');}
  private static String reportPath(String input){String path=relative(input);for(var part:Path.of(path))if(!Set.of("target","build","coverage").contains(part.toString())&&RepositoryMapService.sensitive(part))throw new IllegalArgumentException("Sensitive report path rejected");if(!path.toLowerCase(Locale.ROOT).matches(".*\\.(xml|info|lcov)$"))throw new IllegalArgumentException("XML/LCOV report required");return path;}
  private static Snapshot read(Path root,String path)throws IOException {
    Path file=root;for(var part:Path.of(path)){file=file.resolve(part);if(Files.isSymbolicLink(file))throw new IOException("Symbolic report path rejected");}
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new IOException("Report outside root or unavailable");
    var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);byte[] bytes;
    try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(2097153);}if(bytes.length>2097152)throw new IOException("Report exceeds 2MiB");
    var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!after.isRegularFile()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey())||!file.toRealPath().startsWith(root))throw new IOException("Report changed");
    Path checked=root;for(var part:Path.of(path)){checked=checked.resolve(part);if(Files.isSymbolicLink(checked))throw new IOException("Report path changed");}
    return new Snapshot(bytes,hash(bytes),before.lastModifiedTime().toInstant());
  }
  private static Parsed parse(byte[] bytes,String path,long deadline)throws IOException {
    String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    return path.toLowerCase(Locale.ROOT).endsWith(".xml")?xml(text,deadline):lcov(text,deadline);
  }
  private static void budget(long deadline){RunCancellation.propagate(null);if(System.nanoTime()-deadline>=0)throw new IllegalArgumentException("Coverage parsing deadline exceeded");}
  private static long number(String value){if(value==null||!value.matches("[0-9]{1,12}"))throw new IllegalArgumentException("Invalid coverage count");return Long.parseLong(value);}
  private static int lineNumber(String value){long number=number(value);if(number<1||number>1000000)throw new IllegalArgumentException("Invalid coverage line");return (int)number;}
  private static String bounded(String value){if(value==null||value.length()>1024||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid coverage source");return value;}
  private static String test(String value){String safe=CredentialRedactor.redact(value);return value.length()<=256&&safe.equals(value)&&value.codePoints().noneMatch(Character::isISOControl)?safe:"";}
  private static Parsed lcov(String text,long deadline)throws IOException {
    var rows=new ArrayList<Row>();var pending=new ArrayList<Row>();String source=null,test="";var seen=new HashSet<Integer>();int records=0;
    try {
      for(String line:text.split("\\r?\\n",-1)){budget(deadline);if(++records>50000)throw new IllegalArgumentException("LCOV record limit");if(line.isBlank()||line.startsWith("#"))continue;
        if(line.startsWith("TN:")){if(source!=null)throw new IllegalArgumentException("TN inside section");test=test(line.substring(3));}
        else if(line.startsWith("SF:")||line.startsWith("KF:")){if(source!=null)throw new IllegalArgumentException("Unclosed LCOV section");source=bounded(line.substring(3));seen.clear();}
        else if(line.equals("end_of_record")){if(source==null)throw new IllegalArgumentException("LCOV section missing");rows.addAll(pending);if(rows.size()>10000)throw new IllegalArgumentException("Coverage line limit");pending.clear();source=null;test="";}
        else {if(source==null)throw new IllegalArgumentException("Coverage record outside section");
          if(line.startsWith("DA:")){String[] parts=line.substring(3).split(",",-1);if(parts.length<2||parts.length>3)throw new IllegalArgumentException("Invalid DA record");int number=lineNumber(parts[0]);long hits=number(parts[1]);if(!seen.add(number))throw new IllegalArgumentException("Duplicate line record");if(parts.length==3&&!parts[2].matches("[a-fA-F0-9]{32}"))throw new IllegalArgumentException("Invalid line checksum");pending.add(new Row(source,"",number,hits>0,test));if(pending.size()>10000)throw new IllegalArgumentException("Coverage line limit");}
          else if(!line.matches("(?:VER|FN|FNL|FNA|FNDA|FNF|FNH|BRDA|BRF|BRH|MCDC|LF|LH):.*"))throw new IllegalArgumentException("Unsupported LCOV record");
        }
      }
      if(source!=null||!pending.isEmpty()||rows.isEmpty())throw new IllegalArgumentException("Incomplete or empty LCOV report");return new Parsed("LCOV",List.of(),List.copyOf(rows));
    }catch(IllegalArgumentException invalid){throw new IOException("LCOV invalid",invalid);}
  }
  private static Parsed xml(String text,long deadline)throws IOException {
    int start=text.indexOf("<!DOCTYPE");
    if(start>=0){int end=text.indexOf('>',start);if(end<0||end-start>512)throw new IOException("Unsupported DOCTYPE");String declaration=text.substring(start,end+1);
      boolean jacoco=Pattern.compile("<!DOCTYPE\\s+report\\s+PUBLIC\\s+[\"']-//JACOCO//DTD Report 1\\.[01]//EN[\"']\\s+[\"']report\\.dtd[\"']\\s*>").matcher(declaration).matches();
      boolean cobertura=Pattern.compile("<!DOCTYPE\\s+coverage\\s+SYSTEM\\s+[\"']https?://cobertura\\.sourceforge\\.net/xml/coverage-04\\.dtd[\"']\\s*>").matcher(declaration).matches();
      if(!(jacoco||cobertura)||declaration.contains("["))throw new IOException("External/internal entity declarations rejected");text=text.substring(0,start)+text.substring(end+1);if(text.contains("<!DOCTYPE"))throw new IOException("Duplicate DOCTYPE");
    }
    var collector=new XmlCollector(deadline);
    try {
      var factory=SAXParserFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);
      var parser=factory.newSAXParser();parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
      parser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),collector);if(collector.rows.isEmpty())throw new IOException("No line observations");return new Parsed(collector.format,List.copyOf(collector.roots),List.copyOf(collector.rows));
    }catch(javax.xml.parsers.ParserConfigurationException|SAXException|IllegalArgumentException invalid){RunCancellation.propagate(invalid);throw new IOException("Coverage XML invalid",invalid);}
  }
  private static final class XmlCollector extends DefaultHandler {
    final long deadline;final ArrayDeque<String> stack=new ArrayDeque<>();final List<Row> rows=new ArrayList<>();final List<String> roots=new ArrayList<>();String format="",pkg="",file="";int elements;StringBuilder rootText;
    XmlCollector(long deadline){this.deadline=deadline;}
    @Override public InputSource resolveEntity(String publicId,String systemId)throws SAXException{throw new SAXException("External entities rejected");}
    @Override public void startElement(String uri,String local,String name,Attributes attributes)throws SAXException {
      try {budget(deadline);if(++elements>32768||stack.size()>32)throw new IllegalArgumentException("Coverage XML tree limit");String parent=stack.peek();
        if(stack.isEmpty()){format=name.equals("report")?"JACOCO":name.equals("coverage")?"COBERTURA":"";if(format.isEmpty())throw new IllegalArgumentException("Unknown coverage root");}
        if(format.equals("JACOCO")){if(name.equals("package"))pkg=bounded(attributes.getValue("name"));if(name.equals("sourcefile")&&"package".equals(parent))file=bounded(attributes.getValue("name"));if(name.equals("line")&&"sourcefile".equals(parent)){String ci=attributes.getValue("ci"),mi=attributes.getValue("mi");Boolean covered=ci==null?null:number(ci)>0;if(mi!=null)number(mi);rows.add(new Row(file,pkg,lineNumber(attributes.getValue("nr")),covered,""));}}
        else {if(name.equals("source")&&"sources".equals(parent))rootText=new StringBuilder();if(name.equals("class"))file=bounded(attributes.getValue("filename"));if(name.equals("line")&&"lines".equals(parent)){var ancestors=stack.iterator();ancestors.next();if(ancestors.hasNext()&&ancestors.next().equals("class"))rows.add(new Row(file,"",lineNumber(attributes.getValue("number")),number(attributes.getValue("hits"))>0,""));}}
        if(rows.size()>10000)throw new IllegalArgumentException("Coverage line limit");stack.push(name);
      }catch(IllegalArgumentException invalid){throw new SAXException(invalid);}
    }
    @Override public void characters(char[] chars,int offset,int length)throws SAXException{if(rootText!=null&&"source".equals(stack.peek())){if(rootText.length()+length>1024)throw new SAXException("Source root limit");rootText.append(chars,offset,length);}}
    @Override public void endElement(String uri,String local,String name)throws SAXException {budget(deadline);if(name.equals("source")&&rootText!=null){if(roots.size()>=16)throw new SAXException("Source root count limit");roots.add(rootText.toString().strip());rootText=null;}if(name.equals("sourcefile")||name.equals("class"))file="";if(name.equals("package"))pkg="";stack.pop();}
    @Override public void error(SAXParseException error)throws SAXException{throw error;}
    @Override public void fatalError(SAXParseException error)throws SAXException{throw error;}
  }
  private static String mapSource(Path root,String report,Row row,Parsed parsed,Map<String,RepositoryMapService.File> files) {
    if(parsed.format().equals("JACOCO")){var candidates=files.values().stream().filter(file->file.language().equals("JAVA")&&file.packageName().replace('.','/').equals(row.packageName())&&Path.of(file.path()).getFileName().toString().equals(row.source())).toList();var local=candidates.stream().filter(file->!file.module().equals(".")&&report.startsWith(file.module()+"/")).toList();if(!local.isEmpty())candidates=local;return candidates.size()==1?candidates.getFirst().path():null;}
    var candidates=new LinkedHashSet<String>();String direct=sourcePath(root,row.source());if(direct!=null&&files.containsKey(direct))candidates.add(direct);
    for(String prefix:parsed.roots()){String base=sourcePath(root,prefix);if(base==null)continue;String combined=sourcePath(root,(base.equals(".")?"":base+"/")+row.source());if(combined!=null&&files.containsKey(combined))candidates.add(combined);}
    return candidates.size()==1?candidates.iterator().next():null;
  }
  private static String sourcePath(Path root,String value){try {if(value==null||value.isBlank()||value.length()>1024||value.contains("://")||value.codePoints().anyMatch(Character::isISOControl))return null;Path path=Path.of(value.replace('\\','/'));for(var part:path)if(part.toString().equals(".."))return null;if(path.isAbsolute()){path=path.normalize();if(!path.startsWith(root))return null;path=root.relativize(path);}else if(path.getRoot()!=null)return null;path=path.normalize();if(path.startsWith(".."))return null;return path.toString().isEmpty()?".":path.toString().replace('\\','/');}catch(InvalidPathException invalid){return null;}}
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
