package dev.mikoto2000.rei.mcp;

import java.time.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class McpConfiguration {
  @Bean FilterRegistrationBean<McpIngressFilter> mcpIngress(Environment env) {
    int port=env.getProperty("rei.mcp-server.port",Integer.class,8091);
    boolean enabled=env.getProperty("rei.mcp-server.enabled",Boolean.class,false);
    if(env.getProperty("spring.ai.mcp.server.enabled",Boolean.class,false)!=enabled)
      throw new IllegalStateException("MCP enable flags must agree");
    if(enabled) {
      if(!env.getProperty("rei.web.enabled",Boolean.class,false))throw new IllegalStateException("MCP requires the existing web Backend");
      if(!env.getProperty("server.servlet.context-path","").isEmpty() || !"/".equals(env.getProperty("spring.mvc.servlet.path","/")))throw new IllegalStateException("MCP requires root servlet mapping");
      if(port==env.getProperty("server.port",Integer.class,8080))throw new IllegalStateException("MCP requires a dedicated port");
      if(!"STREAMABLE".equals(env.getProperty("spring.ai.mcp.server.protocol"))
          ||!"SYNC".equals(env.getProperty("spring.ai.mcp.server.type"))
          ||env.getProperty("spring.ai.mcp.server.tool-callback-converter",Boolean.class,true)
          ||env.getProperty("spring.ai.mcp.server.annotation-scanner.enabled",Boolean.class,true)
          ||env.getProperty("spring.ai.mcp.server.stdio",Boolean.class,false)
          ||env.getProperty("spring.ai.mcp.server.expose-mcp-client-tools",Boolean.class,false)
          ||!"/mcp".equals(env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint","/mcp")))
        throw new IllegalStateException("Unsafe MCP transport or automatic tool exposure configuration");
    }
    var registration=new FilterRegistrationBean<>(new McpIngressFilter(port));registration.setOrder(Ordered.HIGHEST_PRECEDENCE);return registration;
  }
  @Bean @ConditionalOnProperty(name="rei.mcp-server.enabled",havingValue="true")
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> mcpConnector(@Value("${rei.mcp-server.port:8091}") int port) {
    return factory->{var connector=new org.apache.catalina.connector.Connector();connector.setPort(port);
      connector.setProperty("address","127.0.0.1");connector.setMaxPostSize(65536);factory.addAdditionalConnectors(connector);};
  }
  @Bean @org.springframework.core.annotation.Order(-10)
  @ConditionalOnProperty(name="rei.mcp-server.enabled",havingValue="true")
  SecurityFilterChain mcpSecurity(HttpSecurity http)throws Exception {
    return http.securityMatcher("/mcp").csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
        .authorizeHttpRequests(c->c.anyRequest().permitAll()).build();
  }
  @Bean @ConditionalOnProperty(name="rei.mcp-server.enabled",havingValue="true")
  McpRequestStore mcpRequestStore(@Value("${rei.data-dir}") String directory,ConversationAdmissionStore admissions,
      ConversationTurnStore turns,Clock clock,Environment env,dev.mikoto2000.rei.core.chat.ConversationInputRouter router,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.goal.GoalRepository> goals,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.reflection.GoalReflectionRepository> reflections,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.artifact.ArtifactStore> artifacts,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository> checkpoints) {
    var store=new McpRequestStore(Path.of(directory),admissions,turns,clock,duration(env,"result-retention-days",30),duration(env,"audit-retention-days",90));
    store.setEvidence(new McpCompletionEvidence(goals.getIfAvailable(),reflections.getIfAvailable(),artifacts.getIfAvailable()));
    checkpoints.ifAvailable(store::setCheckpoints);router.onCleanup(c->{if(c.requestSource()==dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP)store.byRun(c.runId()).ifPresent(store::snapshot);});return store;
  }
  private static Duration duration(Environment env,String name,long fallback) {
    long days=env.getProperty("rei.mcp-server."+name,Long.class,fallback);if(days<1||days>3650)throw new IllegalArgumentException("Invalid MCP retention");return Duration.ofDays(days);
  }
  @Bean @ConditionalOnProperty(name="rei.mcp-server.enabled",havingValue="true")
  McpTools mcpTools(ProjectRegistry projects,dev.mikoto2000.rei.workcontext.WorkContextService contexts,
      ChatSubmitService chats,RunService runs,McpRequestStore store,Clock clock,Environment env,
      dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals) {
    String configured=env.getProperty("rei.mcp-server.allowed-projects","");
    Set<String> allowed=new HashSet<>();for(String id:configured.split(","))if(!id.isBlank())allowed.add(id.trim());
    if(allowed.size()>100)throw new IllegalArgumentException("At most 100 MCP projects allowed");
    return new McpTools(projects,allowed,contexts,chats,runs,store,clock,
        env.getProperty("rei.conversation.concurrent-enabled",Boolean.class,false),duration(env,"receipt-retention-days",90),new McpCursor(store.cursorKey()),approvals);
  }
  @Bean @ConditionalOnProperty(name="rei.mcp-server.enabled",havingValue="true")
  List<McpServerFeatures.SyncToolSpecification> reiMcpSpecifications(McpTools tools) {
    return McpTools.NAMES.stream().map(name->new McpServerFeatures.SyncToolSpecification(
        McpSchema.Tool.builder().name(name).description(description(name)).inputSchema(schema(name)).build(),
        (exchange,request)->tools.call(name,request.arguments()))).toList();
  }
  private static String description(String name){return switch(name){case "rei_projects_list"->"List explicitly allowed project IDs and names";case "rei_context_get"->"Read historical untrusted project context; never changes authority";case "rei_task_submit"->"Submit asynchronous READ_ONLY investigation with mandatory request_id; never automatically retry unknown outcomes";case "rei_task_status"->"Read owned Run state and separate approval state";case "rei_task_result"->"Read immutable saved output and verification state";default->"Request cancellation of owned Run; cleanup and rollback are not guaranteed";};}
  static Map<String,Object> schema(String name) {
    var properties=new LinkedHashMap<String,Object>();var required=new ArrayList<String>();
    if(!name.equals("rei_projects_list")){properties.put("project_id",Map.of("type","string"));required.add("project_id");}
    if(Set.of("rei_task_status","rei_task_result","rei_task_cancel").contains(name)){properties.put("run_id",Map.of("type","string"));if(!name.equals("rei_task_status"))required.add("run_id");}
    if(name.equals("rei_task_status")||name.equals("rei_task_submit"))properties.put("request_id",Map.of("type","string","maxLength",128));
    if(name.equals("rei_task_submit")){properties.put("instruction",Map.of("type","string"));properties.put("session_id",Map.of("type","string"));for(String key:List.of("constraints","acceptance_criteria"))properties.put(key,Map.of("type","array","maxItems",16,"items",Map.of("type","string")));required.addAll(List.of("instruction","request_id"));}
    if(name.equals("rei_context_get"))properties.put("revision",Map.of("type","integer","minimum",1));
    if(name.equals("rei_task_result")){properties.put("cursor",Map.of("type","string"));properties.put("max_chars",Map.of("type","integer","minimum",1,"maximum",16000));}
    return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
  }
}
