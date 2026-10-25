package dev.mikoto2000.rei.secretary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.SecretaryController;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("integration")
class SecretaryHttpTest {
  @TempDir Path root;

  @Test
  void approvalRequiresCurrentRevisionAndMalformedRequestsDoNotMutate() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var ledger =
        new SecretaryLedger(new SqliteSecretaryDatabase(root.toString()), Clock.systemUTC());
    var task = ledger.extract("p", "event", "資料を作成したい").getFirst();
    var mvc =
        MockMvcBuilders.standaloneSetup(new SecretaryController(ledger))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    String path = "/api/v1/secretary/tasks/" + task.id();
    mvc.perform(
            post(path + "/transition")
                .contentType("application/json")
                .content("{\"projectId\":\"p\",\"revision\":0,\"status\":\"OPEN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
    mvc.perform(
            post(path + "/transition")
                .contentType("application/json")
                .content("{\"projectId\":\"p\",\"revision\":0,\"status\":\"DONE\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            post(path + "/transition")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"p\",\"revision\":1,\"status\":\"DONE\",\"root\":\"other\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path).param("projectId", "p"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
  }
}
