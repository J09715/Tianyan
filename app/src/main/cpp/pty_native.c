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
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>

/*
 * openpty() 的 termios 参数传 NULL 会走系统默认行规程——ECHO 打开。
 * 对交互终端这是对的（用户要看见自己敲的字），但对 ACP / MCP STDIO 这类
 * 「按行分帧的 JSON 协议」是致命的：宿主写进 master 的请求帧会被 slave
 * 原样回显读回，读循环把它误判成引擎主动发起的反向请求，于是回一条
 * -32601，那条错误响应再被回显、id 命中挂起的请求，握手直接抛异常。
 *
 * 因此协议会话必须把 line discipline 关成 raw：宿主与引擎之间只有字节流，
 * 不允许内核替任何一方做加工。
 */
static int configure_termios(int fd, jboolean raw_mode) {
    if (!raw_mode) return 0;
    struct termios tio;
    if (tcgetattr(fd, &tio) == -1) return -1;
    /* ECHO 系列回显 → 自己写的帧被读回；ICANON 行缓冲 → 不足一行的输出被拖住；
       ISIG 把帧里的 0x03 当 SIGINT；IEXTEN 处理 Ctrl+V 等扩展序列。 */
    tio.c_lflag &= ~(tcflag_t)(ECHO | ECHOE | ECHOK | ECHONL | ICANON | ISIG | IEXTEN);
    /* IXON/IXOFF 软件流控会吞掉 0x11/0x13；ICRNL/INLCR/IGNCR 改写换行，
       ISTRIP 砍掉第 8 位（UTF-8 直接废掉）；BRKINT 把 break 转成信号。 */
    tio.c_iflag &= ~(tcflag_t)(IXON | IXOFF | ICRNL | INLCR | IGNCR | ISTRIP | BRKINT | INPCK);
    /* OPOST 关掉，避免 \n 被改写成 \r\n —— 分帧按 \n 切，多一个 \r 会污染 JSON。 */
    tio.c_oflag &= ~(tcflag_t)OPOST;
    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;
    return tcsetattr(fd, TCSANOW, &tio);
}

static char **strings_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    int n = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t)n + 1, sizeof(char *));
    if (result == NULL) return NULL;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (s == NULL) continue;
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        result[i] = strdup(cs);
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
 * argv/envp/cwd/columns/rows/rawMode -> int[]{masterFd, childPid}
 * The child becomes a session leader with the slave as controlling terminal.
 * rawMode=true strips line discipline (ECHO/ICANON/ISIG...) for protocol
 * sessions; interactive terminals keep the kernel default so typed input echoes.
 */
JNIEXPORT jintArray JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_openAndExec(
    JNIEnv *env, jclass clazz,
    jobjectArray argv, jobjectArray envp, jstring cwd,
    jint columns, jint rows, jboolean rawMode) {
    int master = -1, slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) == -1) {
        throw_io(env, "openpty failed");
        return NULL;
    }
    /* 在 fork 之前配好，子进程继承；失败不致命，退回默认行规程。 */
    configure_termios(slave, rawMode);
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ioctl(master, TIOCSWINSZ, &ws);

    char **cargv = strings_array(env, argv);
    char **cenvp = strings_array(env, envp);
    const char *ccwd = cwd != NULL ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

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
    return result;
}

/* Reads up to buffer.length bytes from the master; returns bytes read or -1. */
JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_readFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer) {
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len <= 0) return 0;
    jbyte *tmp = (jbyte *)malloc((size_t)len);
    if (tmp == NULL) return -1;
    ssize_t n = read((int)fd, tmp, (size_t)len);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, tmp);
    }
    free(tmp);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_writeFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (length <= 0) return 0;
    jbyte *tmp = (jbyte *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, buffer, offset, length, tmp);
    ssize_t n = write((int)fd, tmp, (size_t)length);
    free(tmp);
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

/* Reaps an already-dead child so it does not linger as a zombie. */
JNIEXPORT void JNICALL
Java_top_tianyan_app_runtime_pty_NativePty_waitPid(
    JNIEnv *env, jclass clazz, jint pid) {
    if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
    }
}
