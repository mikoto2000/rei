import type { ConversationTurn, Run } from "../../entities/models";
export function timeline(
  turns: ConversationTurn[],
  runs: Run[],
): { turn?: ConversationTurn; run?: Run }[] {
  const live = new Map(runs.map((run) => [run.runId, run]));
  const rows = turns.map((turn) => {
    const run = live.get(turn.runId);
    live.delete(turn.runId);
    return { turn, run };
  });
  return [...rows, ...Array.from(live.values(), (run) => ({ run }))];
}
