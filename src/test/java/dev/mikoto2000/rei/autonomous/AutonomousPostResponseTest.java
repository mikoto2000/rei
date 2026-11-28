package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutonomousPostResponseTest {
  private static final Set<String> SOURCES = Set.of("topic:source:1");

  @Test
  void skipIsANormalResultWithoutADraft() {
    var result =
        AutonomousPostResponse.decode("{\"decision\":\"SKIP\",\"reason\":\"今回は見送ります\"}", Set.of());
    assertThat(result).isEqualTo(new AutonomousPostResponse.Skip("今回は見送ります"));
  }

  @Test
  void proposalContainsOnlyValidatedTextAndActualSourceIdentifiers() throws Exception {
    var result =
        AutonomousPostResponse.decode(
            proposal("公開する価値のある話題です。", List.of("topic:source:1")), SOURCES);
    assertThat(result)
        .isInstanceOfSatisfying(
            AutonomousPostResponse.Proposal.class,
            proposed -> {
              assertThat(proposed.content().text()).isEqualTo("公開する価値のある話題です。");
              assertThat(proposed.content().sourceIds()).containsExactly("topic:source:1");
              assertThat(proposed.content().reason()).isEqualTo("共有したい話題です");
              assertThat(proposed.content().revision()).isEqualTo(1);
            });
  }

  @Test
  void invalidJsonTypesDecisionsAndAmbiguousDocumentsAreRejected() {
    for (String response :
        new String[] {
          null,
          "",
          "[]",
          "null",
          "not json",
          "{}",
          "{\"decision\":true,\"reason\":\"reason\"}",
          "{\"decision\":\"PUBLISH\",\"reason\":\"reason\"}",
          "{\"decision\":\"SKIP\",\"reason\":12}",
          "{\"decision\":\"SKIP\",\"decision\":\"SKIP\",\"reason\":\"reason\"}",
          "{\"decision\":\"SKIP\",\"reason\":\"reason\"} {}",
          "{\"decision\":\"PROPOSE_POST\",\"reason\":\"reason\"}",
          "[".repeat(20) + "0" + "]".repeat(20),
          "x".repeat(16385)
        }) {
      assertThatThrownBy(() -> AutonomousPostResponse.decode(response, SOURCES))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void sourceIdsMustBeNonemptyDistinctTextActuallySuppliedToTheModel() throws Exception {
    for (List<?> ids :
        List.of(
            List.of(),
            List.of("unknown"),
            List.of(12),
            List.of(true),
            List.of("topic:source:1", "topic:source:1"))) {
      String response = proposal("本文", ids);
      assertThatThrownBy(() -> AutonomousPostResponse.decode(response, SOURCES))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void modelCannotSupplyApprovalOrAdditionalPostFeatures() throws Exception {
    var root = StorageDatabase.JSON.readTree(proposal("本文", List.of("topic:source:1")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("approvedBy", "model");
    assertThatThrownBy(() -> AutonomousPostResponse.decode(root.toString(), SOURCES))
        .isInstanceOf(IllegalArgumentException.class);
    for (String field : List.of("facets", "reply", "embed", "revision", "bodyHash")) {
      var extra = StorageDatabase.JSON.readTree(proposal("本文", List.of("topic:source:1")));
      ((com.fasterxml.jackson.databind.node.ObjectNode) extra.path("draft")).putNull(field);
      assertThatThrownBy(() -> AutonomousPostResponse.decode(extra.toString(), SOURCES))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void bodyAndReasonMustBeValidReviewText() throws Exception {
    for (String text :
        new String[] {"", "😀".repeat(301), "\uD800", "hello @alice", "a\u001b[2Jb"}) {
      String response = proposal(text, List.of("topic:source:1"));
      assertThatThrownBy(() -> AutonomousPostResponse.decode(response, SOURCES))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String reason :
        new String[] {null, "", "　", "\u200B", "\uDC00", "a\u001b[2Jb", "x".repeat(2049)}) {
      var root =
          StorageDatabase.JSON.createObjectNode().put("decision", "SKIP").put("reason", reason);
      assertThatThrownBy(() -> AutonomousPostResponse.decode(root.toString(), SOURCES))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void invalidDocumentsAreNotCopiedIntoErrors() {
    assertThatThrownBy(
            () -> AutonomousPostResponse.decode("{\"PRIVATE_OR_UNTRUSTED_MARKER\":", SOURCES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("PRIVATE_OR_UNTRUSTED_MARKER")
        .hasNoCause();
  }

  private static String proposal(String text, List<?> ids) throws Exception {
    var root =
        StorageDatabase.JSON
            .createObjectNode()
            .put("decision", "PROPOSE_POST")
            .put("reason", "共有したい話題です");
    root.putObject("draft")
        .put("text", text)
        .set("sourceIds", StorageDatabase.JSON.valueToTree(ids));
    return root.toString();
  }
}
