package dev.mikoto2000.rei.paper;

import java.util.List;

/** Academic検索を用途や設定に応じて提供する窓口。 */
public interface AcademicSearchProvider {
  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  List<Paper> search(PaperSearchQuery query, PaperOperation op);
}
