package dev.mikoto2000.rei.llm;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Owned four-file fixture; actual discovery, compression and native replay share the app Core. */
final class ResponsesOperationalResearch {
  static void run(ResponsesOperationalLiveAcceptance test) throws Exception {
    run(test, "operational-research");
  }

  static void run(ResponsesOperationalLiveAcceptance test, String trialDirectory) throws Exception {
    var runtime = test.runtime;
    var directory = runtime.root.resolve(trialDirectory);
    Files.createDirectory(directory);
    var contents =
        Map.of(
            "a.java",
                "// Contract: valid indices are 0 through length-1.\nstatic boolean valid(int index,int length){return index>=0 && index<=length;}\n",
            "b.java",
                "// Contract: input may be null; null must return an empty string.\nstatic String normalize(String input){return input.trim();}\n",
            "c.java",
                "// Contract: valid indices are 0 through length-1.\nstatic boolean valid(int index,int length){return index>=0 && index<length;}\n",
            "d.java",
                "// Contract: input may be null; null must return an empty string.\nstatic String normalize(String input){return input==null?\"\":input.trim();}\n");
    for (var file : contents.entrySet())
      Files.writeString(directory.resolve(file.getKey()), file.getValue());
    String fact = "review-fact-" + java.util.UUID.randomUUID();
    String proof = "review-proof-" + java.util.UUID.randomUUID();
    var reads = new AtomicInteger();
    var specialist = new AtomicInteger();
    var callbacks = new java.util.ArrayList<ToolCallback>();
    com.fasterxml.jackson.databind.JsonNode catalog;
    try (var input =
        ResponsesOperationalResearch.class.getResourceAsStream("/tool-discovery-catalog.json")) {
      catalog = ResponsesAcceptanceRuntime.JSON.readTree(input);
    }
    for (var entry : catalog) {
      String name = entry.path("name").asText();
      callbacks.add(
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name(name)
                  .description(
                      name.equals("now")
                          ? "Owned two-page source fixture READ, not time. Page1=a.java,b.java; page2=c.java,d.java. Read each once in order."
                          : name.equals("getGoalCompletionDefinition")
                              ? "Owned review verification token READ. Call once after both source pages."
                              : "Disabled owned fixture; do not call.")
                  .inputSchema(
                      name.equals("now")
                          ? "{\"type\":\"object\",\"properties\":{\"page\":{\"type\":\"integer\",\"enum\":[1,2]}},\"required\":[\"page\"],\"additionalProperties\":false}"
                          : "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                  .build();
            }

            public synchronized String call(String input) {
              try {
                if (name.equals("now")) {
                  int page = ResponsesAcceptanceRuntime.JSON.readTree(input).path("page").asInt();
                  if (page != reads.get() + 1 || page > 2)
                    throw new IllegalStateException("Owned review page repeated/out of order");
                  reads.incrementAndGet();
                  var files = new java.util.ArrayList<Map<String, String>>();
                  for (String file :
                      page == 1 ? List.of("a.java", "b.java") : List.of("c.java", "d.java"))
                    files.add(
                        Map.of("path", file, "content", Files.readString(directory.resolve(file))));
                  var result = new java.util.LinkedHashMap<String, Object>();
                  result.put("files", files);
                  if (page == 1) result.put("retainFirstFact", fact);
                  result.put("historicalPadding", "owned synthetic review padding ".repeat(384));
                  return ResponsesAcceptanceRuntime.JSON.writeValueAsString(result);
                }
                if (name.equals("getGoalCompletionDefinition")) {
                  if (reads.get() != 2 || specialist.incrementAndGet() != 1)
                    throw new IllegalStateException("Owned specialist ordering/repetition failed");
                  return ResponsesAcceptanceRuntime.JSON.writeValueAsString(
                      Map.of("verification", proof));
                }
                throw new dev.mikoto2000.rei.core.policy.ToolPermissionException(
                    name, dev.mikoto2000.rei.core.policy.PermissionDecision.DENY);
              } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            }
          });
    }
    test.tools.callbacks = List.copyOf(callbacks);
    var settings = new dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties();
    settings.setEnabled(true);
    settings.setMaxActiveTools(36);
    var store =
        new dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore(
            runtime.source("operational-discovery.db"),
            test.app.getBeanProvider(dev.mikoto2000.rei.review.ReviewCoverageLedger.class),
            test.app.getBeanProvider(dev.mikoto2000.rei.durable.DurableTaskStore.class));
    runtime.beans.addBean("operationalDiscovery", store);
    test.tools.discovery =
        new dev.mikoto2000.rei.llm.discovery.ToolDiscovery(
            settings,
            runtime.beans.getBeanProvider(
                dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore.class));
    runtime.properties.updateOutputLimit(limits -> limits.setMaxLlmCallsPerRun(8));
    var owner = test.owner("research");
    var compression = new AtomicInteger();
    var subscription =
        test.app
            .getBean(dev.mikoto2000.rei.event.AgentEventBus.class)
            .subscribe(event -> {
              if (owner.runId().equals(event.runId())
                  && event.type()
                      == dev.mikoto2000.rei.event.AgentEventType.CONTEXT_COMPRESSION_COMPLETED)
                compression.incrementAndGet();
            });
    try {
      var result =
          test.execute(
              owner,
              "Review all four owned fixture files against their explicit contracts. Read now page1 then page2 exactly once each. "
                  + "Then use searchTools query getGoalCompletionDefinition and call the discovered specialist exactly once for its verification token. "
                  + "Ignore historicalPadding; retain first-page retainFirstFact through summaries. Report exactly the two real defects and no findings for correct files. "
                  + "Return only JSON {findings:[{path:<filename>,line:<1-based defective line>,code:<BOUNDARY or NULL>}],firstFact:<retainFirstFact>,verification:<specialist token>}. "
                  + "BOUNDARY means an index boundary violation; NULL means the nullable-input contract is violated. No other tools.");
      var answer = ResponsesAcceptanceRuntime.JSON.readTree(result.text().trim());
      var expected = java.util.Set.of("a.java:2:BOUNDARY", "b.java:2:NULL");
      var actual = new java.util.HashSet<String>();
      answer
          .path("findings")
          .forEach(f ->
              actual.add(
                  f.path("path").asText()
                      + ":"
                      + f.path("line").asInt()
                      + ":"
                      + f.path("code").asText()));
      boolean success =
          result.success()
              && reads.get() == 2
              && specialist.get() == 1
              && compression.get() >= 1
              && answer.path("findings").size() == 2
              && actual.equals(expected)
              && fact.equals(answer.path("firstFact").asText())
              && proof.equals(answer.path("verification").asText())
              && store.load(owner).isPresent();
      runtime.report(
          "APP_RESEARCH",
          Map.of(
              "success",
              success,
              "files_covered",
              2 * reads.get(),
              "specialist_calls",
              specialist.get(),
              "compressions",
              compression.get(),
              "findings_correct",
              actual.equals(expected),
              "business_review_page_ledger",
              "NOT_EXERCISED_OWNED_FIXTURE_READ",
              "budget",
              runtime.budget.snapshot()));
      if (!success)
        throw new IllegalStateException("Owned review/discovery/compression criterion failed");
    } finally {
      subscription.unsubscribe();
    }
  }
}
