package dev.mikoto2000.rei.web;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.artifact.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@Tag("integration")
class ArtifactControllerTest {
  @TempDir Path root;
  @Test void contentIsOwnerScopedAttachmentWithHashAndDeletedContentIsGone() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    var item=store.publish(new AgentRunContext("run","session",root,project.id()),"document","text/markdown","結果.md","# 結果\n".getBytes(StandardCharsets.UTF_8));
    var mvc=MockMvcBuilders.standaloneSetup(new ArtifactController(store)).setControllerAdvice(new ApiExceptionHandler()).build();
    String path="/api/v1/artifacts/"+item.artifactId();
    mvc.perform(get(path+"/content").param("projectId",project.id()).param("sessionId","other")).andExpect(status().isNotFound());
    mvc.perform(get(path+"/content").param("projectId",project.id()).param("sessionId","session"))
        .andExpect(status().isOk()).andExpect(content().bytes("# 結果\n".getBytes(StandardCharsets.UTF_8)))
        .andExpect(header().string("Content-Type","text/markdown")).andExpect(header().string("X-Content-Type-Options","nosniff"))
        .andExpect(header().string("ETag","\""+item.sha256()+"\"")).andExpect(header().exists("Content-Disposition"));
    mvc.perform(get(path+"/content").param("projectId",project.id()).param("sessionId","session").header("Range","bytes=0-1")).andExpect(status().isRequestedRangeNotSatisfiable());
    mvc.perform(get("/api/v1/artifacts").param("projectId",project.id()).param("limit","1")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].artifactId").value(item.artifactId()));
    mvc.perform(delete(path).contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"sessionId\":\"session\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
    mvc.perform(get(path+"/content").param("projectId",project.id()).param("sessionId","session")).andExpect(status().isGone());
  }
}
