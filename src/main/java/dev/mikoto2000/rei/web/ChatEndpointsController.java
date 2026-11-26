package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.llm.ChatConnectionService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The same process-wide connection selection used by Shell and terminal clients. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/llm/chat")
public final class ChatEndpointsController {
  private final java.util.function.Supplier<List<ChatConnectionService.Endpoint>> endpoints;
  private final java.util.function.Consumer<String> selectEndpoint;

  public ChatEndpointsController(ChatConnectionService connections) {
    java.util.Objects.requireNonNull(connections);
    endpoints = connections::endpoints;
    selectEndpoint = connections::select;
  }

  public record Selection(String endpoint) implements StrictApiRequest {
    public Selection {
      if (endpoint == null || !endpoint.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
        throw new IllegalArgumentException("Valid registered endpoint name required");
    }
  }

  @GetMapping
  public List<ChatConnectionService.Endpoint> list() {
    return endpoints.get();
  }

  @GetMapping("/current")
  public ChatConnectionService.Endpoint current() {
    return list().stream()
        .filter(ChatConnectionService.Endpoint::active)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Current chat endpoint is unavailable"));
  }

  @PutMapping("/current")
  public ChatConnectionService.Endpoint select(@RequestBody Selection selection) {
    if (selection == null) throw new IllegalArgumentException("Endpoint selection required");
    selectEndpoint.accept(selection.endpoint());
    return current();
  }
}
