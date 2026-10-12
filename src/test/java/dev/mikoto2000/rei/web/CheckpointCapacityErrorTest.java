package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.checkpoint.CheckpointException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class CheckpointCapacityErrorTest {
  @RestController static class CapacityController {
    @GetMapping("/capacity") void full(){throw CheckpointException.storageCapacity(8,10,1000,25,1024);}
    @GetMapping("/private-capacity") void privateMessage(){throw new CheckpointException(CheckpointException.Code.CAPACITY,"PRIVATE");}
  }
  @Test void capacityResponseIncludesSafeUsageAndRecoveryInstructions() throws Exception {
    var mvc=MockMvcBuilders.standaloneSetup(new CapacityController()).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/capacity")).andExpect(status().isInsufficientStorage()).andExpect(jsonPath("$.code").value("CAPACITY"))
        .andExpect(jsonPath("$.usage.bytes").value(1000)).andExpect(jsonPath("$.usage.incomingBytes").value(25))
        .andExpect(jsonPath("$.usage.maxBytes").value(1024)).andExpect(jsonPath("$.usage.revisions").value(8))
        .andExpect(jsonPath("$.usage.settingNames[0]").value("rei.checkpoint.max-bytes"))
        .andExpect(jsonPath("$.message",containsString("/checkpoint cleanup --apply")));
    mvc.perform(get("/private-capacity")).andExpect(status().isInsufficientStorage())
        .andExpect(jsonPath("$.message").value("Checkpoint: CAPACITY")).andExpect(jsonPath("$.usage").doesNotExist());
  }
}
