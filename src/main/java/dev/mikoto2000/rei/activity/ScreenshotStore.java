package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.time.Instant;
import java.util.List;

/** 画面画像の記録を保持し、後続の処理で参照できるようにする。 */
public interface ScreenshotStore {
  /** 後から参照できるよう、指定された情報を保存する。 */
  List<String> save(String id, Instant capturedAt, CapturedScreen screen) throws Exception;

  /** 作業状況の把握に関する後片付けを処理する。 */
  void cleanup(Instant before) throws Exception;
}
