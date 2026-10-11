package rikka.shizuku.server;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

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
     * start-up timeout (ShizukuStateMachine.STARTING_TIMEOUT_MS) to come up. The start screen
     * (Starter.waitForBinder) gives up after 20 s and shows its error, so a server that waited
     * longer than that comes up after the screen has reported a failure.
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
     *
     * <p>The mode and group are set only on a plain file: a regular file with one link, owned by
     * root or shell, opened without following a symbolic link. Anything else at the path is
     * removed and the file created afresh; if that fails too, nothing is changed and the lock
     * attempt runs unguarded (see {@link #FILE_LOCK}).
     */
    private static void createShared(String path) {
        for (int attempt = 0; attempt < 2; attempt++) {
            FileDescriptor fd;
            try {
                fd = Os.open(path, OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW
                        | OsConstants.O_CLOEXEC, 0660);
            } catch (ErrnoException | RuntimeException e) {
                // A symbolic link or a directory is removed once; a regular file this server
                // cannot open (no access) is left as it is, and the lock attempt reports it.
                if (attempt == 0 && !isRegularFile(path) && removeNonFile(path)) {
                    continue;
                }
                return;
            }
            try {
                StructStat st = Os.fstat(fd);
                boolean regular = OsConstants.S_ISREG(st.st_mode);
                if (!isPlainFile(regular, st.st_nlink, st.st_uid)) {
                    // Another node, or a second link to the file: replaced once. A regular file
                    // owned by someone else is left as it is.
                    if (attempt == 0 && (!regular || st.st_nlink != 1) && removeNonFile(path)) {
                        continue;
                    }
                    return;
                }
                try {
                    Os.fchmod(fd, 0660);
                    Os.fchown(fd, -1, android.os.Process.SHELL_UID);
                } catch (ErrnoException | RuntimeException ignored) {
                    // Not the owner (a shell server opening root's file): the bits are already set.
                }
                return;
            } catch (ErrnoException | RuntimeException e) {
                return;
            } finally {
                try {
                    Os.close(fd);
                } catch (ErrnoException | RuntimeException ignored) {
                }
            }
        }
    }

    /** Whether lstat shows a regular file at {@code path}; true when it cannot tell. */
    private static boolean isRegularFile(String path) {
        try {
            return OsConstants.S_ISREG(Os.lstat(path).st_mode);
        } catch (ErrnoException | RuntimeException e) {
            return true;
        }
    }

    /** Removes the name at {@code path}, never what a symbolic link there points to. */
    private static boolean removeNonFile(String path) {
        try {
            Os.remove(path);
            return true;
        } catch (ErrnoException | RuntimeException e) {
            LOGGER.w(e, "cannot remove %s", path);
            return false;
        }
    }

    /** The lock file is a regular file with one link, owned by root or shell. */
    @VisibleForTesting
    static boolean isPlainFile(boolean regular, long links, int uid) {
        return regular && links == 1 && (uid == 0 || uid == android.os.Process.SHELL_UID);
    }

    /**
     * Opens the file at {@code path} and takes the lock. The file must be the plain file
     * {@link #createShared} left: checked by lstat before the open (so a symbolic link is never
     * followed) and by fstat after it (the same file). Otherwise the attempt fails and the server
     * runs unguarded.
     */
    private static final Lock FILE_LOCK = path -> {
        StructStat before;
        try {
            before = Os.lstat(path);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
        if (!isPlainFile(OsConstants.S_ISREG(before.st_mode), before.st_nlink, before.st_uid)) {
            throw new IOException(path + " is not a plain file");
        }
        // RandomAccessFile opens with O_CREAT and follows a link; a path swapped after the lstat is
        // caught by the dev/ino check below, which leaves the server unguarded, never chmodded.
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            StructStat opened = Os.fstat(file.getFD());
            if (opened.st_dev != before.st_dev || opened.st_ino != before.st_ino) {
                throw new IOException(path + " changed while it was opened");
            }
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) {
                file.close();
                return null;
            }
            return file::close;
        } catch (ErrnoException e) {
            closeQuietly(file);
            throw new IOException(e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(file);
            throw e;
        }
    };

    private static void closeQuietly(RandomAccessFile file) {
        try {
            file.close();
        } catch (IOException ignored) {
        }
    }

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
