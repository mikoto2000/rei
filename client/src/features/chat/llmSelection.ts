import type { LlmEndpoint, LlmModels } from "../../entities/llm";
export interface LlmPort {
  endpoints(server: string): Promise<LlmEndpoint[]>;
  select(server: string, endpoint: string): Promise<LlmEndpoint>;
  models(server: string): Promise<LlmModels>;
  selectModel(server: string, endpoint: string, model: string): Promise<LlmModels>;
}
export interface LlmState {
  endpoints: LlmEndpoint[];
  models: LlmModels | null;
  busy: boolean;
  error: string | null;
}
export class LlmSelection {
  private state: LlmState = { endpoints: [], models: null, busy: false, error: null };
  constructor(private readonly port: LlmPort, readonly server: string) {}
  snapshot = () => this.state;
  async refresh(): Promise<void> {}
  async select(_endpoint: string): Promise<void> {}
  async loadModels(): Promise<void> {}
  async selectModel(_model: string): Promise<void> {}
}
