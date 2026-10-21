package dev.mikoto2000.rei.topic;

import java.util.List;

/** 話題の管理に必要な情報への関心実行待ちを扱う。 */
public interface CuriosityQueue {
  /** 新たな対象を登録し、後から利用できるようにする。 */
  void add(CuriosityItem item);

  /** 対象を特定できるよう、候補一覧を探す。 */
  List<CuriosityItem> findCandidates(CuriosityQuery query);

  /** 後続の判断に現在の状態を反映するため、使用量の状態を記録する。 */
  void markUsed(String id);

  /** 話題の管理に関するdismissを処理する。 */
  void dismiss(String id);
}
