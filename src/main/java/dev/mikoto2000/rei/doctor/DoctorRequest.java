package dev.mikoto2000.rei.doctor;

import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

/** Bounded explicit diagnostic grammar: no URL, executable or prompt supplied by users. */
public record DoctorRequest(List<Check> checks, boolean details) {
  public enum Check { CONNECTIVITY, INFERENCE, CODEX, CLAUDE, MICROPHONE }
  public DoctorRequest {checks=List.copyOf(checks);}
  static class Options {
    @Option(names="--check") List<Check> checks=new ArrayList<>();
    @Option(names="--details") boolean details;
  }
  public static DoctorRequest parse(String[] args) {
    if(args==null || args.length>32 || Arrays.stream(args).anyMatch(Objects::isNull)
        || Arrays.stream(args).mapToInt(String::length).sum()>4096) throw new IllegalArgumentException("Doctor input exceeds limit");
    var options=new Options();
    try {new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);}
    catch(CommandLine.ParameterException invalid) {throw new IllegalArgumentException("Usage: /doctor [--details] [--check connectivity|inference|codex|claude|microphone]");}
    return new DoctorRequest(options.checks.stream().distinct().toList(),options.details);
  }
  public static boolean accepts(String text) {return text!=null && (text.equals("/doctor")||text.startsWith("/doctor "));}
  public static DoctorRequest parseText(String text) {
    if(!accepts(text))throw new IllegalArgumentException("Doctor command required");
    var args=new dev.mikoto2000.rei.core.command.UserInputParser().split(text);
    return parse(Arrays.copyOfRange(args,1,args.length));
  }
  public String plan() {
    return "Active diagnosis requested: "+checks+". connectivity: unauthenticated GET /v1/models; inference: one bounded fixed prompt; codex/claude: configured CLI --version only; microphone: acquire and close without starting recording. Only requested checks run, after existing permission checks.";
  }
}
