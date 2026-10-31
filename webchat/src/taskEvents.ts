import type { ApprovalRequest, RealtimeEventData } from "./types";

/** Remembers control events only while their HTTP receipt is outstanding. */
export class TaskReceiptTracker {
  private pending = new Map<string, "running" | "waiting" | "terminal">();

  begin(taskId: string) { this.pending.set(taskId, "running"); }
  observe(data: RealtimeEventData) {
    const taskId = String(data.taskId ?? "");
    if (!this.pending.has(taskId)) return;
    if (data.kind === "completed" || data.kind === "error") this.pending.set(taskId, "terminal");
    else if (data.kind === "waiting_approval" && this.pending.get(taskId) !== "terminal") {
      this.pending.set(taskId, "waiting");
    }
  }
  accept(taskId: string): string | null {
    const state = this.pending.get(taskId);
    this.pending.delete(taskId);
    return state === "waiting" || state === "terminal" ? null : taskId;
  }
  discard(taskId: string) { this.pending.delete(taskId); }
}

/** Task completion cannot clear a newer task or an unrelated conversation's controls. */
export function taskEventEffect(data: RealtimeEventData, activeTaskId: string | null, sameConversation: boolean) {
  const taskId = String(data.taskId ?? "");
  const kind = String(data.kind ?? "");
  const terminal = kind === "completed" || kind === "error";
  const waiting = kind === "waiting_approval";
  const matches = sameConversation && taskId !== "" && taskId === activeTaskId;
  return {
    taskId,
    rememberCompletion: terminal && taskId !== "",
    clearActive: matches && (terminal || waiting),
    clearApprovals: matches && terminal,
    showApprovals: sameConversation && waiting,
    approvals: Array.isArray(data.approvals) ? data.approvals as ApprovalRequest[] : null,
  };
}
