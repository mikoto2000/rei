import type { RunMode, RunStatus, StreamState } from "../entities/models";
export const runStatusLabel = (value: RunStatus) =>
  ({
    QUEUED: "順番待ち",
    RUNNING: "作業中",
    WAITING: "回答待ち",
    WAITING_APPROVAL: "承認待ち",
    COMPLETED: "完了",
    FAILED: "失敗",
    CANCELLED: "停止済み",
    UNKNOWN: "結果不明",
  })[value];
export const modeLabel = (value: RunMode) =>
  ({ EXCLUSIVE: "作業", CONVERSATION: "相談", READ_ONLY: "読み取り" })[value];
export const streamLabel = (value: StreamState) =>
  ({
    CONNECTING: "接続中",
    CONNECTED: "受信中",
    RECONNECTING: "再接続中",
    CLOSED: "受信停止",
  })[value];
