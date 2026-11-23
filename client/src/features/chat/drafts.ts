import type { RunMode } from "../../entities/models";

export type Draft = {
  message: string;
  mode: RunMode | "INTERVENTION";
  targetRun: string;
};

export const emptyDraft: Draft = {
  message: "",
  mode: "EXCLUSIVE",
  targetRun: "",
};
