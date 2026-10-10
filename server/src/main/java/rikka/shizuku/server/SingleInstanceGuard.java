package rikka.shizuku.server;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import rikka.shizuku.server.util.Logger;

/**
 * One server process at a time, for the whole life of the process.
 *
 * <p>The starter kills every process named {@code shizuku_plus_server} and then starts a new one,
 * with nothing to stop two starters overlapping: a second starter that scans before the first
 * one's new server has taken its name starts another server, and both then load, migrate and save
 * the same config file. That happens when the watchdog restarts the server while a restart from
 * outside the app (a shell, ops tooling) is already under way.
 *
 * <p>The guard is a POSIX record lock ({@code fcntl(F_SETLK)}, which is what
 * {@link java.nio.channels.FileChannel#tryLock()} takes) on a file beside the config. The kernel
 * drops it when the process dies, SIGKILL included, so the starter's kill frees it; a forked child
 * does not inherit it, so nothing the server spawns can keep it after the server is gone; and only
 * root and shell can open the file, so an app cannot take it first and keep the server from
 * starting. The server takes it before anything else and a second server that cannot get it exits.
 */
public final class SingleInstanceGuard {

    private static final Logger LOGGER = new Logger("SingleInstanceGuard");

    /**
     * How long a new server waits for one the starter has just killed to finish dying. The kernel
     * frees the lock only once the killed process leaves the kernel, which a process stuck in
     * uninterruptible I/O can delay by seconds; a new server that gave up then would leave none
     * running, and nothing restarts it. A server that is really still running keeps the lock for
     * good, and the one waiting has touched nothing, so waiting longer costs only an idle
     * process. 30 s leaves a server that gets the lock late the rest of the manager's 90 s
     * start-up timeout (ShizukuStateMachine.STARTING_TIMEOUT_MS) to come up.
     */
    static final long WAIT_MS = 30_000;
    static final long RETRY_MS = 100;

    private static final String LOCK_NAME = "shizuku_plus_server.lock";

    /** Takes the lock at a path: the held lock, or null when another process holds it. */
    public interface Lock {
        @Nullable
        Closeable tryLock(String path) throws IOException;
    }

    public interface Clock {
        long uptimeMillis();

        void sleep(long ms) throws InterruptedException;
    }

    /** Held for the life of the process; never released except by the process exiting. */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static Closeable held;

    private static final Closeable NONE = () -> {
    };

    private SingleInstanceGuard() {
    }

    /**
     * Takes the guard for this process. Returns false when another server held it throughout
     * {@link #WAIT_MS}; the caller then exits without touching the config or registering a binder.
     * Returns true when the guard is held, and also when it cannot be set up here (the file cannot
     * be opened or locked): the server must not refuse to run because of the guard itself.
     */
    public static boolean acquireForProcess() {
        String path = lockFile().getAbsolutePath();
        createShared(path);
        Closeable guard = acquire(FILE_LOCK, SYSTEM_CLOCK, path, WAIT_MS, RETRY_MS);
        if (guard == null) {
            return false;
        }
        held = guard;
        return true;
    }

    /**
     * Tries to take the lock at {@code path}, retrying every {@code retryMs} for up to
     * {@code waitMs} while another process holds it. Null: another process still holds it. A lock
     * that fails for any other reason gives a no-op guard after one attempt.
     */
    @VisibleForTesting
    @Nullable
    public static Closeable acquire(Lock lock, Clock clock, String path, long waitMs, long retryMs) {
        long deadline = clock.uptimeMillis() + waitMs;
        while (true) {
            Closeable guard;
            try {
                guard = lock.tryLock(path);
            } catch (IOException | RuntimeException e) {
                LOGGER.w(e, "single-instance guard unavailable at %s; running without it", path);
                return NONE;
            }
            if (guard != null) {
                return guard;
            }
            long now = clock.uptimeMillis();
            if (now >= deadline) {
                return null;
            }
            try {
                clock.sleep(Math.min(retryMs, deadline - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** Beside the config file, which root and shell servers share and apps cannot reach. */
    private static File lockFile() {
        File shellDir = new File("/data/user_de/0/com.android.shell");
        try {
            if (shellDir.isDirectory() && shellDir.canWrite()) {
                return new File(shellDir, LOCK_NAME);
            }
        } catch (Throwable ignored) {
        }
        return new File("/data/local/tmp", LOCK_NAME);
    }

    /**
     * Creates the lock file once, before the first attempt, with its final mode so it never exists
     * with wider bits; each attempt then opens the existing file. A root server creates it as
     * root; a shell server started later must still open it, or it would run unguarded. Same bits
     * as the config file: owner and group shell read and write (set again because the umask can
     * clear the group bits at creation).
     */
    private static void createShared(String path) {
        FileDescriptor fd;
        try {
            fd = Os.open(path, OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_CLOEXEC, 0660);
        } catch (ErrnoException | RuntimeException e) {
            // The lock attempt reports it.
            return;
        }
        try {
            Os.fchmod(fd, 0660);
            Os.fchown(fd, -1, android.os.Process.SHELL_UID);
        } catch (ErrnoException | RuntimeException ignored) {
            // Not the owner (a shell server opening root's file): the bits are already set.
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException | RuntimeException ignored) {
            }
        }
    }

    private static final Lock FILE_LOCK = path -> {
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) {
                file.close();
                return null;
            }
            return file::close;
        } catch (IOException | RuntimeException e) {
            try {
                file.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    };

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    };
}
