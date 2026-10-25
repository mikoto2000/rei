package dev.mikoto2000.rei.cli;

import java.util.*;

/**
 * 軽量CLIでの対話に必要な外部コマンド一覧を扱う。
 *
 * <p>Fixed public API adapters. No endpoint, shell text, or reflection target is supplied by a
 * command.
 */
public final class RemoteCommands {
  /** コマンドJSON変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper COMMAND_JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(32768)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /**
   * 軽量CLIでの対話で受け渡す要求を表す。
   *
   * @param method メソッド
   * @param path パス
   * @param body 本文
   */
  public record Request(String method, String path, Object body) {}

  /** 名前一覧。 */
  public static final Set<String> NAMES =
      Set.of(
          "/secretary",
          "/dot",
          "/llm",
          "/bsky",
          "/activity",
          "/storage",
          "/embed",
          "/schedule",
          "/task",
          "/sleep",
          "/reflection",
          "/subagent",
          "/config",
          "/paper",
          "/material-review",
          "/agent",
          "/document",
          "/repair",
          "/timing",
          "/doctor",
          "/mode",
          "/history",
          "/feed",
          "/skill",
          "/profile",
          "/briefing",
          "/reminder",
          "/interest",
          "/memory",
          "/search",
          "/timer",
          "/tasks",
          "/goal",
          "/attention",
          "/checkpoint",
          "/resume",
          "/approval",
          "/dependency",
          "/work",
          "/summarize",
          "/image",
          "/model",
          "/models");

  /** 軽量CLIでの対話で用いる受付経路を返す。 */
  public static Request route(List<String> words, String project, String session) {
    return route(words, project, session, java.nio.file.Path.of(System.getProperty("user.dir")));
  }

  /** 軽量CLIでの対話で用いる受付経路を返す。 */
  public static Request route(
      List<String> words, String project, String session, java.nio.file.Path localBase) {
    var request = routeDirect(words, project, session, localBase);
    if (!Set.of("/schedule", "/task").contains(words.getFirst())
        || request.path().endsWith("/authorize")
        || request.path().endsWith("/refresh-token")) return request;
    if (project == null) throw new IllegalArgumentException("Select a Project first");
    var body = new LinkedHashMap<String, Object>();
    body.put("projectId", project);
    body.put("sessionId", session);
    if (request.body() instanceof Map<?, ?> values)
      values.forEach((key, value) -> body.put((String) key, value));
    int query = request.path().indexOf('?');
    if (query >= 0)
      for (String part : request.path().substring(query + 1).split("&")) {
        var item = part.split("=", 2);
        String value = java.net.URLDecoder.decode(item[1], java.nio.charset.StandardCharsets.UTF_8);
        body.put(item[0], item[0].equals("priority") ? integer(value) : value);
      }
    String action = words.size() == 1 ? "list" : words.get(1), path;
    if (words.getFirst().equals("/schedule")) path = "/calendar/" + action;
    else
      path =
          Set.of("done", "delete").contains(action)
              ? "/tasks/"
                  + longInteger(words.get(2))
                  + "/"
                  + (action.equals("done") ? "complete" : "delete")
              : "/tasks/" + action;
    return post("/api/v1/google-operations" + path, body);
  }

