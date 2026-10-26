import { workspaceDownloadUrl } from "../api";
import { formatBytes } from "../format";
import type { WorkspaceItem } from "../types";
import { Icon } from "./Icon";

interface ContextPaneProps {
  workspacePath: string;
  workspaceItems: WorkspaceItem[];
  workspaceFilePath: string | null;
  workspaceContent: string;
  workspaceDirty: boolean;
  onOpenConversations: () => void;
  onWorkspacePath: () => void;
  onWorkspaceItem: (item: WorkspaceItem) => void;
  onWorkspaceRefresh: () => void;
  onWorkspaceContent: (content: string) => void;
  onWorkspaceSave: () => void;
  onWorkspaceUpload: (files: FileList) => void;
  onWorkspaceCreate: (kind: "file" | "dir") => void;
  onWorkspaceRename: (item: WorkspaceItem) => void;
  onWorkspaceDelete: (item: WorkspaceItem) => void;
}

export function ContextPane({
  workspacePath,
  workspaceItems,
  workspaceFilePath,
  workspaceContent,
  workspaceDirty,
  onOpenConversations,
  onWorkspacePath,
  onWorkspaceItem,
  onWorkspaceRefresh,
  onWorkspaceContent,
  onWorkspaceSave,
  onWorkspaceUpload,
  onWorkspaceCreate,
  onWorkspaceRename,
  onWorkspaceDelete,
}: ContextPaneProps) {
  return (
    <aside className="context-pane">
      <div className="mobile-context-header">
        <button className="appbar-icon" type="button" aria-label="打开对话列表" onClick={onOpenConversations}>
          <Icon name="menu" size={20} />
        </button>
        <strong>Linux 工作区</strong>
        <span />
      </div>

      <section id="workspace-panel" className="context-panel active">
        <header className="context-header">
          <div>
            <strong>Linux 工作区</strong>
            <button className="path-button" type="button" title={workspacePath} onClick={onWorkspacePath}>
              {workspacePath || "/workspace"}
            </button>
          </div>
          <div className="header-actions">
            <label className="quiet-button" title="上传文件到当前目录">
              <Icon name="upload" size={14} /><span>上传</span>
              <input
                type="file"
                multiple
                hidden
                onChange={(event) => {
                  if (event.target.files?.length) onWorkspaceUpload(event.target.files);
                  event.target.value = "";
                }}
              />
            </label>
            <button className="quiet-button" type="button" title="新建文件" onClick={() => onWorkspaceCreate("file")}>
              <Icon name="file" size={14} />
            </button>
            <button className="quiet-button" type="button" title="新建文件夹" onClick={() => onWorkspaceCreate("dir")}>
              <Icon name="folder" size={14} />
            </button>
            {workspaceFilePath && (
              <a className="quiet-link" href={workspaceDownloadUrl(workspaceFilePath)} title="下载文件">
                <Icon name="download" size={15} /><span>下载</span>
              </a>
            )}
            <button className="quiet-button" type="button" onClick={onWorkspaceRefresh}>
              <Icon name="refresh" size={14} /><span>刷新</span>
            </button>
            <button
              className="primary-small-button"
              type="button"
              disabled={!workspaceDirty || !workspaceFilePath}
              onClick={onWorkspaceSave}
            ><Icon name="save" size={14} /><span>保存</span></button>
          </div>
        </header>
        <div className="workspace-layout">
          <div className="workspace-list">
            {!workspaceItems.length && <div className="list-empty">当前工作区为空</div>}
            {workspaceItems.map((item) => (
              <div className={`workspace-item-row${item.path === workspaceFilePath ? " active" : ""}`} key={item.path}>
                <button className="workspace-item" type="button" onClick={() => onWorkspaceItem(item)}>
                  <Icon name={item.isDirectory ? "folder" : "file"} size={15} />
                  <span>{item.name}</span>
                  <small>{item.isDirectory ? "" : formatBytes(item.size)}</small>
                </button>
                <button className="item-action" type="button" title="重命名" onClick={() => onWorkspaceRename(item)}>
                  <Icon name="pencil" size={13} />
                </button>
                <button className="item-action danger" type="button" title="删除" onClick={() => onWorkspaceDelete(item)}>
                  <Icon name="trash" size={13} />
                </button>
              </div>
            ))}
          </div>
          <div className="workspace-editor-wrap">
            <p>{workspaceFilePath || "选择文件以查看或编辑"}</p>
            <textarea
              id="workspace-editor"
              spellCheck={false}
              disabled={!workspaceFilePath}
              value={workspaceContent}
              onChange={(event) => onWorkspaceContent(event.target.value)}
            />
          </div>
        </div>
      </section>
    </aside>
  );
}
