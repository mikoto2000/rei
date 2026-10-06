package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Bounded discovery of saved observations, never an assertion about the current test process. */
public final class TestReportCollectionDiagnosisService {
  public record Counts(long tests,long failures,long errors,long skipped) {
    Counts add(TestReportDiagnosisService.Counts other) {
      return new Counts(tests+other.tests(),failures+other.failures(),errors+other.errors(),skipped+other.skipped());
    }
  }
  public record Summary(String path,String sha256,Instant modifiedAt,Instant observedAt,boolean partial,
      TestReportDiagnosisService.Counts reported,TestReportDiagnosisService.Counts observed,List<String> warnings) {}
  public record Evidence(String path,TestReportDiagnosisService.FailedTest issue) {}
  public record Unavailable(String path,String reason) {}
  public record Result(String mode,String directory,Instant observedAt,boolean partial,Counts reported,Counts observed,
      List<String> discoveredPaths,List<Summary> reports,List<Evidence> failedTests,List<Unavailable> unavailable,List<String> nextActions,List<String> warnings) {}
  private enum Kind { MODULE,TARGET,BUILD,RESULTS,REPORT }
  private record Folder(Path path,Kind kind,int moduleDepth) {}

  public Result read(Path directory,String relative)throws IOException {
    RunCancellation.propagate(null);
    Path root=directory.toRealPath();
    boolean automatic=relative==null;
    Path start=automatic?root:resolveFolder(root,relative);
    var warnings=new LinkedHashSet<String>();
    var files=new TreeSet<String>();
    var pending=new ArrayDeque<Folder>();
    pending.add(new Folder(start,automatic?Kind.MODULE:Kind.REPORT,0));
    int folders=0,entries=0;
    long deadline=System.nanoTime()+10_000_000_000L;
    discovery: while(!pending.isEmpty()) {
      RunCancellation.propagate(null);
      if(System.nanoTime()>deadline){warnings.add("Observation time limit reached");break;}
      if(++folders>256){warnings.add("Directory discovery limited to 256 folders");break;}
      var folder=pending.removeFirst();
      var children=new ArrayList<Path>();
      try {
        checkWithinRoot(root,folder.path());
        var before=Files.readAttributes(folder.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isDirectory())throw new IOException("Not a directory");
        try(var stream=Files.newDirectoryStream(folder.path())) {
          for(var child:stream) {
            RunCancellation.propagate(null);
            if(++entries>4096){warnings.add("Directory discovery limited to 4096 entries");break discovery;}
            children.add(child);
          }
        }
        var after=Files.readAttributes(folder.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        checkWithinRoot(root,folder.path());
        if(!after.isDirectory()||!Objects.equals(before.fileKey(),after.fileKey())
            ||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Directory changed");
      } catch(IOException error) { warnings.add("A report discovery directory was unavailable or changed");continue; }
      children.sort(Comparator.comparing(p->p.getFileName().toString()));
      for(var child:children) {
        String name=child.getFileName().toString();
        if(excluded(child.getFileName()))continue;
        if(Files.isSymbolicLink(child)){warnings.add("Symbolic paths excluded from discovery");continue;}
        if(Files.isRegularFile(child,LinkOption.NOFOLLOW_LINKS)) {
          if((folder.kind()==Kind.REPORT||folder.kind()==Kind.RESULTS)&&name.startsWith("TEST")&&name.endsWith(".xml")) {
            if(files.size()==32){warnings.add("Report discovery limited to 32 files");break discovery;}
            files.add(root.relativize(child).toString().replace('\\','/'));
          }
          continue;
        }
        if(!Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))continue;
        Kind next=switch(folder.kind()) {
          case MODULE -> name.equals("target")?Kind.TARGET:name.equals("build")?Kind.BUILD:Kind.MODULE;
          case TARGET -> Set.of("surefire-reports","failsafe-reports").contains(name)?Kind.REPORT:null;
          case BUILD -> name.equals("test-results")?Kind.RESULTS:null;
          case RESULTS -> Kind.REPORT;
          case REPORT -> null;
        };
        if(next==null)continue;
        if(next==Kind.MODULE&&folder.moduleDepth()==6){warnings.add("Module discovery depth limited to 6");continue;}
        pending.addLast(new Folder(child,next,folder.moduleDepth()+(next==Kind.MODULE?1:0)));
      }
    }
    var reports=new ArrayList<Summary>();var evidence=new ArrayList<Evidence>();var unavailable=new ArrayList<Unavailable>();
    Counts observed=new Counts(0,0,0,0),reported=observed;boolean completeCounts=true;
    var reader=new TestReportDiagnosisService();
    // At most 32 reads of the existing 1MiB reader; concurrent growth does not bypass its cap.
    for(var file:files) {
      RunCancellation.propagate(null);
      if(System.nanoTime()>deadline){warnings.add("Observation time limit reached");break;}
      try {
        var result=reader.read(root,file);
        reports.add(new Summary(result.path(),result.sha256(),result.modifiedAt(),result.observedAt(),result.partial(),result.reported(),result.observed(),result.warnings()));
        observed=observed.add(result.observed());
        if(result.reported()==null)completeCounts=false;else reported=reported.add(result.reported());
        if(result.partial())warnings.add("An individual report was partial or inconsistent");
        for(var issue:result.failedTests()) {
          if(evidence.size()<24)evidence.add(new Evidence(result.path(),issue));
          else warnings.add("Collection failure evidence limited to 24 issues");
        }
      } catch(IOException|IllegalArgumentException error) {
        unavailable.add(new Unavailable(file,"report_unavailable_or_invalid"));
        warnings.add("A discovered report was unavailable or invalid");
      }
    }
    if(reports.isEmpty())warnings.add("No readable saved reports observed");
    boolean partial=!warnings.isEmpty();
    warnings.add("Saved report observations only; current process status and freshness are not independently verified");
    warnings.add("Counts cover analyzed files only; files may describe overlapping tests or different runs");
    String displayDirectory=root.relativize(start.normalize()).toString().replace('\\','/');
    return new Result(automatic?"STANDARD_DISCOVERY":"EXPLICIT_DIRECTORY",displayDirectory.isEmpty()?".":displayDirectory,Instant.now(),partial,
        completeCounts&&!reports.isEmpty()?reported:null,observed,List.copyOf(files),List.copyOf(reports),List.copyOf(evidence),List.copyOf(unavailable),
        List.of("Confirm report paths, timestamps and test execution identity before interpreting totals",
            "Inspect partial warnings and rerun the relevant tests before claiming current success"),List.copyOf(warnings));
  }
  private static Path resolveFolder(Path root,String relative)throws IOException {
    if(relative.isBlank()||relative.length()>1024||relative.contains(":"))throw new IllegalArgumentException("Project-relative report directory required");
    Path path=Path.of(relative.replace('\\','/'));
    if(path.isAbsolute()||path.getRoot()!=null)throw new IllegalArgumentException("Project-relative report directory required");
    for(var part:path)if(part.toString().equals("..")||part.toString().codePoints().anyMatch(Character::isISOControl)||excluded(part))
      throw new IllegalArgumentException("Excluded report directory");
    Path resolved=root.resolve(path);checkWithinRoot(root,resolved);
    if(!Files.isDirectory(resolved,LinkOption.NOFOLLOW_LINKS))throw new IOException("Report directory unavailable");
    return resolved;
  }
  private static boolean excluded(Path part) {
    String name=part.toString();
    return !Set.of("target","build").contains(name)&&(name.startsWith(".")&&!name.equals(".")||RepositoryMapService.sensitive(part));
  }
  private static void checkWithinRoot(Path root,Path path)throws IOException {
    Path checked=root;
    for(var part:root.relativize(path)){checked=checked.resolve(part);if(Files.isSymbolicLink(checked))throw new IOException("Symbolic report path rejected");}
    if(!path.toRealPath().startsWith(root))throw new IOException("Report outside Project");
  }
}
