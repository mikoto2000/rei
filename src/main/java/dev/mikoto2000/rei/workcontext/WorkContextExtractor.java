package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.workcontext.WorkContext.Evidence;
import java.util.List;

/** 作業文脈に必要な情報を入力から取り出す。 */
public interface WorkContextExtractor {
  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  List<WorkContextCandidate> extract(
      List<Evidence> evidence, List<WorkContext.Item> existing, String runStatus);
}
