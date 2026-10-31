/*
 * Real PTY backend for the Tianyan terminal.
 *
 * Termux-style forkpty semantics: the app creates a master/slave pair and
 * execs the command with the slave as its controlling terminal, so job
 * control, Ctrl+C, SIGWINCH resize and raw mode behave exactly like a real
 * terminal. The app keeps the master fd and drives it through JNI.
 */
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/syscall.h>

/* 前置声明：strings_array 的失败清理路径会用到这两个辅助函数，
 * 它们定义在文件后半部分，需先声明以满足 C99 的“先声明后使用”。 */
static void free_strings(char **array);
static void throw_io(JNIEnv *env, const char *what);

/*
 * 关闭 [first, max] 范围内的全部 fd。
 *
 * fork() 会复制宿主 App 进程的全部文件描述符：SQLite 数据库、DataStore（含
 * API key 密文）、下载临时文件、网络套接字都会被 exec 后的 shell 继承——
 * PRoot 沙箱内以 root 运行的进程可以直接通过 /proc/self/fd/N 读写宿主
 * 私有数据，同时泄漏的 fd 也会逐渐耗尽进程配额。优先用 close_range
 * （Linux 5.9+）原子收口，老内核回退为逐个 close。
 */
static void close_from(int first) {
#if defined(SYS_close_range)
    if (syscall(SYS_close_range, (unsigned)first, ~0U, 0) == 0) return;
#endif
    long max_fd = sysconf(_SC_OPEN_MAX);
    int limit = (max_fd > 0 && max_fd <= (1 << 20)) ? (int)max_fd : 1024;
    for (int fd = first; fd < limit; fd++) close(fd);
}

static char **strings_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    int n = (*env)->GetArrayLength(env, array);
    /* 防御异常输入：数组长度理论上非负，但这里做上界保护，
     * 避免调用方传入超大数组导致 calloc 巨量分配。 */
    if (n < 0 || n > 100000) {
        throw_io(env, "Invalid array length");
        return NULL;
    }
    char **result = calloc((size_t)n + 1, sizeof(char *));
    if (result == NULL) return NULL;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (s == NULL) continue;
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        if (cs == NULL) {
            /* GetStringUTFChars 失败会挂起 OOM 异常；必须释放已分配部分再返回，
             * 否则调用方拿到 NULL 后既泄漏内存又留下未处理的 pending exception。 */
            free_strings(result);
            (*env)->DeleteLocalRef(env, s);
            return NULL;
        }
        result[i] = strdup(cs);
        if (result[i] == NULL) {
            (*env)->ReleaseStringUTFChars(env, s, cs);
            free_strings(result);
            (*env)->DeleteLocalRef(env, s);
            return NULL;
        }
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    return result;
}

static void free_strings(char **array) {
    if (array == NULL) return;
    for (int i = 0; array[i] != NULL; i++) free(array[i]);
    free(array);
}

static void throw_io(JNIEnv *env, const char *what) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, what);
}

/*
 * argv/envp/cwd/columns/rows -> int[]{masterFd, childPid}
 * The child becomes a session leader with the slave as controlling terminal.
 */
JNIEXPORT jintArray JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_openAndExec(
    JNIEnv *env, jclass clazz,
    jobjectArray argv, jobjectArray envp, jstring cwd,
    jint columns, jint rows) {
    int master = -1, slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) == -1) {
        throw_io(env, "openpty failed");
        return NULL;
    }
    /* Only the master is nonblocking; the child keeps normal terminal semantics. */
    int flags = fcntl(master, F_GETFL);
    if (flags < 0 || fcntl(master, F_SETFL, flags | O_NONBLOCK) < 0 ||
        fcntl(master, F_SETFD, FD_CLOEXEC) < 0) {
        close(master);
        close(slave);
        throw_io(env, "Failed to configure PTY master");
        return NULL;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ioctl(master, TIOCSWINSZ, &ws);

    char **cargv = strings_array(env, argv);
    char **cenvp = strings_array(env, envp);
    const char *ccwd = cwd != NULL ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    /* strings_array / GetStringUTFChars 失败时会挂起异常并返回 NULL。
     * 必须在 fork 前处理：否则子进程会继承未定义状态，且本函数会在
     * pending exception 下继续返回 jintArray，属未定义行为。 */
    if (cargv == NULL || cenvp == NULL || (cwd != NULL && ccwd == NULL)) {
        close(master);
        close(slave);
        free_strings(cargv);
        free_strings(cenvp);
        if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        /* 异常已由被调用方挂起时无需重复抛出 */
        if (!(*env)->ExceptionCheck(env)) {
            throw_io(env, "Failed to build exec arguments");
        }
        return NULL;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        close(slave);
        free_strings(cargv);
        free_strings(cenvp);
        if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        throw_io(env, "fork failed");
        return NULL;
    }

    if (pid == 0) {
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        if (master >= 0) close(master);
        /* stdio 已接管 slave，exec 前关闭全部继承 fd（见 close_from 注释） */
        close_from(3);
        if (ccwd != NULL) chdir(ccwd);
        if (cargv != NULL && cargv[0] != NULL) {
            execve(cargv[0], cargv, cenvp);
        }
        _exit(127);
    }

    close(slave);
    if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    free_strings(cargv);
    free_strings(cenvp);

    jint pair[2] = { (jint)master, (jint)pid };
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 2, pair);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        /* Allocation failed after fork: no Java owner exists to release these. */
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        close(master);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        return NULL;
    }
    return result;
}

