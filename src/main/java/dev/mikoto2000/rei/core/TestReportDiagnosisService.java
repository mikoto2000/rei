package dev.mikoto2000.rei.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Saved report observations only. Does not run tests or establish current Run/Goal success. */
public final class TestReportDiagnosisService {
  public record Counts(int tests,int failures,int errors,int skipped) {}
  public record FailedTest(String test,String kind,String type,String message,String detail) {}
  public record TestCase(String test,String outcome) {}
  public record Result(String path,String sha256,Instant modifiedAt,Instant observedAt,boolean partial,
      Counts reported,Counts observed,List<FailedTest> failedTests,List<String> nextActions,List<String> warnings,List<TestCase> testCases) {
    public Result{testCases=testCases==null?List.of():List.copyOf(testCases);}
    public Result(String path,String sha256,Instant modifiedAt,Instant observedAt,boolean partial,Counts reported,Counts observed,List<FailedTest> failedTests,List<String> nextActions,List<String> warnings){this(path,sha256,modifiedAt,observedAt,partial,reported,observed,failedTests,nextActions,warnings,List.of());}
  }
  public Result read(Path directory,String relative)throws IOException {
    RunCancellation.propagate(null);
    if(relative==null||relative.isBlank()||relative.length()>1024||relative.contains(":"))throw new IllegalArgumentException("Project-relative XML report required");
    Path input=Path.of(relative.replace('\\','/'));
    if(input.isAbsolute()||input.getRoot()!=null||!relative.toLowerCase(Locale.ROOT).endsWith(".xml"))throw new IllegalArgumentException("Project-relative XML report required");
    for(var part:input)if(part.toString().equals("..")||part.toString().codePoints().anyMatch(Character::isISOControl)
        ||!Set.of("target","build").contains(part.toString())&&RepositoryMapService.sensitive(part))throw new IllegalArgumentException("Excluded report path");
    Path root=directory.toRealPath(),file=root;
    for(var part:input){file=file.resolve(part);if(Files.isSymbolicLink(file))throw new IOException("Symbolic report path rejected");}
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new IOException("Report unavailable within Project");
    var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    byte[] bytes;
    try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(1048577);}
    if(bytes.length>1048576)throw new IOException("Report exceeds 1MiB");
    var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    if(!after.isRegularFile()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())
        ||!Objects.equals(before.fileKey(),after.fileKey())||!file.toRealPath().startsWith(root))throw new IOException("Report changed during observation");
    Path checked=root;for(var part:input){checked=checked.resolve(part);if(Files.isSymbolicLink(checked))throw new IOException("Symbolic report path rejected");}
    var document=parse(bytes);var top=document.getDocumentElement();
    if(!Set.of("testsuite","testsuites").contains(top.getTagName()))throw new IOException("JUnit testsuite/testsuites required");
    // Bound the entire tree, including ignored properties and output, before interpreting cases.
    var pending=new ArrayDeque<Node>();pending.add(top);int nodes=0;
    while(!pending.isEmpty()) {
      RunCancellation.propagate(null);if(++nodes>8192)throw new IOException("Report tree exceeds node limit");
      for(Node child=pending.removeFirst().getFirstChild();child!=null;child=child.getNextSibling())pending.addLast(child);
    }
    var warnings=new LinkedHashSet<String>();var failed=new ArrayList<FailedTest>();var outcomes=new ArrayList<TestCase>();
    var cases=top.getElementsByTagName("testcase");int failures=0,errors=0,skipped=0;
    if(cases.getLength()>1024)warnings.add("Test case observation limited to 1024 entries");
    int tests=Math.min(cases.getLength(),1024);
    for(int i=0;i<tests;i++) {
      RunCancellation.propagate(null);var test=(Element)cases.item(i);
      if(!(test.getParentNode() instanceof Element parent)||!parent.getTagName().equals("testsuite"))throw new IOException("Unsupported testcase placement");
      for(Node ancestor=parent;ancestor!=top;ancestor=ancestor.getParentNode())
        if(!(ancestor instanceof Element suite)||!Set.of("testsuite","testsuites").contains(suite.getTagName()))throw new IOException("Unsupported suite placement");
      boolean failure=false,error=false,skip=false;
      for(Node child=test.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element issue) {
        String kind=issue.getTagName();
        if(kind.equals("skipped")){skip=true;continue;}
        if(!Set.of("failure","error").contains(kind))continue;
        if(kind.equals("failure")){if(failure)warnings.add("Multiple issues reported for one test");failure=true;}
        else {if(error)warnings.add("Multiple issues reported for one test");error=true;}
        if(failed.size()<24)failed.add(new FailedTest(excerpt(test.getAttribute("classname")+"#"+test.getAttribute("name"),256,warnings),
            kind.toUpperCase(Locale.ROOT),excerpt(issue.getAttribute("type"),256,warnings),excerpt(issue.getAttribute("message"),512,warnings),excerpt(issue.getTextContent(),2048,warnings)));
        else warnings.add("Failed test evidence limited to 24 issues");
      }
      if(failure)failures++;if(error)errors++;if(skip)skipped++;
      if((failure?1:0)+(error?1:0)+(skip?1:0)>1)warnings.add("Conflicting outcomes reported for one test");
      outcomes.add(new TestCase(excerpt(test.getAttribute("classname")+"#"+test.getAttribute("name"),256,warnings),error?"ERROR":failure?"FAILURE":skip?"SKIPPED":"PASSED"));
    }
    Counts observed=new Counts(tests,failures,errors,skipped),reported=counts(top,warnings);
    if(reported==null)warnings.add("Top-level complete counts unavailable; observed cases may be incomplete");
    else if(!reported.equals(observed))warnings.add("Reported counts differ from observed cases; report is incomplete or inconsistent");
    boolean partial=!warnings.isEmpty();
    warnings.add("Saved report observations only; current process status and freshness are not independently verified");
    var actions=failed.isEmpty()?List.of("Confirm report identity and current process result before claiming test success"):
        List.of("Inspect the reported failing test and diagnostic excerpt","Reproduce the relevant test before choosing a repair");
    return new Result(root.relativize(file).toString().replace('\\','/'),hash(bytes),before.lastModifiedTime().toInstant(),Instant.now(),partial,
        reported,observed,List.copyOf(failed),actions,List.copyOf(warnings),List.copyOf(outcomes));
  }
  private static Counts counts(Element top,Set<String> warnings)throws IOException {
    String[] names={"tests","failures","errors","skipped"};int[] values=new int[4];boolean missing=false;
    for(int i=0;i<4;i++) {
      String value=top.getAttribute(names[i]);if(value.isEmpty()){missing=true;continue;}
      if(!value.matches("[0-9]{1,9}"))throw new IOException("Invalid report count");values[i]=Integer.parseInt(value);
    }
    if(missing)return null;
    if((long)values[1]+values[2]+values[3]>values[0])warnings.add("Inconsistent top-level outcome counts");
    return new Counts(values[0],values[1],values[2],values[3]);
  }
  private static String excerpt(String text,int limit,Set<String> warnings) {
    String safe=CredentialRedactor.redact(text).replaceAll("(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*","[REDACTED]");
    if(safe.length()>limit){warnings.add("Diagnostic excerpt clipped");safe=safe.substring(0,limit);}
    return safe;
  }
  private static Document parse(byte[] bytes)throws IOException {
    try {
      var factory=DocumentBuilderFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth","64");
      factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
      var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
        @Override public void error(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}
        @Override public void fatalError(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}
      });
      return builder.parse(new ByteArrayInputStream(bytes));
    }catch(javax.xml.parsers.ParserConfigurationException|org.xml.sax.SAXException|IllegalArgumentException error){throw new IOException("JUnit XML parsing unavailable or report invalid",error);}
  }
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
}
