const API_ROOT = "/webchat/api";
let authToken = "";

export function setAuthToken(token: string): void {
  authToken = token.trim();
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, query } = options;
  const url = new URL(`${API_ROOT}${path}`, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const response = await fetch(url, {
    method,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const responseText = await response.text();
  let payload: unknown = null;
  if (responseText) {
    try {
      payload = JSON.parse(responseText);
    } catch {
      payload = responseText;
    }
  }
  if (!response.ok) {
    const record = isRecord(payload) ? payload : null;
    const message = record?.error ?? record?.message ?? payload;
    throw new Error(String(message || `请求失败 (${response.status})`));
  }
  return payload as T;
}

export function workspaceDownloadUrl(path: string): string {
  return `${API_ROOT}/workspaces/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(authToken)}`;
}

export function eventsUrl(): string {
  return `${API_ROOT}/events?token=${encodeURIComponent(authToken)}`;
}

export function terminalStreamUrl(terminalId: string): string {
  return `${API_ROOT}/terminal/${encodeURIComponent(terminalId)}/stream?token=${encodeURIComponent(authToken)}`;
}

export function authorizationHeader(): Record<string, string> {
  return authToken ? { Authorization: `Bearer ${authToken}` } : {};
}

/** 二进制上传工作区文件（body 为原始字节，大小上限由服务端把守）。 */
export async function uploadWorkspaceFile(targetPath: string, file: File): Promise<void> {
  const url = `${API_ROOT}/workspaces/upload?path=${encodeURIComponent(targetPath)}`;
  const response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: { ...authorizationHeader(), "Content-Type": "application/octet-stream" },
    body: file,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `上传失败 (${response.status})`);
  }
}

export interface WorkspaceItemActionInput {
  path: string;
  action: "create_file" | "create_dir" | "delete" | "rename";
  newName?: string;
}

export function workspaceItemAction(input: WorkspaceItemActionInput): Promise<unknown> {
  return request("/workspaces/item", { method: "POST", body: input });
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
