package dev.mikoto2000.rei.computeruse;
import java.util.List;
public record ComputerObservation(String goal, CapturedScreen screenshot,
    List<String> recentHistory, int step, int maxSteps, java.nio.file.Path diagnosticRun) {
  public ComputerObservation(String goal, CapturedScreen screenshot, List<String> recentHistory, int step, int maxSteps) {
    this(goal,screenshot,recentHistory,step,maxSteps,null);
  }
  public ComputerObservation { recentHistory = List.copyOf(recentHistory); }
}
