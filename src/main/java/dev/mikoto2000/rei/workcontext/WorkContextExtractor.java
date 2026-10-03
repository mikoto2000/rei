package dev.mikoto2000.rei.workcontext;
import java.util.List;
import dev.mikoto2000.rei.workcontext.WorkContext.Evidence;
public interface WorkContextExtractor {
  List<WorkContextCandidate> extract(List<Evidence> evidence,List<WorkContext.Item> existing,String runStatus);
}
