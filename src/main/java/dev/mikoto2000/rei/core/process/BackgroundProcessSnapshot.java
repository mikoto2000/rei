package dev.mikoto2000.rei.core.process;

import java.time.Instant;
import java.util.List;

public record BackgroundProcessSnapshot(
    String processId,
    long pid,
    BackgroundProcessStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant endedAt,
    double elapsedSeconds,
    List<String> stdout,
    List<String> stderr,
    boolean found,
    String message,
    BuildTestFailureDiagnosis diagnosis) {
  public BackgroundProcessSnapshot(String processId,long pid,BackgroundProcessStatus status,Integer exitCode,
      Instant startedAt,Instant endedAt,double elapsedSeconds,List<String> stdout,List<String> stderr,boolean found,String message) {
    this(processId,pid,status,exitCode,startedAt,endedAt,elapsedSeconds,stdout,stderr,found,message,null);
  }
  public BackgroundProcessSnapshot {
    diagnosis=BuildTestFailureDiagnosis.process(status,exitCode,found,stdout,stderr,message);
  }
}
