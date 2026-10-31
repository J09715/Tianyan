package com.termux.terminal;

import java.io.IOException;

/**
 * Termux terminal-emulator fork 新增：终端会话的字节流后端。
 * <p>
 * 本地会话使用 {@link JniTerminalSessionBackend}（等价原 JNI PTY 行为）；远程会话（SSH）可实现本接口，
 * 从而复用 {@link TerminalSession} 与其上的 Termux 渲染层，而无需伪造宿主 argv。
 */
public interface TerminalSessionBackend {

    /** 启动（本地=createSubprocess）。返回 pid；无法获取时返回 -1。 */
    int start(int rows, int columns);

    /** 阻塞读取终端输出；返回字节数，EOF 返回 -1。 */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** 写入用户输入。 */
    void write(byte[] buffer, int offset, int length) throws IOException;

    /** 窗口尺寸变化。 */
    void setSize(int rows, int columns);

    /** 阻塞等待会话结束，返回退出码。 */
    int waitForExit();

    /** 终止会话（语义等价 SIGKILL）。 */
    void kill();

    /** 释放资源；幂等。 */
    void close();
}