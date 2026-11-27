import type { LlmEndpoint, LlmModels } from "../../entities/llm";
export interface LlmPort {
  endpoints(server: string): Promise<LlmEndpoint[]>;
  select(server: string, endpoint: string): Promise<LlmEndpoint>;
  models(server: string): Promise<LlmModels>;
  selectModel(
    server: string,
    endpoint: string,
    model: string,
  ): Promise<LlmModels>;
}
export interface LlmState {
  endpoints: LlmEndpoint[];
  models: LlmModels | null;
  busy: boolean;
  error: string | null;
}
export class LlmSelection {
  private state: LlmState = {
    endpoints: [],
    models: null,
    busy: false,
    error: null,
  };
  private revision = 0;
  private readonly listeners = new Set<() => void>();
  constructor(
    private readonly port: LlmPort,
    readonly server: string,
  ) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(patch: Partial<LlmState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }
  private failure(error: unknown) {
    const known = [
      "EndpointNotFound",
      "Conflict",
      "AuthenticationFailed",
      "PermissionDenied",
      "ServerUnreachable",
      "RequestTimeout",
      "RateLimited",
      "VaultLocked",
    ];
    return typeof error === "string" && known.includes(error)
      ? error
      : "InvalidResponse";
  }
  async refresh(): Promise<void> {
    if (this.state.busy) return;
    const revision = ++this.revision;
    try {
      const endpoints = await this.port.endpoints(this.server);
      if (
        !Array.isArray(endpoints) ||
        endpoints.length === 0 ||
        endpoints.filter((e) => e?.active).length !== 1 ||
        endpoints.some((e) => !e?.profile?.id || !Array.isArray(e.diagnostics))
      )
        throw "InvalidResponse";
      if (revision === this.revision)
        this.update({ endpoints, models: null, error: null });
    } catch (error) {
      if (revision === this.revision)
        this.update({ error: this.failure(error), models: null });
    }
  }
  async select(endpoint: string): Promise<void> {
    if (this.state.busy) return;
    const revision = ++this.revision;
    this.update({ busy: true, models: null, error: null });
    try {
      const selected = await this.port.select(this.server, endpoint);
      if (selected.profile.id !== endpoint || !selected.active)
        throw "Conflict";
      const endpoints = this.state.endpoints
        .filter((e) => e.profile.id !== endpoint)
        .map((e) => ({ ...e, active: false }));
      endpoints.push(selected);
      if (revision === this.revision) this.update({ endpoints });
    } catch (error) {
      if (revision === this.revision)
        this.update({ error: this.failure(error) });
    } finally {
      if (revision === this.revision) this.update({ busy: false });
    }
  }
  async loadModels(): Promise<void> {
    if (this.state.busy) return;
    const endpoint = this.state.endpoints.find((e) => e.active)?.profile.id;
    if (!endpoint) return;
    const revision = ++this.revision;
    this.update({ models: null, error: null });
    try {
      const models = await this.port.models(this.server);
      if (revision !== this.revision) return;
      if (models.endpoint !== endpoint) throw "Conflict";
      this.update({ models });
    } catch (error) {
      if (revision === this.revision)
        this.update({ error: this.failure(error) });
    }
  }
  async selectModel(model: string): Promise<void> {
    if (this.state.busy) return;
    const catalog = this.state.models;
    if (!catalog?.models.includes(model)) return;
    const revision = ++this.revision;
    this.update({ busy: true, error: null });
    try {
      const selected = await this.port.selectModel(
        this.server,
        catalog.endpoint,
        model,
      );
      if (
        selected.endpoint !== catalog.endpoint ||
        selected.currentModel !== model
      )
        throw "Conflict";
      const endpoints = await this.port.endpoints(this.server);
      if (endpoints.find((e) => e.active)?.profile.id !== catalog.endpoint)
        throw "Conflict";
      if (revision === this.revision)
        this.update({ endpoints, models: { ...catalog, currentModel: model } });
    } catch (error) {
      if (revision === this.revision)
        this.update({ models: null, error: this.failure(error) });
    } finally {
      if (revision === this.revision) this.update({ busy: false });
    }
  }
}
