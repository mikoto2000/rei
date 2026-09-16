import type { Conversation } from "../../entities/models";
import type { Command } from "../../tauri/commands";
interface SelectionState {
  conversation: Conversation | null;
  loading: boolean;
  error: string | null;
}
export class SessionSelection {
  private state: SelectionState = {
    conversation: null,
    loading: false,
    error: null,
  };
  private generation = 0;
  private listeners = new Set<() => void>();
  constructor(private call: Command) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private publish(state: SelectionState) {
    this.state = state;
    this.listeners.forEach((listener) => listener());
  }
  clear() {
    this.generation++;
    this.publish({ conversation: null, loading: false, error: null });
  }
  async open(
    serverId: string,
    sessionId: string,
  ): Promise<Conversation | null> {
    const generation = ++this.generation;
    this.publish({ conversation: null, loading: true, error: null });
    try {
      const conversation = await this.call("session_open", {
        serverId,
        sessionId,
      });
      if (generation !== this.generation) return null;
      this.publish({ conversation, loading: false, error: null });
      return conversation;
    } catch (error) {
      if (generation === this.generation)
        this.publish({
          conversation: null,
          loading: false,
          error: typeof error === "string" ? error : "UnexpectedServerError",
        });
      return null;
    }
  }
}
