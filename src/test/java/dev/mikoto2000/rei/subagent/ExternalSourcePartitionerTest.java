package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalSourcePartitionerTest {
  @Test
  void recursivelyBisectsInOrderAndLeavesAcceptedGroupsTogether() {
    var result =
        ExternalSourcePartitioner.split(List.of(1, 2, 3, 4, 5, 6, 7), list -> list.size() <= 2);
    assertThat(result).containsExactly(List.of(1), List.of(2, 3), List.of(4, 5), List.of(6, 7));
    assertThatThrownBy(() -> result.add(List.of(8)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void acceptsAnUnsplitOrEmptyInputWithoutDuplicatingIt() {
    assertThat(ExternalSourcePartitioner.split(List.of(1, 2), list -> true))
        .containsExactly(List.of(1, 2));
    assertThat(ExternalSourcePartitioner.split(List.of(), list -> true)).containsExactly(List.of());
  }

  @Test
  void unsplittableLeafRefusesRatherThanDroppingIt() {
    assertThatThrownBy(
            () -> ExternalSourcePartitioner.split(List.of(1, 9, 2), list -> !list.contains(9)))
        .isInstanceOf(ChatGptException.class)
        .hasMessageContaining("UNSPLITTABLE_INPUT");
  }
}
