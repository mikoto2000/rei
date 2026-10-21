package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 外部取得元Partitionerの結果と状態の契約を検証するテスト。 */
class ExternalSourcePartitionerTest {
  /** 補助エージェントへの委譲で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void recursivelyBisectsInOrderAndLeavesAcceptedGroupsTogether() {
    var result =
        ExternalSourcePartitioner.split(List.of(1, 2, 3, 4, 5, 6, 7), list -> list.size() <= 2);
    assertThat(result).containsExactly(List.of(1), List.of(2, 3), List.of(4, 5), List.of(6, 7));
    assertThatThrownBy(() -> result.add(List.of(8)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acceptsAnUnsplitOrEmptyInputWithoutDuplicatingIt() {
    assertThat(ExternalSourcePartitioner.split(List.of(1, 2), list -> true))
        .containsExactly(List.of(1, 2));
    assertThat(ExternalSourcePartitioner.split(List.of(), list -> true)).containsExactly(List.of());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unsplittableLeafRefusesRatherThanDroppingIt() {
    assertThatThrownBy(
            () -> ExternalSourcePartitioner.split(List.of(1, 9, 2), list -> !list.contains(9)))
        .isInstanceOf(ChatGptException.class)
        .hasMessageContaining("UNSPLITTABLE_INPUT");
  }
}
