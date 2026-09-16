import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { Run, Connection } from "../entities/models";
export interface Events {
  runs: (callback: (run: Run) => void) => Promise<UnlistenFn>;
  connection: (callback: (state: Connection) => void) => Promise<UnlistenFn>;
}
export const events: Events = {
  runs: (callback) =>
    listen<Run>("rei://run-state", (e) => callback(e.payload)),
  connection: (callback) =>
    listen<Connection>("rei://connection-state", (e) => callback(e.payload)),
};
