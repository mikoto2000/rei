package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;

/**
 * Context Budget の割り当て結果。
 *
 * @param included 採用されたセクション名
 * @param dropped  削除されたセクション名
 * @param totalTokens 採用された合計トークン概算
 */
public record AllocationResult(List<String> included, List<String> dropped, int totalTokens) {
}
