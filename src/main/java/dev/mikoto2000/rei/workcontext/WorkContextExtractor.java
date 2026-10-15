package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.workcontext.WorkContext.Evidence;
import java.util.List;

public interface WorkContextExtractor {
  List<WorkContextCandidate> extract(
      List<Evidence> evidence, List<WorkContext.Item> existing, String runStatus);
}
