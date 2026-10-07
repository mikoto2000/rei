package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoverageImpactServiceTest {
  @TempDir Path root;List<String> sources=new ArrayList<>();
  void write(String path,String text)throws Exception{Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
  void source(String path,String text)throws Exception{write(path,text);sources.add(path);}
  CoverageImpactService service(){return new CoverageImpactService(new RepositoryMapService(directory->List.copyOf(sources)));}
  CoverageImpactService.Result read(String path,int first,int last,String... reports)throws Exception{return service().analyze(root,List.of(new CoverageImpactService.Range(path,first,last)),List.of(reports));}
  CoverageImpactService.Line line(CoverageImpactService.Result result,int number){return result.areas().getFirst().lines().stream().filter(value->value.number()==number).findFirst().orElseThrow();}
  @Test void lcovTestIdentityMapsReportedHitsWithoutInventingUnmeasuredCoverage()throws Exception {
    source("src/helper.ts","export function help() {\n return 1;\n}\n");write("coverage/lcov.info","TN:helper.test.ts#returnsOne\nSF:src/helper.ts\nDA:1,2\nDA:2,0\nend_of_record\n");
    var result=read("src/helper.ts",1,3,"coverage/lcov.info");assertEquals("REPORT_OBSERVATIONS",result.status());assertEquals("LCOV",result.reports().getFirst().format());assertEquals(64,result.reports().getFirst().sha256().length());
    assertEquals("REPORTED_COVERED",line(result,1).status());assertEquals(List.of("helper.test.ts#returnsOne"),line(result,1).reportedTests());assertEquals("REPORTED_UNCOVERED",line(result,2).status());assertEquals("UNMEASURED",line(result,3).status());assertTrue(line(result,2).reportedTests().isEmpty());assertTrue(result.partial());
  }
  @Test void jacocoStandardDoctypeIsReadOfflineAndAggregateHasNoInventedTests()throws Exception {
    source("src/main/java/p/A.java","package p;\nclass A {}\n");write("target/site/jacoco/jacoco.xml","<?xml version=\"1.0\"?><!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\"><report name=\"fixture\"><package name=\"p\"><sourcefile name=\"A.java\"><line nr=\"1\" mi=\"0\" ci=\"3\"/><line nr=\"2\" mi=\"2\" ci=\"0\"/></sourcefile></package></report>");
    var result=read("src/main/java/p/A.java",1,2,"target/site/jacoco/jacoco.xml");assertEquals("JACOCO",result.reports().getFirst().format());assertEquals("REPORTED_COVERED",line(result,1).status());assertEquals("REPORTED_UNCOVERED",line(result,2).status());assertTrue(line(result,1).reportedTests().isEmpty());
  }
  @Test void coberturaRelativeSourceRootsMapActualLineHits()throws Exception {
    source("src/main/java/p/A.java","package p;\nclass A {}\n");write("coverage/cobertura.xml","<coverage><sources><source>src/main/java</source></sources><packages><package name=\"p\"><classes><class filename=\"p/A.java\" name=\"p.A\"><lines><line number=\"1\" hits=\"1\"/><line number=\"2\" hits=\"0\"/></lines></class></classes></package></packages></coverage>");
    var result=read("src/main/java/p/A.java",1,2,"coverage/cobertura.xml");assertEquals("COBERTURA",result.reports().getFirst().format());assertEquals("REPORTED_COVERED",line(result,1).status());assertEquals("REPORTED_UNCOVERED",line(result,2).status());assertTrue(line(result,1).reportedTests().isEmpty());
  }
  @Test void absentMissingAndIncompleteReportsAreNeverZeroCoverageSuccess()throws Exception {
    source("helper.js","export function help() {}\n");assertEquals("NO_REPORTS",read("helper.js",1,1).status());var missing=read("helper.js",1,1,"coverage/missing.xml");assertEquals("NO_USABLE_REPORTS",missing.status());assertEquals("UNAVAILABLE",missing.reports().getFirst().status());assertEquals("UNMEASURED",line(missing,1).status());
    write("coverage/broken.info","TN:test\nSF:helper.js\nDA:1,1\n");var incomplete=read("helper.js",1,1,"coverage/broken.info");assertEquals("INVALID",incomplete.reports().getFirst().status());assertEquals("UNMEASURED",line(incomplete,1).status());
  }
  @Test void staleReportIsHistoricalAndDoesNotClaimCurrentCoveringTests()throws Exception {
    source("helper.py","def help():\n    return 1\n");write("coverage/lcov.info","TN:test_help\nSF:helper.py\nDA:1,1\nend_of_record\n");Files.setLastModifiedTime(root.resolve("coverage/lcov.info"),FileTime.from(Instant.EPOCH));
    var result=read("helper.py",1,1,"coverage/lcov.info");assertEquals("STALE_REPORT",line(result,1).status());assertTrue(line(result,1).reportedTests().isEmpty());assertTrue(result.partial());
  }
  @Test void entityDoctypeEscapesNegativeCountsAndOversizedReportsCannotSupplyEvidence()throws Exception {
    source("helper.ts","export function help() {}\n");write("coverage/entity.xml","<!DOCTYPE report [<!ENTITY secret SYSTEM \"file:///outside/secret\">]><report name=\"&secret;\"/>");var entity=read("helper.ts",1,1,"coverage/entity.xml");assertEquals("INVALID",entity.reports().getFirst().status());
    assertThrows(IllegalArgumentException.class,()->read("helper.ts",1,1,"../outside.xml"));write("coverage/negative.info","SF:helper.ts\nDA:1,-1\nend_of_record\n");assertEquals("INVALID",read("helper.ts",1,1,"coverage/negative.info").reports().getFirst().status());
    write("coverage/large.info","x".repeat(2100000));assertEquals("UNAVAILABLE",read("helper.ts",1,1,"coverage/large.info").reports().getFirst().status());
  }
  @Test void ambiguousJacocoSourceAndOutsideLcovPathsDoNotGuessCoverage()throws Exception {
    source("one/src/p/A.java","package p; class A {}\n");source("two/src/p/A.java","package p; class A {}\n");write("coverage/jacoco.xml","<report name=\"fixture\"><package name=\"p\"><sourcefile name=\"A.java\"><line nr=\"1\" ci=\"3\" mi=\"0\"/></sourcefile></package></report>");assertEquals("UNMEASURED",line(read("one/src/p/A.java",1,1,"coverage/jacoco.xml"),1).status());
    write("coverage/outside.info","TN:test\nSF:../outside.ts\nDA:1,1\nend_of_record\n");assertEquals("UNMEASURED",line(read("one/src/p/A.java",1,1,"coverage/outside.info"),1).status());
  }
  @Test void existingImpactKeepsStructuralCandidatesAndAddsIndependentCoverageFacts()throws Exception {
    source("src/helper.ts","export function help() {}\n");source("src/helper.test.ts","import { help } from './helper';\n");
    write("coverage/lcov.info","TN:helper.test.ts\nSF:src/helper.ts\nDA:1,1\nend_of_record\n");
    var impact=new ChangeTestImpactService(new RepositoryMapService(directory->List.copyOf(sources)));
    var result=impact.analyze(root,List.of("src/helper.ts"),20,List.of(new CoverageImpactService.Range("src/helper.ts",1,1)),List.of("coverage/lcov.info"));
    assertEquals("REPORTED_COVERED",line(result.coverage(),1).status());assertTrue(result.candidates().stream().anyMatch(value->value.path().equals("src/helper.test.ts")));
    assertEquals("BROAD_REGRESSION_REQUIRED",result.regressionAssessment().scope());assertTrue(result.partial());
    assertEquals("NO_REPORTS",impact.analyze(root,List.of("src/helper.ts"),20).coverage().status());
    assertThrows(IllegalArgumentException.class,()->impact.analyze(root,List.of("src/helper.ts"),20,List.of(new CoverageImpactService.Range("other.ts",1,1)),List.of("coverage/lcov.info")));
  }
  @Test void credentialTestNamesCancellationAndBoundsDoNotEscape()throws Exception {
    source("helper.ts","export function help() {}\n");write("coverage/lcov.info","TN:Bearer sk-abcdefghijklmnopqrstuvwxyz1234567890\nSF:helper.ts\nDA:1,1\nend_of_record\n");
    assertTrue(line(read("helper.ts",1,1,"coverage/lcov.info"),1).reportedTests().isEmpty());
    assertThrows(IllegalArgumentException.class,()->read("helper.ts",1,257,"coverage/lcov.info"));
    write("coverage/deep.xml","<report>"+"<group>".repeat(33)+"</group>".repeat(33)+"</report>");assertEquals("INVALID",read("helper.ts",1,1,"coverage/deep.xml").reports().getFirst().status());
    Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->read("helper.ts",1,1,"coverage/lcov.info"));}finally{Thread.interrupted();}
  }
}
