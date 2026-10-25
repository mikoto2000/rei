package dev.mikoto2000.rei.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded Gmail REST protocol. Mail content is data; no model or delivery policy is invoked here.
 */
final class GmailSecretaryProtocol {
  @FunctionalInterface
  interface Wire {
    JsonNode call(
        String method, String path, Map<String, String> query, Object body, boolean write);
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private final Wire wire;
  private final Clock clock;

  GmailSecretaryProtocol(Wire wire, Clock clock) {
    this.wire = wire;
    this.clock = clock;
  }

  private static String value(JsonNode node, String key) {
    return node.path(key).asText("");
  }

  private static String bound(String value, int max) {
    return value.substring(0, Math.min(value.length(), max));
  }

  private static String id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}"))
      throw new IllegalArgumentException("Invalid Gmail id");
    return value;
  }

  private static void fields(Map<String, String> args, String... allowed) {
    if (!Set.of(allowed).containsAll(args.keySet()))
      throw new IllegalArgumentException("Unknown Gmail arguments");
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String header(JsonNode message, String name) {
    for (var h : message.path("payload").path("headers"))
      if (name.equalsIgnoreCase(value(h, "name"))) return bound(value(h, "value"), 2048);
    return "";
  }

  private static String decodedSubject(String value) {
    try {
      return jakarta.mail.internet.MimeUtility.decodeText(value);
    } catch (java.io.UnsupportedEncodingException invalid) {
      return value;
    }
  }

  private static boolean label(JsonNode message, String name) {
    for (var label : message.path("labelIds")) if (name.equals(label.asText())) return true;
    return false;
  }

  private SecretaryProvider.Item item(JsonNode message, boolean body) {
    String messageId = id(value(message, "id"));
    String subject = bound(decodedSubject(header(message, "Subject")), 500);
    boolean inbound = !label(message, "SENT") && !label(message, "DRAFT");
    boolean unread = label(message, "UNREAD");
    boolean actionable =
        inbound
            && (label(message, "IMPORTANT")
                || unread
                    && subject.matches("(?is).*(reply|please|request|review|返信|対応|確認|お願い|[?？]).*"));
    var data = new HashMap<String, String>();
    data.put("threadId", value(message, "threadId"));
    data.put("from", header(message, "From"));
    data.put("to", header(message, "To"));
    data.put("messageId", header(message, "Message-ID"));
    data.put("references", header(message, "References"));
    data.put("inbound", Boolean.toString(inbound));
    long millis = message.path("internalDate").asLong(clock.millis());
    data.put("internalDate", Long.toString(millis));
    String revision =
        hash(
            value(message, "historyId")
                + ":"
                + message.path("labelIds")
                + ":"
                + subject
                + ":"
                + header(message, "From"));
    return new SecretaryProvider.Item(
        messageId,
        revision,
        "MAIL",
        subject,
        body ? body(message.path("payload"), 0) : "",
        unread ? "UNREAD" : inbound ? "INBOUND" : "SENT",
        Instant.ofEpochMilli(millis),
        null,
        actionable,
        data);
  }

  private static String body(JsonNode part, int depth) {
    if (depth > 8) return "";
    String mime = value(part, "mimeType");
    String raw = value(part.path("body"), "data");
    if (!raw.isEmpty() && (mime.equals("text/plain") || mime.equals("text/html"))) {
      try {
        byte[] decoded = Base64.getUrlDecoder().decode(raw);
        if (decoded.length > 131072)
          throw new SecretaryProvider.Failure("BODY_TOO_LARGE", 0, false);
        String text = new String(decoded, StandardCharsets.UTF_8);
        if (mime.equals("text/html"))
          text =
              text.replaceAll("<[^>]{0,2048}>", " ")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&amp;", "&");
        return bound(text, 32768);
      } catch (IllegalArgumentException invalid) {
        throw new SecretaryProvider.Failure("INVALID_MIME", 0, false);
      }
    }
    StringBuilder result = new StringBuilder();
    for (var child : part.path("parts")) {
      if (result.length() >= 32768) break;
      String text = body(child, depth + 1);
      if (!text.isBlank()) result.append(text).append('\n');
    }
    return bound(result.toString(), 32768);
  }

  SecretaryProvider.Page poll(SecretaryConnections.Connection connection, String cursor) {
    JsonNode state = JSON.createObjectNode();
    if (!cursor.isBlank())
      try {
        if (cursor.length() > 16000) throw new IllegalArgumentException();
        state = JSON.readTree(Base64.getUrlDecoder().decode(cursor));
      } catch (java.io.IOException | IllegalArgumentException invalid) {
        throw new SecretaryProvider.Failure("INVALID_CURSOR", 0, false);
      }
    if (!value(state, "stage").equals("HISTORY")) {
      String anchor = value(state, "anchor");
      if (anchor.isBlank())
        anchor =
            value(
                wire.call("GET", "/gmail/v1/users/me/profile", Map.of(), null, false), "historyId");
      var page = list(Map.of("q", "newer_than:30d"), value(state, "page"), false);
      return new SecretaryProvider.Page(
          page.items(),
          cursor(
              page.cursor().isEmpty() ? "HISTORY" : "FULL", anchor, page.cursor(), "", List.of()),
          page.partial());
    }
    String anchor = value(state, "anchor");
    if (!anchor.matches("[0-9]{1,30}"))
      return new SecretaryProvider.Page(List.of(), cursor("FULL", "", "", "", List.of()), true);
    var pending = new java.util.LinkedHashSet<String>();
    for (var item : state.path("pending")) pending.add(id(item.asText()));
    String page = value(state, "page"), nextAnchor = value(state, "nextAnchor");
    if (pending.isEmpty()) {
      var query = new HashMap<String, String>();
      query.put("startHistoryId", anchor);
      query.put("maxResults", "5");
      if (!page.isBlank()) query.put("pageToken", page);
      JsonNode response;
      try {
        response = wire.call("GET", "/gmail/v1/users/me/history", query, null, false);
      } catch (SecretaryProvider.Failure expired) {
        if (expired.code().equals("NOT_FOUND"))
          return new SecretaryProvider.Page(List.of(), cursor("FULL", "", "", "", List.of()), true);
        throw expired;
      }
      for (var history : response.path("history"))
        for (var message : history.path("messages")) pending.add(id(value(message, "id")));
      if (pending.size() > 200)
        return new SecretaryProvider.Page(List.of(), cursor("FULL", "", "", "", List.of()), true);
      page = value(response, "nextPageToken");
      nextAnchor = value(response, "historyId");
    }
    var items = new ArrayList<SecretaryProvider.Item>();
    var remaining = new ArrayList<String>();
    for (String message : pending) {
      if (items.size() >= 5) {
        remaining.add(message);
        continue;
      }
      try {
        items.add(
            item(
                wire.call(
                    "GET",
                    "/gmail/v1/users/me/messages/" + message,
                    Map.of("format", "metadata"),
                    null,
                    false),
                false));
      } catch (SecretaryProvider.Failure deleted) {
        if (!deleted.code().equals("NOT_FOUND")) throw deleted;
        items.add(
            new SecretaryProvider.Item(
                message,
                "deleted:" + anchor,
                "MAIL",
                "Deleted message",
                "",
                "DELETED",
                clock.instant(),
                null,
                false,
                Map.of()));
      }
    }
    boolean partial = !remaining.isEmpty() || !page.isBlank();
    if (!partial && nextAnchor.matches("[0-9]{1,30}")) anchor = nextAnchor;
    return new SecretaryProvider.Page(
        items,
        cursor("HISTORY", anchor, page, remaining.isEmpty() ? "" : nextAnchor, remaining),
        partial);
  }

  private static String cursor(
      String stage, String anchor, String page, String nextAnchor, List<String> pending) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              JSON.writeValueAsBytes(
                  Map.of(
                      "stage",
                      stage,
                      "anchor",
                      anchor,
                      "page",
                      page,
                      "nextAnchor",
                      nextAnchor,
                      "pending",
                      pending)));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private SecretaryProvider.Page list(Map<String, String> args, String cursor, boolean full) {
    fields(args, "q", "page");
    var query = new HashMap<String, String>();
    query.put("maxResults", "5");
    query.put("q", bound(args.getOrDefault("q", "in:inbox"), 512));
    String page = args.getOrDefault("page", cursor);
    if (!page.isBlank()) {
      if (page.length() > 2048 || page.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid Gmail cursor");
      query.put("pageToken", page);
    }
    var response = wire.call("GET", "/gmail/v1/users/me/messages", query, null, false);
    var items = new ArrayList<SecretaryProvider.Item>();
    for (var entry : response.path("messages")) {
      if (items.size() >= 5) break;
      var message =
          wire.call(
              "GET",
              "/gmail/v1/users/me/messages/" + id(value(entry, "id")),
              Map.of("format", full ? "full" : "metadata"),
              null,
              false);
      items.add(item(message, full));
    }
    return new SecretaryProvider.Page(
        items, value(response, "nextPageToken"), !value(response, "nextPageToken").isEmpty());
  }

  SecretaryProvider.Page read(
      SecretaryConnections.Connection connection, String operation, Map<String, String> args) {
    if (Set.of("LIST", "UNREAD", "NEEDS_REPLY").contains(operation)) {
      fields(args, "q", "page");
      var requested = new HashMap<>(args);
      if (!operation.equals("LIST")) requested.put("q", "in:inbox is:unread");
      var result = list(requested, "", false);
      return operation.equals("NEEDS_REPLY")
          ? new SecretaryProvider.Page(
              result.items().stream().filter(SecretaryProvider.Item::actionable).toList(),
              result.cursor(),
              result.partial())
          : result;
    }
    if (operation.equals("THREAD")) {
      fields(args, "id");
      var response =
          wire.call(
              "GET",
              "/gmail/v1/users/me/threads/" + id(args.get("id")),
              Map.of("format", "full"),
              null,
              false);
      var items = new ArrayList<SecretaryProvider.Item>();
      var messages = response.path("messages");
      for (int index = Math.max(0, messages.size() - 50); index < messages.size(); index++)
        items.add(item(messages.get(index), true));
      return new SecretaryProvider.Page(items, "", response.path("messages").size() > 50);
    }
    if (!Set.of("MESSAGE", "REPLY").contains(operation))
      throw new IllegalArgumentException("Unknown Gmail read operation");
    fields(args, "id");
    var message =
        wire.call(
            "GET",
            "/gmail/v1/users/me/messages/" + id(args.get("id")),
            Map.of("format", "full"),
            null,
            false);
    var item = item(message, true);
    if (operation.equals("MESSAGE")) return new SecretaryProvider.Page(List.of(item), "", false);
    String address =
        mailbox(
            header(message, "Reply-To").isBlank()
                ? header(message, "From")
                : header(message, "Reply-To"));
    String subject = item.title().matches("(?is)^re:.*") ? item.title() : "Re: " + item.title();
    var data = new HashMap<String, String>();
    data.put("to", address);
    data.put("subject", subject);
    data.put("threadId", item.data().get("threadId"));
    String original = messageId(header(message, "Message-ID"), true);
    data.put("inReplyTo", original);
    data.put("references", original);
    data.put("generation", "local_template_no_model");
    return new SecretaryProvider.Page(
        List.of(
            new SecretaryProvider.Item(
                item.id(),
                item.revision(),
                "REPLY_DRAFT",
                subject,
                "ご連絡ありがとうございます。内容を確認し、改めてご連絡いたします。",
                "PREVIEW",
                clock.instant(),
                null,
                false,
                data)),
        "",
        false);
  }

  private static String mailbox(String value) {
    if (value == null
        || value.length() > 500
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid mail address");
    String address = value.strip();
    int a = address.indexOf('<'), b = address.lastIndexOf('>');
    if (a >= 0 && b > a) address = address.substring(a + 1, b);
    if (!address.matches(
        "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"))
      throw new IllegalArgumentException("Use plain mail addresses");
    return address;
  }

  private static String addresses(String value, boolean optional) {
    if ((value == null || value.isBlank()) && optional) return "";
    if (value == null) throw new IllegalArgumentException("Recipient required");
    String[] split = value.split(",", -1);
    if (split.length > 10) throw new IllegalArgumentException("Too many recipients");
    return String.join(
        ", ", java.util.Arrays.stream(split).map(GmailSecretaryProtocol::mailbox).toList());
  }

  private static String messageId(String value, boolean optional) {
    if ((value == null || value.isBlank()) && optional) return "";
    if (value == null || !value.matches("<[A-Za-z0-9.!#$%&'*+/=?^_`{|}~@-]{1,240}>"))
      throw new IllegalArgumentException("Invalid reply Message-ID");
    return value;
  }

  SecretaryProvider.Outcome verify(
      SecretaryConnections.Connection c, String operation, Map<String, String> args, String key) {
    if (!Set.of("MAIL_SEND", "MAIL_DRAFT").contains(operation))
      throw new IllegalArgumentException("Unknown Gmail action");
    boolean draft = operation.equals("MAIL_DRAFT");
    String marker = "<rei." + hash(key).substring(0, 40) + "@local.invalid>";
    var matches =
        wire.call(
            "GET",
            "/gmail/v1/users/me/messages",
            Map.of(
                "q",
                "rfc822msgid:"
                    + marker.substring(1, marker.length() - 1)
                    + (draft ? " in:drafts" : " in:sent"),
                "maxResults",
                "5"),
            null,
            false);
    if (matches.path("messages").size() != 1)
      return new SecretaryProvider.Outcome("UNCERTAIN", "", "", Map.of(), false);
    String id = id(value(matches.path("messages").get(0), "id"));
    var observed =
        wire.call(
            "GET", "/gmail/v1/users/me/messages/" + id, Map.of("format", "full"), null, false);
    var parsed = item(observed, true);
    boolean verified =
        header(observed, "Message-ID").equals(marker)
            && addresses(header(observed, "To"), false).equals(addresses(args.get("to"), false))
            && addresses(header(observed, "Cc"), true).equals(addresses(args.get("cc"), true))
            && decodedSubject(header(observed, "Subject")).equals(args.get("subject"))
            && parsed
                .text()
                .replace("\r\n", "\n")
                .stripTrailing()
                .equals(args.get("body").replace("\r\n", "\n").stripTrailing())
            && (draft ? label(observed, "DRAFT") : label(observed, "SENT"))
            && (args.getOrDefault("threadId", "").isBlank()
                || args.get("threadId").equals(parsed.data().get("threadId")))
            && (args.getOrDefault("inReplyTo", "").isBlank()
                || args.get("inReplyTo").equals(header(observed, "In-Reply-To")));
    return new SecretaryProvider.Outcome(
        verified ? "COMPLETED" : "UNCERTAIN",
        id,
        parsed.revision(),
        Map.of("threadId", parsed.data().get("threadId"), "messageId", marker),
        verified);
  }

  SecretaryProvider.Outcome write(
      SecretaryConnections.Connection connection,
      String operation,
      Map<String, String> args,
      String key) {
    fields(
        args, "to", "cc", "subject", "body", "threadId", "inReplyTo", "references", "trackReply");
    if (!Set.of("MAIL_DRAFT", "MAIL_SEND").contains(operation))
      throw new IllegalArgumentException("Unknown Gmail action");
    String to = addresses(args.get("to"), false), cc = addresses(args.get("cc"), true);
    String subject = args.get("subject"), text = args.get("body");
    if (subject == null
        || subject.isBlank()
        || subject.length() > 500
        || subject.chars().anyMatch(Character::isISOControl)
        || text == null
        || text.length() > 32768) throw new IllegalArgumentException("Invalid mail subject/body");
    String thread = args.getOrDefault("threadId", "");
    if (!thread.isBlank()) id(thread);
    String inReplyTo = messageId(args.get("inReplyTo"), true);
    if (!thread.isBlank() && inReplyTo.isBlank())
      throw new IllegalArgumentException("A threaded reply requires In-Reply-To");
    String references = args.getOrDefault("references", inReplyTo);
    if (!references.isBlank()) {
      var ids = references.split(" +");
      if (ids.length > 20) throw new IllegalArgumentException("Too many references");
      for (String ref : ids) messageId(ref, false);
    }
    var profile = wire.call("GET", "/gmail/v1/users/me/profile", Map.of(), null, false);
    String sender = mailbox(value(profile, "emailAddress"));
    String marker = "<rei." + hash(key).substring(0, 40) + "@local.invalid>";
    String mime =
        "From: "
            + sender
            + "\r\nTo: "
            + to
            + "\r\n"
            + (cc.isEmpty() ? "" : "Cc: " + cc + "\r\n")
            + "Subject: =?UTF-8?B?"
            + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
            + "?=\r\nMessage-ID: "
            + marker
            + "\r\n"
            + (inReplyTo.isEmpty()
                ? ""
                : "In-Reply-To: " + inReplyTo + "\r\nReferences: " + references + "\r\n")
            + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + Base64.getMimeEncoder(76, new byte[] {13, 10})
                .encodeToString(text.getBytes(StandardCharsets.UTF_8))
            + "\r\n";
    var message = new HashMap<String, Object>();
    message.put(
        "raw",
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mime.getBytes(StandardCharsets.UTF_8)));
    if (!thread.isBlank()) message.put("threadId", thread);
    boolean draft = operation.equals("MAIL_DRAFT");
    var sent =
        wire.call(
            "POST",
            draft ? "/gmail/v1/users/me/drafts" : "/gmail/v1/users/me/messages/send",
            Map.of(),
            draft ? Map.of("message", message) : message,
            true);
    String external = id(value(sent, "id"));
    try {
      var observed =
          wire.call(
              "GET",
              (draft ? "/gmail/v1/users/me/drafts/" : "/gmail/v1/users/me/messages/") + external,
              Map.of("format", "full"),
              null,
              false);
      if (draft) observed = observed.path("message");
      var parsed = item(observed, true);
      boolean verified =
          header(observed, "Message-ID").equals(marker)
              && addresses(header(observed, "To"), false).equals(to)
              && addresses(header(observed, "Cc"), true).equals(cc)
              && decodedSubject(header(observed, "Subject")).equals(subject)
              && (thread.isBlank() || thread.equals(parsed.data().get("threadId")))
              && (inReplyTo.isBlank() || inReplyTo.equals(header(observed, "In-Reply-To")))
              && parsed
                  .text()
                  .replace("\r\n", "\n")
                  .stripTrailing()
                  .equals(text.replace("\r\n", "\n").stripTrailing())
              && (draft || label(observed, "SENT"));
      return new SecretaryProvider.Outcome(
          verified ? "COMPLETED" : "UNCERTAIN",
          external,
          parsed.revision(),
          Map.of(
              "threadId",
              parsed.data().get("threadId"),
              "sender",
              sender,
              "messageId",
              marker,
              "bodySha256",
              hash(text)),
          verified);
    } catch (SecretaryProvider.Failure failure) {
      throw new SecretaryProvider.Failure("VERIFICATION_UNAVAILABLE", failure.retrySeconds(), true);
    }
  }
}
