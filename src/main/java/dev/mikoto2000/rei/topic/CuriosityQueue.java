package dev.mikoto2000.rei.topic;

import java.util.List;

public interface CuriosityQueue {
  void add(CuriosityItem item);
  List<CuriosityItem> findCandidates(CuriosityQuery query);
  void markUsed(String id);
  void dismiss(String id);
}
