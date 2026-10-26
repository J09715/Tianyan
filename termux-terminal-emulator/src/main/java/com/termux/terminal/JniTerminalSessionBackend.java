package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/**
 * 默认 {@link TerminalSessionBackend}：由随包 {@code libtermux.so} 的 JNI PTY 驱动。
 * <p>
 * 行为与上游等价：读取侧一个 {@link FileInputStream}，写入侧一个 {@link FileOutputStream}，
 * 各自惰性创建且只创建一次（分别由 InputReader / OutputWriter 线程独占持有）。
 */
final class JniTerminalSessionBackend implements TerminalSessionBackend {

    private static final String LOG_TAG = "TerminalSession";

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final TerminalSessionClient mClient;

    private int mFd = -1;
    private int mPid = -1;
    private FileDescriptor mFileDescriptor;

    private InputStream mInput;
    private OutputStream mOutput;

    JniTerminalSessionBackend(String shellPath, String cwd, String[] args, String[] env, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mClient = client;
    }

    @Override
    public int start(int rows, int columns) {
        int[] processId = new int[1];
        mFd = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns);
        mPid = processId[0];
        mFileDescriptor = wrapFileDescriptor(mFd, mClient);
        return mPid;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        InputStream input = mInput;
        if (input == null) {
            input = mInput = new FileInputStream(mFileDescriptor);
        }
        return input.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        OutputStream output = mOutput;
        if (output == null) {
            output = mOutput = new FileOutputStream(mFileDescriptor);
        }
        output.write(buffer, offset, length);
        output.flush();
    }

    @Override
    public void setSize(int rows, int columns) {
        if (mFd != -1) JNI.setPtyWindowSize(mFd, rows, columns);
    }

    @Override
    public int waitForExit() {
        return JNI.waitFor(mPid);
    }

    @Override
    public void kill() {
        if (mPid > 0) {
            try {
                Os.kill(mPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                mClient.logWarn(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (mFd != -1) {
            JNI.close(mFd);
            mFd = -1;
        }
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            client.logStackTraceWithMessage(LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }
}