package dev.mikoto2000.rei.paper;

import java.util.List;

public interface AcademicSearchProvider {
  List<Paper> search(PaperSearchQuery query, PaperOperation op);
}
