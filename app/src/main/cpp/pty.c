#define _GNU_SOURCE
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "HtopPtyNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void free_envp(char **envp) {
    if (!envp) {
        return;
    }
    for (size_t i = 0; envp[i] != NULL; i++) {
        free(envp[i]);
    }
    free(envp);
}

static char **copy_envp(JNIEnv *env, jobjectArray jenvp) {
    if (!jenvp) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, jenvp);
    char **envp = (char **)calloc((size_t)len + 1, sizeof(char *));
    if (!envp) {
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, jenvp, i);
        if (!jstr) {
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
        if (utf) {
            envp[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, jstr, utf);
        }
        (*env)->DeleteLocalRef(env, jstr);
        if (!envp[i]) {
            free_envp(envp);
            return NULL;
        }
    }
    envp[len] = NULL;
    return envp;
}

static void reset_child_signals(void) {
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);

    static const int sigs[] = {
        SIGINT, SIGQUIT, SIGPIPE, SIGCHLD, SIGTSTP, SIGTTIN, SIGTTOU, SIGHUP, SIGWINCH
    };
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++) {
        sigaction(sigs[i], &sa, NULL);
    }
}

static void close_inherited_fds(void) {
    struct rlimit rl;
    int max_fd = 4096;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) {
        if (rl.rlim_cur < (rlim_t)max_fd) {
            max_fd = (int)rl.rlim_cur;
        }
    }
    for (int fd = 3; fd < max_fd; fd++) {
        close(fd);
    }
}

JNIEXPORT jintArray JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_openPty(
    JNIEnv *env,
    jclass clazz,
    jstring jcmd,
    jobjectArray jenvp,
    jint rows,
    jint cols,
    jint xpixel,
    jint ypixel
) {
    (void)clazz;
    if (!jcmd) {
        return NULL;
    }

    const char *cmd_utf = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (!cmd_utf) {
        return NULL;
    }
    char *cmd_copy = strdup(cmd_utf);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd_utf);
    if (!cmd_copy) {
        return NULL;
    }

    char **c_envp = copy_envp(env, jenvp);
    if (jenvp && !c_envp) {
        free(cmd_copy);
        return NULL;
    }

    int master_fd = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        LOGE("open(/dev/ptmx) failed: %s", strerror(errno));
        free(cmd_copy);
        free_envp(c_envp);
        return NULL;
    }

    char slave_name[128];
    if (grantpt(master_fd) != 0 ||
        unlockpt(master_fd) != 0 ||
        ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        LOGE("PTY setup failed: %s", strerror(errno));
        close(master_fd);
        free(cmd_copy);
        free_envp(c_envp);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_xpixel = (unsigned short)(xpixel > 0 ? xpixel : 0);
    ws.ws_ypixel = (unsigned short)(ypixel > 0 ? ypixel : 0);
    ioctl(master_fd, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        close(master_fd);
        free(cmd_copy);
        free_envp(c_envp);
        return NULL;
    }

    if (pid == 0) {
        reset_child_signals();

        if (setsid() < 0) {
            _exit(125);
        }

        int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) {
            _exit(126);
        }

        ioctl(slave_fd, TIOCSCTTY, 0);

        struct termios t;
        if (tcgetattr(slave_fd, &t) == 0) {
#ifdef IUTF8
            t.c_iflag |= IUTF8;
#endif
            tcsetattr(slave_fd, TCSANOW, &t);
        }

        ioctl(slave_fd, TIOCSWINSZ, &ws);

        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);

        if (slave_fd > STDERR_FILENO) {
            close(slave_fd);
        }
        close(master_fd);
        close_inherited_fds();

        char *const argv[] = {"/system/bin/sh", "-c", cmd_copy, NULL};
        if (c_envp) {
            execve("/system/bin/sh", argv, c_envp);
        } else {
            execv("/system/bin/sh", argv);
        }
        _exit(127);
    }

    free(cmd_copy);
    free_envp(c_envp);

    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        close(master_fd);
        return NULL;
    }
    jint vals[2] = {(jint)master_fd, (jint)pid};
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_setWindowSize(
    JNIEnv *env,
    jclass clazz,
    jint master_fd,
    jint rows,
    jint cols,
    jint xpixel,
    jint ypixel
) {
    (void)env;
    (void)clazz;
    if (master_fd < 0) {
        return -EBADF;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_xpixel = (unsigned short)(xpixel > 0 ? xpixel : 0);
    ws.ws_ypixel = (unsigned short)(ypixel > 0 ? ypixel : 0);

    if (ioctl(master_fd, TIOCSWINSZ, &ws) < 0) {
        return -errno;
    }

    pid_t pgrp = tcgetpgrp(master_fd);
    if (pgrp > 0) {
        kill(-pgrp, SIGWINCH);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_readFd(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    (void)clazz;
    if (fd < 0 || !buffer || offset < 0 || length < 0) {
        return -EINVAL;
    }
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (offset > cap || length > cap - offset) {
        return -EINVAL;
    }
    if (length == 0) {
        return 0;
    }

    jbyte tmp[8192];
    jint to_read = length < (jint)sizeof(tmp) ? length : (jint)sizeof(tmp);
    ssize_t n;
    do {
        n = read(fd, tmp, (size_t)to_read);
    } while (n < 0 && errno == EINTR);

    if (n == 0) {
        return 0;
    }
    if (n < 0) {
        if (errno == EIO) {
            return 0;
        }
        return -errno;
    }

    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)n, tmp);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_writeFd(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    (void)clazz;
    if (fd < 0 || !buffer || offset < 0 || length < 0) {
        return -EINVAL;
    }
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (offset > cap || length > cap - offset) {
        return -EINVAL;
    }
    if (length == 0) {
        return 0;
    }

    jbyte tmp[4096];
    jint total_written = 0;
    while (total_written < length) {
        jint chunk = length - total_written;
        if (chunk > (jint)sizeof(tmp)) {
            chunk = (jint)sizeof(tmp);
        }
        (*env)->GetByteArrayRegion(env, buffer, offset + total_written, chunk, tmp);
        if ((*env)->ExceptionCheck(env)) {
            return -EINVAL;
        }

        jint chunk_written = 0;
        while (chunk_written < chunk) {
            ssize_t w = write(fd, tmp + chunk_written, (size_t)(chunk - chunk_written));
            if (w < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return -errno;
            }
            chunk_written += (jint)w;
        }
        total_written += chunk;
    }
    return total_written;
}

JNIEXPORT jint JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_waitFor(
    JNIEnv *env,
    jclass clazz,
    jint pid
) {
    (void)env;
    (void)clazz;
    if (pid <= 0) {
        return -EINVAL;
    }
    int status = 0;
    pid_t res;
    do {
        res = waitpid((pid_t)pid, &status, 0);
    } while (res < 0 && errno == EINTR);

    if (res < 0) {
        return -errno;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_sendSignal(
    JNIEnv *env,
    jclass clazz,
    jint pid,
    jint sig
) {
    (void)env;
    (void)clazz;
    if (pid <= 0) {
        return -EINVAL;
    }
    int r1 = kill(-(pid_t)pid, sig);
    int e1 = errno;
    int r2 = kill((pid_t)pid, sig);
    int e2 = errno;
    if (r1 == 0 || r2 == 0) {
        return 0;
    }
    return -(e2 != 0 ? e2 : e1);
}

JNIEXPORT void JNICALL
Java_com_andrewchqng_htop_pty_PtyNative_closeFd(
    JNIEnv *env,
    jclass clazz,
    jint fd
) {
    (void)env;
    (void)clazz;
    if (fd >= 0) {
        close(fd);
    }
}
