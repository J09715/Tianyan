import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useCallback, useEffect, useRef, useState } from "react";
import { terminalStreamUrl } from "../api";
import {
  closeTerminal,
  createTerminal,
  listTerminals,
  resizeTerminal,
  sendTerminalInput,
} from "../terminalApi";
import type { WebTerminalInfo } from "../types";
import { Icon } from "./Icon";

type LinkState = "offline" | "connecting" | "online" | "closed";

const TERMINAL_THEME = {
  background: "#0d1117",
  foreground: "#c9d1d9",
  cursor: "#58a6ff",
  cursorAccent: "#0d1117",
  selectionBackground: "#264f78",
  black: "#484f58",
  red: "#ff7b72",
  green: "#3fb950",
  yellow: "#d29922",
  blue: "#58a6ff",
  magenta: "#bc8cff",
  cyan: "#39c5cf",
  white: "#b1bac4",
  brightBlack: "#6e7681",
  brightRed: "#ffa198",
  brightGreen: "#56d364",
  brightYellow: "#e3b341",
  brightBlue: "#79c0ff",
  brightMagenta: "#d2a8ff",
  brightCyan: "#56d4dd",
  brightWhite: "#f0f6fc",
};

function base64ToBytes(encoded: string): Uint8Array {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error ?? "终端操作失败");
}

interface TerminalPaneProps {
  /** 面板是否可见（桌面切换 / 移动端分区）。不可见时暂停 fit 但保持会话。 */
  active: boolean;
}

export function TerminalPane({ active }: TerminalPaneProps) {
  const [terminals, setTerminals] = useState<WebTerminalInfo[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [link, setLink] = useState<LinkState>("offline");
  const [notice, setNotice] = useState("");
  const containerRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const sourceRef = useRef<EventSource | null>(null);
  const activeIdRef = useRef<string | null>(null);
  const resizeTimerRef = useRef<number | null>(null);

  // xterm 实例只建一次；会话切换靠 term.reset() 清屏。
  useEffect(() => {
    const term = new Terminal({
      fontFamily: '"Cascadia Code", "JetBrains Mono", Consolas, "Courier New", monospace',
      fontSize: 13,
      lineHeight: 1.2,
      cursorBlink: true,
      scrollback: 5000,
      theme: TERMINAL_THEME,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    termRef.current = term;
    fitRef.current = fit;
    if (containerRef.current) term.open(containerRef.current);
    const inputDisposable = term.onData((data) => {
      const id = activeIdRef.current;
      if (id) void sendTerminalInput(id, new TextEncoder().encode(data)).catch(() => undefined);
    });
    return () => {
      inputDisposable.dispose();
      sourceRef.current?.close();
      sourceRef.current = null;
      term.dispose();
      termRef.current = null;
      fitRef.current = null;
    };
  }, []);

  // 会话切换：重开 SSE 流。浏览器断开（标签页隐藏/网络闪断）由 EventSource 自动重连。
  useEffect(() => {
    activeIdRef.current = activeId;
    const term = termRef.current;
    if (!term) return;
    sourceRef.current?.close();
    sourceRef.current = null;
    term.reset();
    if (!activeId) {
      setLink("offline");
      return;
    }
    setLink("connecting");
    setNotice("");
    const source = new EventSource(terminalStreamUrl(activeId));
    sourceRef.current = source;
    source.addEventListener("hello", (event) => {
      setLink("online");
      setNotice("");
      try {
        const info = JSON.parse((event as MessageEvent).data) as WebTerminalInfo;
        term.resize(info.columns, info.rows);
      } catch {
        // 元信息解析失败不影响输出流
      }
    });
    source.addEventListener("output", (event) => {
      const payload = (event as MessageEvent).data as string;
      term.write(base64ToBytes(payload));
    });
    source.addEventListener("exit", () => {
      setLink("closed");
      setNotice("会话已结束（shell 退出或服务端回收）");
    });
    source.onerror = () => {
      setLink(source.readyState === EventSource.CLOSED ? "offline" : "connecting");
    };
    return () => {
      source.close();
      if (sourceRef.current === source) sourceRef.current = null;
    };
  }, [activeId]);

  const fitTerminal = useCallback(() => {
    const term = termRef.current;
    const fit = fitRef.current;
    const id = activeIdRef.current;
    if (!term || !fit || !id) return;
    try {
      fit.fit();
      const { cols, rows } = term;
      if (resizeTimerRef.current !== null) window.clearTimeout(resizeTimerRef.current);
      // 防抖上报 resize，避免拖拽窗口时请求风暴。
      resizeTimerRef.current = window.setTimeout(() => {
        void resizeTerminal(id, cols, rows).catch(() => undefined);
      }, 150);
    } catch {
      // 容器不可见（display:none）时 fit 会抛错，忽略即可。
    }
  }, []);

  useEffect(() => {
    if (!active) return;
    fitTerminal();
    const observer = new ResizeObserver(() => fitTerminal());
    if (containerRef.current) observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [active, fitTerminal]);

  const refresh = useCallback(async () => {
    try {
      const list = await listTerminals();
      setTerminals(list);
      setActiveId((current) =>
        current && list.some((item) => item.id === current)
          ? current
          : list[0]?.id ?? null,
      );
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }, []);

  useEffect(() => {
    if (active) void refresh();
  }, [active, refresh]);

  async function handleCreate() {
    try {
      const term = termRef.current;
      const created = await createTerminal({
        columns: term?.cols ?? 80,
        rows: term?.rows ?? 24,
      });
      setTerminals((current) => [...current, created]);
      setActiveId(created.id);
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }

  async function handleClose(id: string) {
    try {
      await closeTerminal(id);
      const next = terminals.filter((item) => item.id !== id);
      setTerminals(next);
      if (activeId === id) setActiveId(next[0]?.id ?? null);
    } catch (error) {
      setNotice(errorMessage(error));
    }
  }

  const linkLabel: Record<LinkState, string> = {
    offline: "未连接",
    connecting: "连接中",
    online: "已连接",
    closed: "已结束",
  };

  return (
    <section className={`terminal-pane${active ? "" : " hidden"}`} aria-label="远程终端">
      <header className="terminal-toolbar">
        <div className="terminal-tabs">
          {terminals.map((item) => (
            <button
              className={`terminal-tab${item.id === activeId ? " active" : ""}`}
              key={item.id}
              title={item.workingDirectory}
              type="button"
              onClick={() => setActiveId(item.id)}
            >
              <Icon name="terminal" size={13} />
              <span>{item.label}</span>
              {!item.alive && <em className="terminal-dead">已退出</em>}
            </button>
          ))}
        </div>
        <div className="terminal-actions">
          <button className="quiet-button" type="button" onClick={() => void handleCreate()}>
            <Icon name="plus" size={14} /><span>新建</span>
          </button>
          {activeId && (
            <button className="quiet-button danger" type="button" onClick={() => void handleClose(activeId)}>
              <Icon name="x" size={14} /><span>关闭</span>
            </button>
          )}
          <span className={`terminal-link link-${link}`}>{linkLabel[link]}</span>
        </div>
      </header>
      {notice && <div className="terminal-notice" role="status">{notice}</div>}
      <div className="terminal-host" ref={containerRef} />
      {!terminals.length && (
        <div className="terminal-empty">
          <p>还没有远程终端会话</p>
          <button className="primary-small-button" type="button" onClick={() => void handleCreate()}>
            <Icon name="plus" size={14} /><span>启动第一个终端</span>
          </button>
        </div>
      )}
    </section>
  );
}
