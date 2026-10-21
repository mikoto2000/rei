package dev.mikoto2000.rei.memory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.memory.command.MemoryCommand;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.mockito.Mockito;
import picocli.CommandLine;

/** 自動実行条件Propertyの結果と状態の契約を検証するテスト。 */
class AutoTriggerPropertyTest {

  // Feature: ai-memory-consolidation, Property 14: 自動トリガー時の非保存
  /** 長期記憶の保存と検索で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Property(tries = 20)
  void consolidateWithoutApproveNeverSaves(@ForAll boolean ignored) {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    when(consolidator.extractCandidates())
        .thenReturn(
            List.of(
                new Memory(
                    "1",
                    "candidate",
                    MemoryType.KNOWLEDGE,
                    MemoryScope.SHORT_TERM,
                    MemoryStatus.CANDIDATE,
                    0.8d,
                    null,
                    null,
                    null)));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any()))
        .thenReturn(
            new MemoryConflictResolver.ConflictResult(
                MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    new CommandLine(
            new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver))
        .execute();

    verify(memoryService, never()).save(any());
  }
}