  /** 軽量CLIでの対話で用いる受付経路Directを返す。 */
  private static Request routeDirect(
      List<String> words, String project, String session, java.nio.file.Path localBase) {
    String name = words.getFirst(), action = words.size() > 1 ? words.get(1) : "list";
    if (name.equals("/secretary")) return secretary(words, project);
    if (name.equals("/dot")) {
      var command = dev.mikoto2000.rei.dot.DotCommandRequest.parse(words.subList(1, words.size()));
      String owner = command.projectId() == null ? project : command.projectId();
      if (owner == null) throw new IllegalArgumentException("Select a Project first");
      String base = "/api/v1/projects/" + id(owner) + "/dot-requests";
      return switch (command.action()) {
        case "request" -> {
          var body = new LinkedHashMap<String, Object>(command.payload());
          body.putIfAbsent("request_id", java.util.UUID.randomUUID().toString());
          body.put("session_id", session);
          yield post(base, body);
        }
        case "requests" -> get(base);
        case "status" -> get(base + "/" + id(command.requestId()));
        case "result" -> get(base + "/" + id(command.requestId()) + "/result");
        case "cancel" -> post(base + "/" + id(command.requestId()) + "/cancel", Map.of());
        case "verify", "resume" ->
            post(base + "/" + id(command.requestId()) + "/" + command.action(), command.payload());
        default -> throw invalid();
      };
    }
    if (!NAMES.contains(name))
      throw new IllegalArgumentException(
          "Unsupported CLI command: "
              + name
              + ". Use legacy-shell; this input was not sent to the model.");
    if (name.equals("/checkpoint") && Set.of("status", "cleanup").contains(action)) {
      String base = "/api/v1/checkpoints/history";
      if (action.equals("status")) {
        exact(words, 2);
        return get(base + "/status");
      }
      if (words.size() == 2) return get(base + "/cleanup");
      exact(words, 3);
      if (!words.get(2).equals("--apply")) throw invalid();
      return post(base + "/cleanup", Map.of("apply", true));
    }
    if (name.equals("/feed")) {
      String base = "/api/v1/feed/commands";
      if (action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(base);
      }
      if (action.equals("import-opml")) {
        exact(words, 3);
        String input = words.get(2);
        if (input.equals("~")) input = System.getProperty("user.home");
        else if (input.startsWith("~/") || input.startsWith("~\\"))
          input =
              java.nio.file.Path.of(System.getProperty("user.home"))
                  .resolve(input.substring(2))
                  .toString();
        return post(
            base + "/imports",
            Map.of("path", localBase.resolve(input).toAbsolutePath().normalize().toString()));
      }
      if (action.equals("add")) {
        var parsed = parse(words, 2, Set.of("--name"), Set.of());
        if (parsed.arguments().size() != 1) throw invalid();
        var body = new LinkedHashMap<String, Object>();
        body.put("url", parsed.arguments().getFirst());
        body.put("displayName", parsed.options().get("--name"));
        return post(base, body);
      }
      if (action.equals("edit")) {
        var parsed = parse(words, 2, Set.of("--name"), Set.of("--enabled", "--disabled"));
        if (parsed.arguments().size() != 1) throw invalid();
        boolean enabled = Boolean.parseBoolean(parsed.options().get("--enabled")),
            disabled = Boolean.parseBoolean(parsed.options().get("--disabled"));
        var body = new LinkedHashMap<String, Object>();
        body.put("displayName", parsed.options().get("--name"));
        body.put("enabled", enabled == disabled ? null : enabled);
        return new Request("PATCH", base + "/" + longInteger(parsed.arguments().getFirst()), body);
      }
      if (action.equals("delete")) {
        exact(words, 3);
        return new Request("DELETE", base + "/" + longInteger(words.get(2)), null);
      }
      if (Set.of("update", "summary").contains(action)) {
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (project == null || words.size() > 3 || action.equals("summary") && words.size() != 2)
          throw invalid();
        var body = new LinkedHashMap<String, Object>();
        body.put("projectId", project);
        body.put("sessionId", session);
        body.put("feedId", words.size() == 3 ? longInteger(words.get(2)) : null);
        return post(base + "/" + action, body);
      }
      if (action.equals("item")) {
        if (words.size() < 3) throw invalid();
        String operation = words.get(2);
        if (operation.equals("list")) {
          var parsed = parse(words, 3, Set.of("--from", "--to", "--limit"), Set.of());
          if (!parsed.arguments().isEmpty()) throw invalid();
          String query = "?limit=" + integer(parsed.options().getOrDefault("--limit", "10"));
          for (String option : List.of("--from", "--to"))
            if (parsed.options().containsKey(option)) {
              String date = parsed.options().get(option);
              java.time.OffsetDateTime.parse(date);
              query += "&" + option.substring(2) + "=" + value(date);
            }
          return get(base + "/items" + query);
        }
        if (operation.equals("summarize")) {
          exact(words, 4);
          if (project == null) throw invalid();
          var body = new LinkedHashMap<String, Object>();
          body.put("projectId", project);
          body.put("sessionId", session);
          body.put("itemId", longInteger(words.get(3)));
          return post(base + "/items/summarize", body);
        }
        throw invalid();
      }
    }
    if (name.equals("/llm")) {
      if (words.size() < 3 || !action.equals("capture")) throw invalid();
      String operation = words.get(2), base = "/api/v1/llm/capture";
      if (Set.of("next", "status", "off").contains(operation)) {
        exact(words, 3);
        if (project == null || session == null)
          throw new IllegalArgumentException("Select a Session first");
        return operation.equals("status")
            ? get(base + "/status?projectId=" + id(project) + "&sessionId=" + id(session))
            : post(
                base + "/reservation" + (operation.equals("off") ? "/off" : ""),
                Map.of("projectId", project, "sessionId", session));
      }
      if (operation.equals("list")) {
        if (words.size() == 3) return get(base + "/sessions");
        exact(words, 4);
        return get(base + "/sessions/" + id(words.get(3)) + "/attempts");
      }
      if (operation.equals("show")) {
        exact(words, 4);
        return get(base + "/attempts/" + id(words.get(3)) + "/show");
      }
      if (operation.equals("raw")) {
        exact(words, 4);
        return post(base + "/attempts/" + id(words.get(3)) + "/raw", Map.of("approved", false));
      }
      if (operation.equals("export")) {
        exact(words, 5);
        return post(
            base + "/attempts/" + id(words.get(3)) + "/export",
            Map.of(
                "approved",
                false,
                "path",
                localBase.resolve(words.get(4)).toAbsolutePath().toString()));
      }
      if (operation.equals("delete")) {
        exact(words, 4);
        return new Request("DELETE", base + "/sessions/" + id(words.get(3)), null);
      }
      if (operation.equals("clear")) {
        exact(words, 3);
        return new Request("DELETE", base + "/sessions", null);
      }
      throw invalid();
    }
    if (name.equals("/bsky")) {
      String base = "/api/v1/bluesky";
      if (action.equals("history-users")) {
        exact(words, 2);
        return get(base + "/history-users");
      }
      if (action.equals("history")) {
        var parsed = parse(words, 2, Set.of("--limit"), Set.of());
        if (parsed.arguments().size() != 1) throw invalid();
        return get(
            base
                + "/history/"
                + id(parsed.arguments().getFirst())
                + "?limit="
                + integer(parsed.options().getOrDefault("--limit", "20")));
      }
      if (action.equals("reply")) {
        if (words.size() < 3 || project == null) throw invalid();
        var parsed = parse(words, 2, Set.of(), Set.of());
        if (parsed.arguments().isEmpty()) throw invalid();
        var body = new LinkedHashMap<String, Object>();
        body.put("projectId", project);
        body.put("sessionId", session);
        body.put("target", parsed.arguments().getFirst());
        body.put(
            "text",
            parsed.arguments().size() == 1
                ? null
                : String.join(" ", parsed.arguments().subList(1, parsed.arguments().size())));
        return post(base + "/replies", body);
      }
      throw invalid();
    }
    if (name.equals("/interest") && Set.of("list", "discover").contains(action)) {
      if (action.equals("list")) {
        var parsed = parse(words, words.size() == 1 ? 1 : 2, Set.of("--hours"), Set.of());
        if (!parsed.arguments().isEmpty()) throw invalid();
        return get(
            "/api/v1/interest/commands/list?hours="
                + integer(parsed.options().getOrDefault("--hours", "24")));
      }
      exact(words, 2);
      if (project == null) throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      return post("/api/v1/interest/commands/discover", body);
    }
    if (name.equals("/activity")) {
      var parsed =
          dev.mikoto2000.rei.application.cli.ActivityRequest.parse(words.subList(1, words.size()));
      String base = "/api/v1/activity/commands";
      if (parsed.operation().equals("timeline"))
        return Set.of("pause", "resume").contains(parsed.action())
            ? post(base + "/" + parsed.action(), Map.of())
            : get(base + "/timeline?date=" + value(parsed.date()) + "&verbose=" + parsed.verbose());
      if (Set.of("summary", "weekly", "monthly").contains(parsed.operation()))
        return get(base + "/" + parsed.operation() + "?date=" + value(parsed.date()));
      if (parsed.operation().equals("context")) {
        if (project == null) throw new IllegalArgumentException("Select a Project first");
        return get(base + "/context?projectId=" + id(project) + "&date=" + value(parsed.date()));
      }
      base += "/" + parsed.operation();
      String operation = parsed.action();
      if (operation.equals("suggest-rules")
          && Set.of("behavior", "classification").contains(parsed.operation())) {
        if (project == null) throw new IllegalArgumentException("Select a Project first");
        var body = new LinkedHashMap<String, Object>();
        body.put("projectId", project);
        body.put("sessionId", session);
        return post(base + "/suggest-rules", body);
      }
      if (operation.equals("status")) return get(base + "/status");
      if (Set.of("on", "off").contains(operation)
          && Set.of("coaching", "behavior").contains(parsed.operation()))
        return post(base + "/enabled", Map.of("enabled", operation.equals("on")));
      if (parsed.operation().equals("coaching")) {
        if (operation.equals("configure")) {
          var settings = parsed.settings();
          return post(
              base + "/settings",
              Map.of(
                  "settings",
                  Map.of(
                      "enabled",
                      false,
                      "categories",
                      settings.categories(),
                      "targetShare",
                      settings.targetShare(),
                      "minimumObservedMinutes",
                      settings.minObservedMinutes(),
                      "minimumCoverage",
                      settings.minCoverage(),
                      "maximumUnknownShare",
                      settings.maxUnknownShare(),
                      "cooldownDays",
                      settings.cooldownDays())));
        }
        if (Set.of("weekly", "monthly").contains(operation)) {
          var body = new LinkedHashMap<String, Object>();
          body.put("date", parsed.date());
          return post(base + "/" + operation, body);
        }
      }
      if (parsed.operation().equals("behavior")) {
        if (operation.equals("evaluate")) return get(base + "/evaluate");
        if (operation.equals("uncertain")) return get(base + "/uncertain?top=" + parsed.top());
      }
      if (parsed.operation().equals("classification")) {
        if (operation.equals("unknowns")) return get(base + "/unknowns?top=" + parsed.top());
        if (operation.equals("rules")) return get(base + "/rules");
        if (operation.equals("reload")) return post(base + "/reload", Map.of());
      }
      throw invalid();
    }
    if (name.equals("/storage")) {
      String base = "/api/v1/storage";
      if (action.equals("status")) {
        var parsed = parse(words, 2, Set.of("--project"), Set.of());
        if (!parsed.arguments().isEmpty()) throw invalid();
        boolean current = currentStorageScope(parsed.options(), project);
        return get(
            base
                + "/status"
                + (project == null
                    ? ""
                    : ("?projectId=" + id(project) + (current ? "&current=true" : ""))));
      }
      if (words.size() < 3) throw invalid();
      String operation = words.get(2);
      if (action.equals("maintenance")) {
        if (operation.equals("status")) {
          exact(words, 3);
          return get(base + "/maintenance/status");
        }
        if (operation.equals("checkpoint")) {
          exact(words, 3);
          return post(base + "/maintenance/checkpoint", Map.of());
        }
        if (operation.equals("incremental-vacuum")) {
          var parsed = parse(words, 3, Set.of("--pages"), Set.of());
          if (!parsed.arguments().isEmpty() || !parsed.options().containsKey("--pages"))
            throw invalid();
          return post(
              base + "/maintenance/incremental-vacuum",
              Map.of("pages", integer(parsed.options().get("--pages"))));
        }
        if (operation.equals("vacuum")) {
          var parsed = parse(words, 3, Set.of("--window-seconds"), Set.of());
          if (!parsed.arguments().isEmpty() || !parsed.options().containsKey("--window-seconds"))
            throw invalid();
          return post(
              base + "/maintenance/vacuum",
              Map.of("windowSeconds", longInteger(parsed.options().get("--window-seconds"))));
        }
        throw invalid();
      }
      if (!action.equals("retention")) throw invalid();
      base += "/retention";
      if (operation.equals("history")) {
        exact(words, 3);
        return get(base + "/history");
      }
      if (Set.of("approve", "apply", "restore", "approve-purge", "purge").contains(operation)) {
        exact(words, 4);
        return post(
            base
                + (Set.of("approve", "apply").contains(operation) ? "/plans/" : "/executions/")
                + id(words.get(3))
                + "/"
                + operation,
            Map.of());
      }
      if (operation.equals("plan")) {
        var parsed =
            parse(
                words,
                3,
                Set.of("--category", "--project", "--max-objects", "--max-bytes"),
                Set.of());
        if (!parsed.arguments().isEmpty()) throw invalid();
        var body = storagePlanOptions(parsed.options(), project);
        return post(base + "/plans", body);
      }
      if (operation.equals("policy")) {
        var parsed =
            parse(
                words,
                3,
                Set.of("--category", "--retention-days", "--max-count", "--max-policy-bytes"),
                Set.of("--clear-count", "--clear-policy-bytes"));
        if (!parsed.arguments().isEmpty() || !parsed.options().containsKey("--category"))
          throw invalid();
        var options = parsed.options();
        var body = new LinkedHashMap<String, Object>();
        body.put("category", options.get("--category"));
        body.put("retentionDays", options.get("--retention-days"));
        body.put(
            "maxCount",
            options.containsKey("--max-count") ? longInteger(options.get("--max-count")) : null);
        body.put(
            "maxPolicyBytes",
            options.containsKey("--max-policy-bytes")
                ? longInteger(options.get("--max-policy-bytes"))
                : null);
        body.put("clearCount", Boolean.parseBoolean(options.get("--clear-count")));
        body.put("clearPolicyBytes", Boolean.parseBoolean(options.get("--clear-policy-bytes")));
        return post(base + "/policy", body);
      }
      if (operation.equals("auto")) {
        if (words.size() < 4) throw invalid();
        String automatic = words.get(3);
        base += "/automatic";
        if (automatic.equals("history")) {
          exact(words, 4);
          return get(base + "/history");
        }
        if (Set.of("approve", "disable").contains(automatic)) {
          exact(words, 5);
          return post(base + "/" + id(words.get(4)) + "/" + automatic, Map.of());
        }
        if (automatic.equals("propose")) {
          var parsed =
              parse(
                  words,
                  4,
                  Set.of(
                      "--category",
                      "--project",
                      "--max-objects",
                      "--max-bytes",
                      "--interval-seconds"),
                  Set.of());
          if (!parsed.arguments().isEmpty()
              || !parsed
                  .options()
                  .keySet()
                  .containsAll(
                      Set.of("--category", "--max-objects", "--max-bytes", "--interval-seconds")))
            throw invalid();
          var body = storagePlanOptions(parsed.options(), project);
          body.put("intervalSeconds", longInteger(parsed.options().get("--interval-seconds")));
          return post(base + "/proposals", body);
        }
      }
      throw invalid();
    }
    if (name.equals("/embed")) {
      String base = "/api/v1/vector-documents";
      if (action.equals("list")) {
        exact(words, 2);
        return get(base);
      }
      if (action.equals("search")) {
        var parsed = parse(words, 2, Set.of("--top-k", "--threshold", "--source"), Set.of());
        if (parsed.arguments().isEmpty()) throw invalid();
        String query = base + "/search?query=" + value(String.join(" ", parsed.arguments()));
        if (parsed.options().containsKey("--top-k"))
          query += "&topK=" + integer(parsed.options().get("--top-k"));
        if (parsed.options().containsKey("--threshold")) {
          double threshold;
          try {
            threshold = Double.parseDouble(parsed.options().get("--threshold"));
          } catch (NumberFormatException error) {
            throw invalid();
          }
          if (!Double.isFinite(threshold)) throw invalid();
          query += "&threshold=" + threshold;
        }
        if (parsed.options().containsKey("--source"))
          query += "&source=" + value(parsed.options().get("--source"));
        return get(query);
      }
      if (action.equals("delete")) {
        var parsed = parse(words, 2, Set.of("--doc-id", "--source"), Set.of());
        if (!parsed.arguments().isEmpty() || parsed.options().size() != 1) throw invalid();
        return post(
            base + "/delete",
            Map.of(
                "docId",
                parsed.options().getOrDefault("--doc-id", ""),
                "source",
                parsed.options().getOrDefault("--source", "")));
      }
      var parsed = parse(words, action.equals("add") ? 2 : 1, Set.of(), Set.of());
      if (parsed.arguments().isEmpty()) throw invalid();
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      var documents =
          dev.mikoto2000.rei.core.completion.DocumentPathResolver.resolve(
                  parsed.arguments(), localBase)
              .stream()
              .map(path -> localBase.resolve(path).toAbsolutePath().normalize().toString())
              .toList();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      body.put("documents", documents);
      return post(base + "/ingestions", body);
    }
    if (name.equals("/schedule") || name.equals("/task")) {
      String base = name.equals("/schedule") ? "/api/v1/google-calendar" : "/api/v1/google-tasks";
      if (action.equals("auth") || name.equals("/schedule") && action.equals("refresh-token")) {
        exact(words, 2);
        if (project == null) throw new IllegalArgumentException("Select a Project first");
        var body = new LinkedHashMap<String, Object>();
        body.put("projectId", project);
        body.put("sessionId", session);
        return post(base + (action.equals("auth") ? "/authorize" : "/refresh-token"), body);
      }
      if (name.equals("/schedule")) {
        if (action.equals("list")) {
          var parsed =
              parse(words, words.size() == 1 ? 1 : 2, Set.of("--date", "--from", "--to"), Set.of());
          if (!parsed.arguments().isEmpty()) throw invalid();
          String query = "";
          for (String option : List.of("--date", "--from", "--to")) {
            if (parsed.options().containsKey(option)) {
              String value = parsed.options().get(option);
              if (option.equals("--date")) java.time.LocalDate.parse(value);
              query += (query.isEmpty() ? "?" : "&") + option.substring(2) + "=" + id(value);
            }
          }
          return get(base + "/events" + query);
        }
        if (action.equals("add")) {
          var parsed =
              parse(words, 2, Set.of("--start", "--end", "--location", "--description"), Set.of());
          if (parsed.arguments().isEmpty()
              || !parsed.options().containsKey("--start")
              || !parsed.options().containsKey("--end")) throw invalid();
          var body = new LinkedHashMap<String, Object>();
          body.put("title", String.join(" ", parsed.arguments()));
          for (String option : List.of("--start", "--end", "--location", "--description"))
            body.put(option.substring(2), parsed.options().get(option));
          return post(base + "/events", body);
        }
      } else {
        if (action.equals("list")) {
          var parsed =
              parse(
                  words, words.size() == 1 ? 1 : 2, Set.of("--priority", "--due-before"), Set.of());
          if (!parsed.arguments().isEmpty()) throw invalid();
          String query = "";
          for (String option : List.of("--priority", "--due-before")) {
            if (parsed.options().containsKey(option)) {
              String value = parsed.options().get(option);
              if (option.equals("--priority")) integer(value);
              if (option.equals("--due-before")) java.time.LocalDate.parse(value);
              query +=
                  (query.isEmpty() ? "?" : "&")
                      + (option.equals("--due-before") ? "dueBefore" : option.substring(2))
                      + "="
                      + id(value);
            }
          }
          return get(base + query);
        }
        if (action.equals("add")) {
          var parsed = parse(words, 2, Set.of("--due", "--priority"), Set.of());
          if (parsed.arguments().isEmpty()) throw invalid();
          var body = new LinkedHashMap<String, Object>();
          body.put("title", String.join(" ", parsed.arguments()));
          String due = parsed.options().get("--due");
          if (due != null) java.time.LocalDate.parse(due);
          body.put("due", due);
          body.put("priority", integer(parsed.options().getOrDefault("--priority", "3")));
          return post(base, body);
        }
        if (Set.of("done", "delete").contains(action)) {
          exact(words, 3);
          long value;
          try {
            value = Long.parseLong(words.get(2));
          } catch (NumberFormatException error) {
            throw invalid();
          }
          return action.equals("done")
              ? post(base + "/" + value + "/complete", Map.of())
              : new Request("DELETE", base + "/" + value, null);
        }
      }
      throw invalid();
    }
    if (name.equals("/memory") && Set.of("summarize", "consolidate", "export").contains(action)) {
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      String base = "/api/v1/projects/" + id(project) + "/memory/" + action;
      if (action.equals("export")) {
        var parsed = parse(words, 2, Set.of("--dir"), Set.of());
        if (!parsed.arguments().isEmpty()) throw invalid();
        var body = new LinkedHashMap<String, Object>();
        String directory = parsed.options().get("--dir");
        body.put(
            "directory",
            directory == null
                ? null
                : localBase.resolve(directory).toAbsolutePath().normalize().toString());
        return post(base, body);
      }
      if (session == null) throw new IllegalArgumentException("Select a Session first");
      boolean approval = false, approvalSpecified = false;
      for (int index = 2; index < words.size(); index++) {
        String option = words.get(index), value = "true";
        int equals = option.indexOf('=');
        if (equals >= 0) {
          value = option.substring(equals + 1);
          option = option.substring(0, equals);
        }
        if (approvalSpecified
            || !Set.of("--approve", "--save").contains(option)
            || !Set.of("true", "false").contains(value)) throw invalid();
        approval = Boolean.parseBoolean(value);
        approvalSpecified = true;
      }
      return post(base, Map.of("sessionId", session, "approve", approval));
    }
    if (name.equals("/sleep")) {
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      String base = "/api/v1/projects/" + id(project) + "/memory/sleep";
      if (words.size() == 1 || action.equals("preview")) {
        exact(words, words.size() == 1 ? 1 : 2);
        if (session == null) throw new IllegalArgumentException("Select a Session first");
        return post(base, Map.of("sessionId", session, "preview", words.size() == 2));
      }
      if (Set.of("status", "history", "requests").contains(action)) {
        exact(words, 2);
        return get(
            base
                + "/"
                + action
                + (action.equals("status") && session != null ? "?sessionId=" + id(session) : ""));
      }
      if (action.equals("cancel-request")) {
        var parsed = parse(words, 2, Set.of("--revision"), Set.of());
        if (parsed.arguments().size() != 1 || !parsed.options().containsKey("--revision"))
          throw invalid();
        long version = revision(parsed.options().get("--revision"));
        if (version < 1) throw invalid();
        return post(
            base + "/requests/" + id(parsed.arguments().getFirst()) + "/cancel",
            Map.of("revision", version));
      }
      throw invalid();
    }
    if (name.equals("/reflection")) {
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      var parsed =
          parseRepeating(
              words,
              1,
              Set.of(
                  "--revision",
                  "--evidence-hash",
                  "--note",
                  "--source-kind",
                  "--kind",
                  "--evidence",
                  "--observation"),
              Set.of(),
              "--evidence");
      var arguments = parsed.arguments();
      var selected = parsed.options();
      if (arguments.size() > 2) throw invalid();
      String operation = arguments.isEmpty() ? "list" : arguments.getFirst();
      String identity = arguments.size() == 2 ? arguments.get(1) : null;
      String base = "/api/v1/projects/" + id(project) + "/reflections";
      if (Set.of("list", "runs").contains(operation)) {
        if (identity != null) throw invalid();
        return get(base + (operation.equals("runs") ? "/runs" : ""));
      }
      if (Set.of("show", "run", "collect", "promote", "collect-run").contains(operation)) {
        if (identity == null) throw invalid();
        String encoded = id(identity);
        return switch (operation) {
          case "show" -> get(base + "/" + encoded);
          case "run" -> get(base + "/runs/" + encoded);
          case "collect" -> post(base + "/collect", Map.of("id", identity));
          case "promote" -> post(base + "/" + encoded + "/promote", Map.of());
          default -> {
            if (session == null) throw new IllegalArgumentException("Select a Session first");
            yield post(base + "/runs/" + encoded + "/collect", Map.of("sessionId", session));
          }
        };
      }
      if (session == null) throw new IllegalArgumentException("Select a Session first");
      if (operation.equals("lessons")) {
        if (identity != null) throw invalid();
        return get(base + "/lessons?sessionId=" + id(session));
      }
      if (operation.equals("lesson")) {
        if (identity == null) throw invalid();
        return get(base + "/lessons/" + id(identity) + "?sessionId=" + id(session));
      }
      var body = new LinkedHashMap<String, Object>();
      body.put("sessionId", session);
      body.put("id", identity);
      body.put("sourceKind", selected.getOrDefault("--source-kind", "RUN"));
      String kind = selected.getOrDefault("--kind", "FAILURE_PATTERN");
      if (!Set.of("FAILURE_PATTERN", "SUCCESSFUL_STRATEGY", "REPEATED_CORRECTION").contains(kind))
        throw invalid();
      body.put("kind", kind);
      try {
        body.put("revision", Long.parseLong(selected.getOrDefault("--revision", "-1")));
      } catch (NumberFormatException malformed) {
        throw invalid();
      }
      body.put("evidenceHash", selected.get("--evidence-hash"));
      body.put("note", selected.get("--note"));
      body.put(
          "evidence",
          selected.containsKey("--evidence")
              ? List.of(selected.get("--evidence").split(","))
              : null);
      body.put("observation", selected.get("--observation"));
      if (operation.equals("lesson-propose")) {
        if (identity != null) throw invalid();
        return post(base + "/lessons/proposals", body);
      }
      if (identity == null) throw invalid();
      String encoded = id(identity);
      return switch (operation) {
        case "lesson-observe" -> post(base + "/lessons/observe", body);
        case "lesson-observe-correction" -> post(base + "/lessons/observe-correction", body);
        case "lesson-validate" -> post(base + "/lessons/" + encoded + "/validate", body);
        case "lesson-counterexample" ->
            post(base + "/lessons/" + encoded + "/counterexamples", body);
        case "lesson-correct" -> post(base + "/lessons/" + encoded + "/corrections", body);
        case "lesson-forget" -> post(base + "/lessons/" + encoded + "/forget", body);
        default -> throw invalid();
      };
    }
    if (name.equals("/subagent")) {
      String base = "/api/v1/subagents";
      if (action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(base);
      }
      if (action.equals("show")) {
        exact(words, 3);
        return get(base + "/" + id(words.get(2)));
      }
      if (action.equals("reload")) {
        exact(words, 2);
        return post(base + "/reload", Map.of());
      }
      if (action.equals("init")) {
        exact(words, 3);
        return post(base + "/initialize", Map.of("id", words.get(2)));
      }
      if (action.equals("validate")) {
        exact(words, 3);
        return post(
            base + "/validate",
            Map.of(
                "file", localBase.resolve(words.get(2)).toAbsolutePath().normalize().toString()));
      }
      throw invalid();
    }
    if (name.equals("/config")) {
      if (action.equals("path")) {
        exact(words, 2);
        return get("/api/v1/config/path");
      }
      if (action.equals("init")) {
        var selected = options(words, 2, Set.of(), Set.of("--force"));
        return post(
            "/api/v1/config/initialize",
            Map.of("force", Boolean.parseBoolean(selected.getOrDefault("--force", "false"))));
      }
      throw invalid();
    }
    if (name.equals("/skill")) {
      if (action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get("/api/v1/skills/report");
      }
      if (action.equals("show")) {
        exact(words, 3);
        return get("/api/v1/skills/" + id(words.get(2)) + "/report");
      }
      if (action.equals("reload")) {
        exact(words, 2);
        return post("/api/v1/skills/reload-report", Map.of());
      }
      throw invalid();
    }
    if (name.equals("/paper") || name.equals("/material-review") || name.equals("/agent")) {
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      if (words.size() > 2049
          || words.stream().mapToLong(String::length).sum() > 16384
          || words.stream().anyMatch(word -> word.codePoints().anyMatch(Character::isISOControl)))
        throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      if (name.equals("/agent")) {
        var parsed =
            dev.mikoto2000.rei.externalagent.ExternalAgentCommandRequest.parse(
                String.join(" ", words));
        body.put("agent", parsed.agent());
        body.put("action", parsed.action());
        body.put("target", parsed.target());
        return post("/api/v1/external-agent/operations", body);
      }
      var args = words.subList(1, words.size());
      if (name.equals("/paper")) {
        try {
          dev.mikoto2000.rei.paper.PaperCommandRequest.parse(args.toArray(String[]::new));
        } catch (dev.mikoto2000.rei.paper.PaperException invalid) {
          throw new IllegalArgumentException(invalid.getMessage());
        }
      } else
        dev.mikoto2000.rei.externalagent.BeginnerReviewRequest.parse(args.toArray(String[]::new));
      body.put("arguments", List.copyOf(args));
      return post(
          name.equals("/paper") ? "/api/v1/paper/operations" : "/api/v1/material-reviews", body);
    }
    if (name.equals("/document") || name.equals("/repair")) {
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      if (words.size() < 3 || words.size() > 4) throw invalid();
      String identity = words.get(2), hash = words.size() == 4 ? words.get(3) : null;
      String pattern = name.equals("/document") ? "[a-f0-9-]{36}" : "[0-9a-fA-F-]{36}";
      var mutations =
          name.equals("/document")
              ? Set.of("rollback", "clean", "reconcile-single")
              : Set.of("apply");
      if (!identity.matches(pattern)
          || !(action.equals("show") && hash == null
              || mutations.contains(action) && hash != null && hash.matches("[a-f0-9]{64}")))
        throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      body.put("action", action);
      body.put("id", identity);
      body.put("hash", hash);
      return post("/api/v1/" + name.substring(1) + "/recovery", body);
    }
    if (name.equals("/timing")) {
      if (words.size() > 4) throw invalid();
      String target = "last";
      boolean details = false, selected = false;
      for (String argument : words.subList(1, words.size())) {
        if (argument.equals("--details")) {
          if (details) throw invalid();
          details = true;
        } else {
          if (selected || !argument.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
          target = argument;
          selected = true;
        }
      }
      return get(
          "/api/v1/timing?runId="
              + id(target)
              + "&details="
              + details
              + (project == null ? "" : "&projectId=" + id(project))
              + (session == null ? "" : "&sessionId=" + id(session)));
    }
    if (name.equals("/doctor")) {
      if (words.size() > 33 || words.stream().mapToInt(String::length).sum() > 4096)
        throw invalid();
      var checks = new LinkedHashSet<String>();
      boolean details = false;
      for (int index = 1; index < words.size(); index++) {
        String option = words.get(index);
        if (option.equals("--details") || option.equals("--details=true")) {
          details = true;
          continue;
        }
        if (option.equals("--details=false")) {
          details = false;
          continue;
        }
        String check;
        if (option.equals("--check")) {
          if (++index >= words.size()) throw invalid();
          check = words.get(index);
        } else if (option.startsWith("--check=")) check = option.substring(8);
        else throw invalid();
        check = check.toLowerCase(Locale.ROOT);
        if (!Set.of("connectivity", "inference", "codex", "claude", "microphone").contains(check))
          throw invalid();
        checks.add(check);
      }
      if (checks.isEmpty()) return get("/api/v1/doctor?details=" + details);
      if (project == null) throw new IllegalArgumentException("Select a Project first");
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      body.put("checks", List.copyOf(checks));
      body.put("details", details);
      return post("/api/v1/diagnostics", body);
    }
    if (name.equals("/models")) {
      exact(words, 1);
      return get("/api/v1/models");
    }
    if (name.equals("/model")) {
      if (words.size() == 1) return get("/api/v1/models/current");
      exact(words, 2);
      String model = words.get(1);
      if (model.isBlank()
          || model.length() > 1024
          || model.codePoints().anyMatch(Character::isISOControl)) throw invalid();
      return new Request("PUT", "/api/v1/models/current", Map.of("model", model));
    }
    if (name.equals("/profile")) {
      if (words.size() == 1 || action.equals("summary")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get("/api/v1/profile/report");
      }
      if (action.equals("path")) {
        exact(words, 2);
        return get("/api/v1/profile/path");
      }
      if (action.equals("chart")) {
        var selected = options(words, 2, Set.of("--bucket-seconds", "--width"), Set.of());
        long seconds;
        try {
          seconds = Long.parseLong(selected.getOrDefault("--bucket-seconds", "60"));
        } catch (NumberFormatException malformed) {
          throw invalid();
        }
        return get(
            "/api/v1/profile/chart?bucketSeconds="
                + Math.max(1L, seconds)
                + "&width="
                + Math.max(1, integer(selected.getOrDefault("--width", "40"))));
      }
      if (action.equals("mermaid")) {
        var selected = options(words, 2, Set.of("--limit"), Set.of());
        return get(
            "/api/v1/profile/mermaid?limit="
                + Math.max(1, integer(selected.getOrDefault("--limit", "12"))));
      }
      throw unsupported();
    }
    if (name.equals("/briefing")) {
      if (words.size() != 1 && !(words.size() == 2 && action.equals("today")) || project == null)
        throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      return post("/api/v1/briefing/commands/today", body);
    }
    if (name.equals("/search")) {
      if (project == null) throw invalid();
      var parsed =
          parse(
              words,
              1,
              Set.of("--vector-top-k", "--web-top-k", "--threshold", "--source"),
              Set.of());
      if (parsed.arguments().isEmpty()) throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("sessionId", session);
      body.put("query", String.join(" ", parsed.arguments()));
      body.put("vectorTopK", integer(parsed.options().getOrDefault("--vector-top-k", "3")));
      body.put("webTopK", integer(parsed.options().getOrDefault("--web-top-k", "5")));
      Double threshold =
          parsed.options().containsKey("--threshold")
              ? Double.valueOf(parsed.options().get("--threshold"))
              : null;
      if (threshold != null && !Double.isFinite(threshold)) throw invalid();
      body.put("threshold", threshold);
      body.put("source", parsed.options().get("--source"));
      return post("/api/v1/search/answers", body);
    }
    if (name.equals("/memory")
        && project != null
        && Set.of("list", "search", "show", "forget").contains(action)) {
      String base = "/api/v1/projects/" + id(project) + "/memory";
      if (action.equals("list")) {
        var selected =
            options(words, words.size() == 1 ? 1 : 2, Set.of("--limit", "--offset"), Set.of());
        return get(
            base
                + "?limit="
                + integer(selected.getOrDefault("--limit", "20"))
                + "&offset="
                + integer(selected.getOrDefault("--offset", "0")));
      }
      if (action.equals("search")) {
        var selected = parse(words, 2, Set.of("--limit"), Set.of());
        if (selected.arguments().isEmpty()) throw invalid();
        return get(
            base
                + "/search?query="
                + BackendClient.segment(String.join(" ", selected.arguments()))
                + "&limit="
                + integer(selected.options().getOrDefault("--limit", "10")));
      }
      exact(words, 3);
      return action.equals("show")
          ? get(base + "/" + id(words.get(2)))
          : post(base + "/" + id(words.get(2)) + "/forget", Map.of());
    }
    if (name.equals("/history")) return history(words, project, session);
    if (Set.of("/feed", "/skill", "/reminder", "/interest", "/memory").contains(name)) {
      String resource =
          switch (name) {
            case "/skill" -> "skills";
            case "/reminder" -> "reminders/commands";
            case "/interest" -> "interests";
            case "/memory" -> "memories";
            default -> "feed";
          };
      String base = "/api/v1/" + resource;
      if (name.equals("/interest") && action.equals("list")) {
        var selected = options(words, 2, Set.of("--hours"), Set.of());
        long hours = revision(selected.getOrDefault("--hours", "24"));
        if (hours < 1 || hours > 8760) throw invalid();
        return get(base + "?hours=" + hours);
      }
      if (action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(base);
      }
      if (action.equals("show")) {
        exact(words, 3);
        return get(base + "/" + id(words.get(2)));
      }
      if (action.equals("delete") && Set.of("/feed", "/reminder", "/memory").contains(name)) {
        if (words.size() != 3 && (words.size() != 4 || !words.get(3).equals("--yes")))
          throw invalid();
        return new Request("DELETE", base + "/" + id(words.get(2)), null);
      }
      if (name.equals("/skill") && action.equals("reload")) {
        exact(words, 2);
        return post(base + "/reload", Map.of());
      }
      if (name.equals("/feed") && Set.of("enable", "disable").contains(action)) {
        exact(words, 3);
        return new Request(
            "PATCH", base + "/" + id(words.get(2)), Map.of("enabled", action.equals("enable")));
      }
      if (name.equals("/feed") && action.equals("add")) {
        var parsed = parse(words, 2, Set.of("--name"), Set.of());
        var args = parsed.arguments();
        var selected = parsed.options();
        if (args.size() != 1 && (args.size() != 2 || !selected.isEmpty())) throw invalid();
        var body = new LinkedHashMap<String, Object>();
        body.put("url", args.getFirst());
        body.put("displayName", args.size() == 2 ? args.get(1) : selected.get("--name"));
        return post(base, body);
      }
      if (name.equals("/feed") && action.equals("edit")) {
        var parsed = parse(words, 2, Set.of("--name"), Set.of("--enabled", "--disabled"));
        if (parsed.arguments().size() != 1) throw invalid();
        boolean enabled = Boolean.parseBoolean(parsed.options().get("--enabled")),
            disabled = Boolean.parseBoolean(parsed.options().get("--disabled"));
        var body = new LinkedHashMap<String, Object>();
        body.put("displayName", parsed.options().get("--name"));
        body.put("enabled", enabled == disabled ? null : enabled);
        return new Request("PATCH", base + "/" + id(parsed.arguments().getFirst()), body);
      }
      if (name.equals("/reminder") && action.equals("add")) {
        var parsed = parse(words, 2, Set.of("--at", "--target", "--minutes-before"), Set.of());
        var selected = parsed.options();
        if (selected.isEmpty() && parsed.arguments().size() == 2)
          return post(
              base,
              Map.of("message", parsed.arguments().getFirst(), "at", parsed.arguments().get(1)));
        if (parsed.arguments().isEmpty()
            || !(selected.containsKey("--at") && selected.size() == 1
                || !selected.containsKey("--at")
                    && selected.containsKey("--target")
                    && selected.containsKey("--minutes-before")
                    && selected.size() == 2)) throw invalid();
        var body = new LinkedHashMap<String, Object>();
        body.put("message", String.join(" ", parsed.arguments()));
        String at = selected.get("--at"), target = selected.get("--target");
        try {
          java.time.OffsetDateTime.parse(at == null ? target : at);
        } catch (java.time.format.DateTimeParseException malformed) {
          throw invalid();
        }
        body.put("at", at);
        body.put("target", target);
        body.put(
            "minutesBefore",
            selected.containsKey("--minutes-before")
                ? revision(selected.get("--minutes-before"))
                : null);
        return post(base, body);
      }
      if (name.equals("/memory") && action.equals("add")) {
        exact(words, 5);
        return post(
            base, Map.of("content", words.get(2), "type", words.get(3), "scope", words.get(4)));
      }
      throw unsupported();
    }
    if (project == null) throw new IllegalArgumentException("Select a Project first");
    if (name.equals("/mode")) {
      if (session == null) throw new IllegalArgumentException("Select a Session first");
      String base = "/api/v1/sessions/" + id(session) + "/response-style";
      if (action.equals("list") || action.equals("status")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(base + "?projectId=" + id(project));
      }
      if (!Set.of("auto", "normal", "conversation").contains(action)) throw unsupported();
      boolean voiceOnly = false;
      if (words.size() > 2) {
        if (!action.equals("conversation")) throw invalid();
        String option = words.get(2), value = "true";
        if (option.startsWith("--voice-only=")) {
          exact(words, 3);
          value = option.substring("--voice-only=".length());
        } else if (option.equals("--voice-only")) {
          if (words.size() == 4) value = words.get(3);
          else exact(words, 3);
        } else throw invalid();
        if (!Set.of("true", "false").contains(value)) throw invalid();
        voiceOnly = Boolean.parseBoolean(value);
      } else exact(words, 2);
      return new Request(
          "PATCH",
          base,
          Map.of(
              "projectId",
              project,
              "style",
              action.toUpperCase(Locale.ROOT),
              "voiceOnly",
              voiceOnly));
    }
    String scoped = "/api/v1/projects/" + id(project);
    if (name.equals("/tasks")) {
      if (action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get("/api/v1/tasks?projectId=" + id(project));
      }
      if (action.equals("show")) {
        exact(words, 3);
        return get("/api/v1/tasks/" + id(words.get(2)) + "?projectId=" + id(project));
      }
      if (Set.of("cancel", "resume", "suspend").contains(action)) {
        exact(words, 5);
        var body = new LinkedHashMap<String, Object>();
        body.put("projectId", project);
        body.put("sessionId", session);
        body.put("expectedRunId", words.get(3));
        body.put("expectedRevision", revision(words.get(4)));
        return post("/api/v1/tasks/" + id(words.get(2)) + "/" + action, body);
      }
      throw unsupported();
    }
    if (name.equals("/checkpoint") && words.size() == 1) {
      if (session == null) throw new IllegalArgumentException("Select a Session first");
      return post(scoped + "/checkpoints", Map.of("sessionId", session));
    }
    if (name.equals("/resume")) {
      String base = scoped + "/checkpoints";
      if (action.equals("overview")) {
        exact(words, 2);
        return get(scoped + "/resume-overview");
      }
      if (words.size() == 1 || action.equals("list")) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(base + (words.size() == 1 ? "/report" : "/report/list"));
      }
      if (Set.of("show", "abandon").contains(action)) {
        exact(words, 3);
        String path = base + "/" + id(words.get(2));
        return action.equals("show") ? get(path + "/report") : post(path + "/abandon", Map.of());
      }
      exact(words, 2);
      return post(base + "/" + id(action) + "/resume", Map.of());
    }
    if (name.equals("/summarize")) {
      if (words.size() == 1) return get(scoped + "/summaries/latest");
      exact(words, 2);
      return post("/api/v1/summaries/cli", Map.of("projectId", project, "url", action));
    }
    if (name.equals("/image")) {
      boolean generate = action.equals("generate");
      var parsed =
          parse(
              words,
              generate ? 2 : 1,
              generate ? Set.of("--output", "--model", "--size") : Set.of(),
              generate ? Set.of("--raw") : Set.of());
      if (parsed.arguments().isEmpty()) throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("projectId", project);
      body.put("prompt", String.join(" ", parsed.arguments()));
      String size = parsed.options().get("--size");
      if (size != null) dev.mikoto2000.rei.image.ImageSize.parse(size);
      body.put("size", size);
      body.put("model", parsed.options().get("--model"));
      body.put(
          "output",
          parsed.options().containsKey("--output")
              ? localBase
                  .resolve(parsed.options().get("--output"))
                  .toAbsolutePath()
                  .normalize()
                  .toString()
              : null);
      body.put("raw", Boolean.parseBoolean(parsed.options().get("--raw")));
      return post("/api/v1/images/cli", body);
    }
    if (name.equals("/work")) {
      if (Set.of("show", "list").contains(action)) {
        exact(words, words.size() == 1 ? 1 : 2);
        return get(scoped + "/work-context/report");
      }
      if (Set.of("summary", "history").contains(action)) {
        exact(words, 2);
        return get(
            scoped + "/work-context/" + action + (action.equals("history") ? "/report" : ""));
      }
      if (action.equals("update")) {
        exact(words, 2);
        if (session == null) throw new IllegalArgumentException("Select a Session first");
        return post(
            scoped + "/work-context/updates", Map.of("projectId", project, "sessionId", session));
      }
      throw unsupported();
    }
    String resource =
        switch (name) {
          case "/checkpoint" -> "checkpoints";
          case "/approval" -> "approvals";
          case "/dependency" -> "dependencies";
          case "/attention" -> "attention";
          case "/timer" -> "schedules";
          case "/goal" -> "goals";
          default -> throw unsupported();
        };
    String base = scoped + "/" + resource;
    if (action.equals("list")) {
      exact(words, words.size() == 1 ? 1 : 2);
      return get(base);
    }
    if (action.equals("show")) {
      exact(words, 3);
      return get(base + "/" + id(words.get(2)));
    }
    if (action.equals("history") && Set.of("/goal", "/timer", "/dependency").contains(name)) {
      exact(words, 3);
      return get(base + "/" + id(words.get(2)) + "/history");
    }
    if (name.equals("/goal") && action.equals("create")) {
      if (words.size() < 3 || session == null) throw invalid();
      var options =
          options(
              words,
              3,
              Set.of(
                  "--file",
                  "--sha256",
                  "--criteria-json",
                  "--completion-json",
                  "--max-runs",
                  "--max-llm-calls"),
              Set.of());
      if (options.containsKey("--criteria-json")
          && (options.containsKey("--file") || options.containsKey("--sha256"))) throw invalid();
      if (!options.containsKey("--criteria-json")
          && (!options.containsKey("--file") || !options.containsKey("--sha256"))) throw invalid();
      long runs = revision(options.getOrDefault("--max-runs", "3")),
          calls = revision(options.getOrDefault("--max-llm-calls", "20"));
      if (runs < 1 || runs > 10 || calls < 1 || calls > 100) throw invalid();
      var body = new LinkedHashMap<String, Object>();
      body.put("sessionId", session);
      body.put("objective", words.get(2));
      body.put("maxRuns", runs);
      body.put("maxLlmCalls", calls);
      for (String option : List.of("--file", "--sha256", "--criteria-json", "--completion-json"))
        if (options.containsKey(option)) {
          String field =
              switch (option) {
                case "--criteria-json" -> "criteriaJson";
                case "--completion-json" -> "completionJson";
                default -> option.substring(2);
              };
          body.put(field, options.get(option));
        }
      return post(base, body);
    }
    if (name.equals("/goal") && action.equals("completion")) {
      if (words.size() < 3) throw invalid();
      var options = options(words, 3, Set.of("--completion-json", "--proof-json"), Set.of());
      if (options.size() != 1) throw invalid();
      String json = options.values().iterator().next();
      if (json.length() > 32768) throw invalid();
      try {
        var body = COMMAND_JSON.readTree(json);
        if (body == null || !body.isObject()) throw invalid();
        boolean definition = options.containsKey("--completion-json");
        return new Request(
            definition ? "PUT" : "POST",
            base + "/" + id(words.get(2)) + (definition ? "/completion" : "/completion-evidence"),
            body);
      } catch (java.io.IOException malformed) {
        throw invalid();
      }
    }
    if (name.equals("/checkpoint") && action.equals("inspect")) {
      exact(words, 3);
      return get(base + "/" + id(words.get(2)) + "/reconciliation");
    }
    if (name.equals("/checkpoint") && action.equals("save")) {
      exact(words, 2);
      if (session == null) throw new IllegalArgumentException("Select a Session first");
      return post(base, Map.of("sessionId", session));
    }
    if (name.equals("/checkpoint") && action.equals("abandon")) {
      exact(words, 3);
      return post(base + "/" + id(words.get(2)) + "/abandon", Map.of());
    }
    if (name.equals("/approval") && Set.of("approve", "deny", "reject").contains(action)) {
      boolean continued =
          words.size() == 4 && words.get(3).equals("--continue") && action.equals("approve");
      if (!continued) exact(words, 3);
      return post(
          base + "/" + id(words.get(2)) + "/decision",
          continued
              ? Map.of("approved", true, "continueRun", true)
              : Map.of("approved", action.equals("approve")));
    }
    if (Set.of("/goal", "/timer").contains(name) && action.equals("reconcile")) {
      if (words.size() < 3) throw invalid();
      var options =
          options(words, 3, Set.of("--run-id"), Set.of("--acknowledge-uncertain-side-effects"));
      String run = options.get("--run-id");
      if (run == null
          || run.isBlank()
          || run.length() > 128
          || !Boolean.parseBoolean(options.get("--acknowledge-uncertain-side-effects")))
        throw invalid();
      return post(
          base + "/" + id(words.get(2)) + "/reconcile",
          Map.of("expectedRunId", run, "acknowledgeUncertainSideEffects", true));
    }
    if (name.equals("/goal") && action.equals("wait")) {
      if (words.size() < 3) throw invalid();
      var options = options(words, 3, Set.of("--dependency-id", "--wait-reason"), Set.of());
      String path = base + "/" + id(words.get(2)) + "/wait";
      if (!options.containsKey("--dependency-id")) {
        if (!options.isEmpty()) throw invalid();
        return get(path);
      }
      if (session == null)
        throw new IllegalArgumentException("Select the owning Goal Session first");
      return post(
          path,
          Map.of(
              "dependencyId",
              options.get("--dependency-id"),
              "reason",
              options.getOrDefault("--wait-reason", "dependency_wait"),
              "sessionId",
              session));
    }
    if (name.equals("/goal")
        && Set.of("wait-activate", "wait-resume", "wait-cancel").contains(action)) {
      if (words.size() < 3) throw invalid();
      var options = options(words, 3, Set.of("--wait-version"), Set.of());
      if (!options.containsKey("--wait-version")) throw invalid();
      long version = revision(options.get("--wait-version"));
      if (version < 1) throw invalid();
      return post(
          base + "/" + id(words.get(2)) + "/wait/" + action.substring(5),
          Map.of("expectedVersion", version));
    }
    if (name.equals("/attention") && action.equals("ack")) {
      exact(words, 3);
      return post(base + "/" + id(words.get(2)) + "/ack", Map.of());
    }
    if (name.equals("/attention") && action.equals("delivery")) {
      exact(words, 3);
      return get(base + "/" + id(words.get(2)) + "/delivery");
    }
    if (name.equals("/attention") && Set.of("deliver", "retry-delivery").contains(action)) {
      if (words.size() < 3) throw invalid();
      boolean retry = action.equals("retry-delivery");
      var options =
          options(words, 3, Set.of(), retry ? Set.of("--acknowledge-duplicate-risk") : Set.of());
      return post(
          base + "/" + id(words.get(2)) + "/delivery",
          Map.of(
              "retry",
              retry,
              "acknowledgeDuplicateRisk",
              Boolean.parseBoolean(options.get("--acknowledge-duplicate-risk"))));
    }
    if (name.equals("/goal") && Set.of("run", "cancel", "verify").contains(action)
        || name.equals("/timer") && Set.of("activate", "cancel").contains(action)) {
      exact(words, 3);
      return post(base + "/" + id(words.get(2)) + "/" + action, Map.of());
    }
    if (name.equals("/goal") && action.equals("progress")) {
      exact(words, 3);
      return get(base + "/" + id(words.get(2)) + "/completion-progress");
    }
    if (name.equals("/dependency") && Set.of("check", "cancel").contains(action)) {
      exact(words, 3);
      return post(base + "/" + id(words.get(2)) + "/" + action, Map.of());
    }
    if (name.equals("/dependency") && action.equals("answer")) {
      if (words.size() == 5 && !words.get(3).startsWith("--"))
        return post(
            base + "/" + id(words.get(2)) + "/answer",
            Map.of("expectedVersion", revision(words.get(3)), "answer", words.get(4)));
      var selected = parse(words, 2, Set.of("--value"), Set.of());
      if (selected.arguments().size() != 1 || !selected.options().containsKey("--value"))
        throw invalid();
      return post(
          base + "/" + id(selected.arguments().getFirst()) + "/human-answer",
          Map.of("value", selected.options().get("--value")));
    }
    throw unsupported();
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  private static Request history(List<String> words, String project, String session) {
    String action = words.size() > 1 ? words.get(1) : "";
    var query = new LinkedHashMap<String, Object>();
    if (action.isEmpty() || action.startsWith("--")) {
      var selected = options(words, 1, Set.of("--limit", "--project-id", "--cursor"), Set.of());
      if (selected.containsKey("--limit")) query.put("limit", integer(selected.get("--limit")));
      if (selected.containsKey("--project-id"))
        query.put("projectId", selected.get("--project-id"));
      if (selected.containsKey("--cursor")) query.put("cursor", selected.get("--cursor"));
      return query("/api/v1/history/report", query);
    }
    if (project == null) throw new IllegalArgumentException("Select a Project first");
    query.put("selectedProjectId", project);
    if (action.equals("list")) {
      var selected = options(words, 2, Set.of("--project", "--limit", "--offset"), Set.of());
      if (selected.containsKey("--project")) query.put("project", selected.get("--project"));
      query.put("limit", integer(selected.getOrDefault("--limit", "50")));
      query.put("offset", integer(selected.getOrDefault("--offset", "0")));
      return query("/api/v1/history/conversations", query);
    }
    if (action.equals("show")) {
      var parsed =
          parse(words, 2, Set.of("--project", "--last", "--limit", "--cursor"), Set.of("--all"));
      var selected = parsed.options();
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (parsed.arguments().size() > 1
          || selected.containsKey("--last") && selected.containsKey("--all")) throw invalid();
      boolean legacy =
          selected.containsKey("--project")
              || selected.containsKey("--last")
              || selected.containsKey("--all");
      if (legacy && (selected.containsKey("--limit") || selected.containsKey("--cursor")))
        throw invalid();
      if (!legacy && parsed.arguments().isEmpty() && selected.containsKey("--cursor"))
        throw invalid();
      if (legacy && session != null) query.put("sessionId", session);
      if (selected.containsKey("--project")) query.put("project", selected.get("--project"));
      if (!parsed.arguments().isEmpty()) query.put("conversation", parsed.arguments().getFirst());
      if (legacy) {
        query.put("last", integer(selected.getOrDefault("--last", "50")));
        query.put("all", Boolean.parseBoolean(selected.get("--all")));
      } else {
        if (selected.containsKey("--limit")) query.put("limit", integer(selected.get("--limit")));
        if (selected.containsKey("--cursor")) query.put("cursor", selected.get("--cursor"));
      }
      return query("/api/v1/history/" + (legacy ? "conversation" : "show"), query);
    }
    if (action.equals("search")) {
      var parsed = parse(words, 2, Set.of("--limit"), Set.of("--current", "--all"));
      var selected = parsed.options();
      if (parsed.arguments().isEmpty()
          || selected.containsKey("--current") && selected.containsKey("--all")) throw invalid();
      query.put("query", String.join(" ", parsed.arguments()));
      query.put(
          "scope",
          selected.containsKey("--all")
                  || selected.containsKey("--current")
                      && !Boolean.parseBoolean(selected.get("--current"))
              ? "ALL_PROJECTS"
              : selected.containsKey("--current")
                  ? "CURRENT_PROJECT_ONLY"
                  : "CURRENT_PROJECT_PREFERRED");
      query.put("limit", integer(selected.getOrDefault("--limit", "10")));
      return query("/api/v1/history/search", query);
    }
    throw unsupported();
  }

  /** 軽量CLIでの対話で用いる検索条件を返す。 */
  private static Request query(String path, Map<String, ?> parameters) {
    return get(
        path
            + (parameters.isEmpty()
                ? ""
                : "?"
                    + parameters.entrySet().stream()
                        .map(
                            entry ->
                                BackendClient.segment(entry.getKey())
                                    + "="
                                    + BackendClient.segment(entry.getValue().toString()))
                        .collect(java.util.stream.Collectors.joining("&"))));
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static String text(List<String> words, int start) {
    if (words.size() <= start) throw invalid();
    return String.join(" ", words.subList(start, words.size()));
  }

  /** 軽量CLIでの対話で用いる選択可能な設定を返す。 */
  private static Request secretary(List<String> words, String selectedProject) {
    String action = words.size() > 1 ? words.get(1) : "list";
    boolean identified =
        Set.of("show", "sources", "history", "related", "update", "approve", "done", "cancel")
            .contains(action);
    if (identified && words.size() < 3) throw invalid();
    Set<String> allowed = new HashSet<>(Set.of("--project"));
    switch (action) {
      case "list" -> allowed.addAll(Set.of("--limit", "--offset"));
      case "show", "sources", "history", "related" -> {}
      case "extract" -> allowed.addAll(Set.of("--key", "--title"));
      case "add", "update" -> {
        allowed.addAll(Set.of("--title", "--intent", "--due", "--priority", "--tag", "--ref"));
        allowed.addAll(action.equals("add") ? Set.of("--key") : Set.of("--status", "--revision"));
      }
      case "approve", "done", "cancel" -> allowed.add("--revision");
      default -> throw invalid();
    }
    var options = options(words, identified ? 3 : 2, allowed, Set.of());
    String project = options.getOrDefault("--project", selectedProject);
    if (project == null || project.isBlank())
      throw new IllegalArgumentException("Select a Project first");
    String base = "/api/v1/secretary/tasks";
    if (action.equals("list")) {
      int limit = Integer.parseInt(options.getOrDefault("--limit", "100"));
      int offset = Integer.parseInt(options.getOrDefault("--offset", "0"));
      if (limit < 1 || limit > 1000 || offset < 0) throw invalid();
      return query(base, Map.of("projectId", project, "limit", limit, "offset", offset));
    }
    if (Set.of("show", "sources", "history", "related").contains(action))
      return get(
          base
              + "/"
              + id(words.get(2))
              + (action.equals("show") ? "" : "/" + action)
              + "?projectId="
              + id(project));
    var body = new LinkedHashMap<String, Object>();
    body.put("projectId", project);
    if (action.equals("extract")) {
      if (!options.containsKey("--key") || !options.containsKey("--title")) throw invalid();
      body.put("eventId", options.get("--key"));
      body.put("text", options.get("--title"));
      return post(base + "/extract", body);
    }
    long revision = Long.parseLong(options.getOrDefault("--revision", "0"));
    if (revision < 0) throw invalid();
    if (Set.of("approve", "done", "cancel").contains(action)) {
      body.put("revision", revision);
      body.put(
          "status",
          switch (action) {
            case "approve" -> "OPEN";
            case "done" -> "DONE";
            default -> "CANCELLED";
          });
      return post(base + "/" + id(words.get(2)) + "/transition", body);
    }
    if (!options.containsKey("--title") || options.get("--title").isBlank()) throw invalid();
    if (action.equals("add") && !options.containsKey("--key")) throw invalid();
    int priority = Integer.parseInt(options.getOrDefault("--priority", "3"));
    if (priority < 1 || priority > 5) throw invalid();
    String intent = options.getOrDefault("--intent", "TODO");
    String status = options.getOrDefault("--status", "OPEN");
    if (!Set.of("IDEA", "TODO", "COMMITMENT").contains(intent)
        || !Set.of("CANDIDATE", "OPEN", "WAITING", "DONE", "CANCELLED").contains(status))
      throw invalid();
    body.put("title", options.get("--title"));
    body.put("intent", intent);
    body.put("status", status);
    body.put("priority", priority);
    body.put(
        "due",
        options.containsKey("--due")
            ? java.time.LocalDate.parse(options.get("--due")).toString()
            : null);
    body.put(
        "tags",
        options.containsKey("--tag") ? List.of(options.get("--tag").split(",")) : List.of());
    body.put(
        "references",
        options.containsKey("--ref") ? List.of(options.get("--ref").split(",")) : List.of());
    body.put("revision", revision);
    if (action.equals("add")) {
      body.put("key", options.get("--key"));
      return post(base, body);
    }
    return new Request("PUT", base + "/" + id(words.get(2)), body);
  }

  private static Map<String, String> options(
      List<String> words, int start, Set<String> values, Set<String> flags) {
    var parsed = parse(words, start, values, flags);
    if (!parsed.arguments().isEmpty()) throw invalid();
    return parsed.options();
  }

  /**
   * 軽量CLIでの対話で受け渡す解釈済みを表す。
   *
   * @param arguments 引数
   * @param options 選択可能な設定
   */
  private record Parsed(List<String> arguments, Map<String, String> options) {}

  /** 入力を機能内で扱えるよう、Repeatingを解釈する。 */
  private static Parsed parseRepeating(
      List<String> words, int start, Set<String> values, Set<String> flags, String repeated) {
    var normalized = new ArrayList<String>();
    var collected = new ArrayList<String>();
    for (int index = start; index < words.size(); index++) {
      String word = words.get(index);
      if (word.equals("--")) {
        normalized.addAll(words.subList(index, words.size()));
        break;
      }
      if (word.equals(repeated)) {
        if (++index >= words.size()) throw invalid();
        collected.add(words.get(index));
      } else if (word.startsWith(repeated + "="))
        collected.add(word.substring(repeated.length() + 1));
      else normalized.add(word);
    }
    if (!collected.isEmpty()) normalized.addFirst(repeated + "=" + String.join(",", collected));
    return parse(normalized, 0, values, flags);
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private static Parsed parse(
      List<String> words, int start, Set<String> values, Set<String> flags) {
    var parsed = new HashMap<String, String>();
    var arguments = new ArrayList<String>();
    for (int index = start; index < words.size(); index++) {
      String option = words.get(index);
      if (option.equals("--")) {
        arguments.addAll(words.subList(index + 1, words.size()));
        break;
      }
      if (!option.startsWith("--")) {
        arguments.add(option);
        continue;
      }
      String attached = null;
      int equals = option.indexOf('=');
      if (equals >= 0) {
        attached = option.substring(equals + 1);
        option = option.substring(0, equals);
      }
      if (parsed.containsKey(option)) throw invalid();
      if (flags.contains(option)) {
        if (attached != null && !Set.of("true", "false").contains(attached)) throw invalid();
        parsed.put(option, attached == null ? "true" : attached);
        continue;
      }
      if (!values.contains(option)) throw invalid();
      if (attached == null) {
        if (++index >= words.size()) throw invalid();
        attached = words.get(index);
      }
      if (attached.isBlank() || attached.startsWith("--")) throw invalid();
      parsed.put(option, attached);
    }
    return new Parsed(List.copyOf(arguments), Map.copyOf(parsed));
  }

  /** 軽量CLIでの対話で用いる改訂番号を返す。 */
  private static long revision(String value) {
    try {
      long revision = Long.parseLong(value);
      if (revision < 0) throw invalid();
      return revision;
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  /** 軽量CLIでの対話で用いる整数を返す。 */
  private static long longInteger(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  /** 軽量CLIでの対話において、現在保存対象範囲に該当するか判定する。 */
  private static boolean currentStorageScope(Map<String, String> options, String project) {
    String scope = options.get("--project");
    if (scope != null && !scope.equals("current")) throw invalid();
    if (scope != null && project == null)
      throw new IllegalArgumentException("Select a Project first");
    return scope != null;
  }

  /** 軽量CLIでの対話で用いる保存計画選択可能な設定を返す。 */
  private static LinkedHashMap<String, Object> storagePlanOptions(
      Map<String, String> options, String project) {
    var body = new LinkedHashMap<String, Object>();
    body.put("projectId", project);
    body.put("current", currentStorageScope(options, project));
    body.put("category", options.getOrDefault("--category", "raw-results"));
    body.put("maxObjects", integer(options.getOrDefault("--max-objects", "100")));
    body.put("maxBytes", longInteger(options.getOrDefault("--max-bytes", "16777216")));
    return body;
  }

  /** 軽量CLIでの対話で用いる整数を返す。 */
  private static int integer(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  /** 軽量CLIでの対話に関する完全一致を処理する。 */
  private static void exact(List<String> words, int size) {
    if (words.size() != size) throw invalid();
  }

  /** 軽量CLIでの対話で用いる識別子を返す。 */
  private static String id(String value) {
    if (value == null || value.isBlank() || value.length() > 256) throw invalid();
    return BackendClient.segment(value);
  }

  /** 後続の処理で使う値を返す。 */
  private static String value(String value) {
    if (value == null || value.isBlank() || value.length() > 16384 || value.indexOf('\0') >= 0)
      throw invalid();
    return BackendClient.segment(value);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  private static Request get(String path) {
    return new Request("GET", path, null);
  }

  /** 軽量CLIでの対話で用いる投稿を返す。 */
  private static Request post(String path, Object body) {
    return new Request("POST", path, body);
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid command arguments; inspect /help and docs/lightweight-cli-client.md");
  }

  /** 軽量CLIでの対話で用いる未対応を返す。 */
  private static IllegalArgumentException unsupported() {
    return new IllegalArgumentException(
        "This operation is not yet available in CLI; use legacy-shell. This input was not sent to the model.");
  }
}
