import type { LlmPort } from "./llmSelection";
export interface LlmControlsProps {
  serverId: string;
  port?: LlmPort;
  disabled?: boolean;
  onBusy?: (busy: boolean) => void;
}
export function LlmControls(_props: LlmControlsProps) {
  return null;
}
