package dev.mikoto2000.rei.computeruse;
import java.util.List;
public record ComputerObservation(String goal, CapturedScreen screenshot,
    List<String> recentHistory, int step, int maxSteps) {
  public ComputerObservation { recentHistory = List.copyOf(recentHistory); }
}
