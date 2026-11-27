export type Support = "SUPPORTED" | "UNSUPPORTED" | "UNKNOWN";
export interface LlmEndpoint {
  profile: {
    id: string;
    model: string | null;
    provider: string;
    api: string;
    authentication: string;
    accountRef: string | null;
    usagePath: string | null;
    capabilities: {
      route: Record<string, Support>;
      server: Record<string, Support>;
      model: Record<string, Support>;
      effective: Record<string, Support>;
    };
  };
  active: boolean;
  reasoningReplay: string | null;
  authenticationState: string;
  unavailableReason: string | null;
  generationSettings: Record<string, unknown>;
  unappliedSettings: Record<string, string>;
  diagnostics: string[];
  selectionScope: string;
}
export interface LlmModels {
  endpoint: string;
  currentModel: string | null;
  models: string[];
}
