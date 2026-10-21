package dev.mikoto2000.rei.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.checkpoint.CheckpointException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 再開用の記録許容件数エラーの結果と状態の契約を検証するテスト。 */
class CheckpointCapacityErrorTest {
  /** 検証条件を再現するための許容件数受付窓口。 */
  @RestController
  static class CapacityController {
    /** {@code full} の条件を再現するため、検証用の操作や状態を扱う。 */
    @GetMapping("/capacity")
    void full() {
      throw CheckpointException.storageCapacity(8, 10, 1000, 25, 1024);
    }

    /** {@code privateMessage} の条件を再現するため、検証用の操作や状態を扱う。 */
    @GetMapping("/private-capacity")
    void privateMessage() {
      throw new CheckpointException(CheckpointException.Code.CAPACITY, "PRIVATE");
    }
  }

  /** Web APIによる操作で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void capacityResponseIncludesSafeUsageAndRecoveryInstructions() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new CapacityController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(get("/capacity"))
        .andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.code").value("CAPACITY"))
        .andExpect(jsonPath("$.usage.bytes").value(1000))
        .andExpect(jsonPath("$.usage.incomingBytes").value(25))
        .andExpect(jsonPath("$.usage.maxBytes").value(1024))
        .andExpect(jsonPath("$.usage.revisions").value(8))
        .andExpect(jsonPath("$.usage.settingNames[0]").value("rei.checkpoint.max-bytes"))
        .andExpect(jsonPath("$.message", containsString("/checkpoint cleanup --apply")));
    mvc.perform(get("/private-capacity"))
        .andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.message").value("Checkpoint: CAPACITY"))
        .andExpect(jsonPath("$.usage").doesNotExist());
  }
}
