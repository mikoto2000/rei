package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 状態表示コマンド要求の結果と状態の契約を検証するテスト。 */
class DotCommandRequestTest {
  /** 利用者の状態表示で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void requestOptionsAreSharedAndBounded() {
    var r =
        DotCommandRequest.parse(
            List.of(
                "request",
                "Compare options",
                "--project-id",
                "project",
                "--category",
                "COMPARISON",
                "--context",
                "Background",
                "--expected-output",
                "Report",
                "--constraint",
                "read only",
                "--acceptance-criterion",
                "sources",
                "--timeout",
                "3600"));
    assertEquals("request", r.action());
    assertEquals("project", r.projectId());
    assertEquals("Compare options", r.payload().get("instruction"));
    assertEquals(List.of("read only"), r.payload().get("constraints"));
    assertEquals(3600, r.payload().get("timeout"));
  }

  /** 要求が生成元のマップやリストから独立していることを確認する。 */
  @Test
  void snapshotsNestedPayloadCollections() {
    var constraints = new ArrayList<>(List.of("read only"));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("constraints", constraints);
    var request = new DotCommandRequest("request", null, "project", payload);

    constraints.add("write");
    payload.clear();
    assertEquals(List.of("read only"), request.payload().get("constraints"));
    var stored = (List<?>) request.payload().get("constraints");
    assertThrows(UnsupportedOperationException.class, stored::clear);
  }

  /** 利用者の状態表示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void fixedCommandsAndUnknownOptions() {
    assertEquals("abc", DotCommandRequest.parse(List.of("cancel", "abc")).requestId());
    assertEquals("requests", DotCommandRequest.parse(List.of("requests")).action());
    assertThrows(
        IllegalArgumentException.class,
        () -> DotCommandRequest.parse(List.of("request", "task", "--shell", "rm")));
    assertThrows(IllegalArgumentException.class, () -> DotCommandRequest.parse(List.of("request")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DotCommandRequest.parse(List.of("status", "id", "extra")));
  }
}
