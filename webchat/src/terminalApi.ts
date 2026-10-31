import { request } from "./api";
import type { WebTerminalInfo } from "./types";

export interface TerminalCreateInput {
  label?: string;
  workingDirectory?: string;
  columns?: number;
  rows?: number;
}

export async function listTerminals(): Promise<WebTerminalInfo[]> {
  const payload = await request<{ terminals?: WebTerminalInfo[] }>("/terminal");
  return Array.isArray(payload?.terminals) ? payload.terminals : [];
}

export async function createTerminal(input: TerminalCreateInput = {}): Promise<WebTerminalInfo> {
  return request<WebTerminalInfo>("/terminal", { method: "POST", body: input });
}

export async function closeTerminal(id: string): Promise<void> {
  await request(`/terminal/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function sendTerminalInput(id: string, data: Uint8Array): Promise<void> {
  await request(`/terminal/${encodeURIComponent(id)}/input`, {
    method: "POST",
    body: { data: bytesToBase64(data) },
  });
}

export async function resizeTerminal(id: string, columns: number, rows: number): Promise<void> {
  await request(`/terminal/${encodeURIComponent(id)}/resize`, {
    method: "POST",
    body: { columns, rows },
  });
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}