/* Reads up to buffer.length bytes from the master; returns bytes read or -1. */
JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_readFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer) {
    if (buffer == NULL) return -1;
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len <= 0) return 0;
    struct pollfd ready = { .fd = fd, .events = POLLIN };
    int polled = poll(&ready, 1, 100);
    if (polled == 0 || (polled < 0 && errno == EINTR)) return 0;
    if (polled < 0) { throw_io(env, "PTY read poll failed"); return -1; }
    jbyte *tmp = (jbyte *)malloc((size_t)len);
    if (tmp == NULL) { throw_io(env, "PTY read allocation failed"); return -1; }
    ssize_t n = read((int)fd, tmp, (size_t)len);
    int read_error = errno;
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, tmp);
    }
    free(tmp);
    if (n == 0 || (n < 0 && read_error == EIO)) return -1;
    if (n < 0 && (read_error == EAGAIN || read_error == EINTR)) return 0;
    if (n < 0) throw_io(env, "PTY read failed");
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_writeFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (buffer == NULL) return -1;
    if (length <= 0) return 0;
    /* 边界校验：offset/length 由 Java 侧传入，越界会让 GetByteArrayRegion
     * 触发 ArrayIndexOutOfBoundsException；此处显式拒绝并返回 -1，
     * 由调用方按「写入失败」处理，语义更清晰。
     * 注意：刻意不设固定长度上限——调用方（NativePtySession.write）按
     * data.size - offset 循环分片写入，若在此截断会丢失大段粘贴内容。 */
    jsize bufLen = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || offset > bufLen || length > bufLen - offset) {
        return -1;
    }
    struct pollfd ready = { .fd = fd, .events = POLLOUT };
    int polled = poll(&ready, 1, 100);
    if (polled == 0 || (polled < 0 && errno == EINTR)) return 0;
    if (polled < 0) { throw_io(env, "PTY write poll failed"); return -1; }
    jbyte *tmp = (jbyte *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, buffer, offset, length, tmp);
    ssize_t n = write((int)fd, tmp, (size_t)length);
    int write_error = errno;
    free(tmp);
    if (n < 0 && (write_error == EAGAIN || write_error == EINTR)) return 0;
    if (n < 0) throw_io(env, "PTY write failed");
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_resizeFd(
    JNIEnv *env, jclass clazz, jint fd, jint columns, jint rows) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    return ioctl((int)fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

/*
 * The child called setsid(), so -pid addresses its whole session group.
 * Signal 0 is used as an existence probe: 0 means alive, -1 means gone.
 */
JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_killPid(
    JNIEnv *env, jclass clazz, jint pid, jint sig) {
    if (pid <= 0) return -1;
    if (kill(-pid, sig) == 0) return 0;
    return kill(pid, sig) == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_closeFd(
    JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}

/* No blocking wait in a reader or closer; ECHILD also means we no longer own the PID. */
JNIEXPORT jboolean JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_pollExit(
    JNIEnv *env, jclass clazz, jint pid) {
    if (pid <= 0) { throw_io(env, "Invalid PTY child PID"); return JNI_FALSE; }
    pid_t result;
    do { result = waitpid(pid, NULL, WNOHANG); } while (result < 0 && errno == EINTR);
    if (result == pid || (result < 0 && errno == ECHILD)) return JNI_TRUE;
    if (result < 0) throw_io(env, "PTY child reap failed");
    return JNI_FALSE;
}
