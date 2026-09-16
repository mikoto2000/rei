import { invoke } from "@tauri-apps/api/core";
import type {
  Snapshot,
  Server,
  Project,
  Conversation,
  Run,
  Connection,
  SessionSummary,
  SessionPage,
  TurnPage,
} from "../entities/models";
type RunArgs = { serverId: string; runId: string };
interface Commands {
  app_snapshot: [undefined, Snapshot];
  vault_unlock: [{ password: string }, void];
  server_list: [undefined, Server[]];
  server_save: [{ id: string | null; name: string; baseUrl: string }, string];
  server_select: [{ serverId: string }, void];
  server_remove: [{ serverId: string }, void];
  server_test: [{ serverId: string }, Connection];
  credential_set: [{ serverId: string; credential: string }, void];
  credential_delete: [{ serverId: string }, void];
  projects_list: [{ serverId: string }, Project[]];
  session_list: [
    {
      serverId: string;
      projectId?: string | null;
      limit?: number;
      cursor?: string | null;
    },
    SessionPage,
  ];
  session_get: [{ serverId: string; sessionId: string }, SessionSummary];
  session_turns: [
    {
      serverId: string;
      sessionId: string;
      limit?: number;
      cursor?: string | null;
    },
    TurnPage,
  ];
  session_open: [{ serverId: string; sessionId: string }, Conversation];
  conversation_list: [undefined, Conversation[]];
  conversation_create: [
    { serverId: string; projectId: string; title: string },
    Conversation,
  ];
  conversation_continue_new: [{ conversationId: string }, Conversation];
  conversation_delete: [{ conversationId: string }, void];
  conversation_select: [{ conversationId: string }, void];
  chat_submit: [{ conversationId: string; message: string }, Run];
  run_get: [RunArgs, Run];
  run_cancel: [RunArgs, void];
  run_subscribe: [RunArgs, void];
  run_unsubscribe: [RunArgs, void];
  run_list_active: [undefined, Run[]];
  notification_settings: [{ enabled: boolean }, boolean];
}
export type Command = <K extends keyof Commands>(
  name: K,
  args: Commands[K][0],
) => Promise<Commands[K][1]>;
export const command: Command = (name, args) => invoke(name, args);
