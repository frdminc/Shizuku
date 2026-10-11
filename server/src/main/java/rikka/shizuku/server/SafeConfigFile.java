package rikka.shizuku.server;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

import rikka.shizuku.server.util.Logger;

/**
 * The server's config file, read and written without following a symbolic link at any of its
 * names.
 *
 * <p>The file is in a directory the shell user owns, and a root server reads and writes it.
 * {@code android.util.AtomicFile}, used before, opens the file, its backup ({@code .bak}) and its
 * new file ({@code .new}) by name, and the mode and group were then set by path; each of those
 * follows a symbolic link. Here:
 *
 * <ul>
 * <li>A write puts the bytes in a temp file ({@code .tmp.<pid>}) created afresh in the same
 * directory (exclusive, no-follow), syncs it, and sets mode 0660 and group shell through its
 * descriptor. It then renames the temp file to the backup's name and the backup to the config's
 * name. A rename replaces a symbolic link at the name it renames to; it does not follow it.
 * <li>A read opens the config without following a symbolic link and reads it only when fstat shows
 * a regular file of at most {@link #MAX_BYTES}. Anything else reads as no config and is left where
 * it is; the next write replaces it.
 * <li>A backup is, by AtomicFile's rule, the good copy: AtomicFile kept the last good config there
 * while it wrote a new one. A read moves a regular backup to the config's name before reading, as
 * AtomicFile did; a backup that is not a regular file is removed unread. A write goes through the
 * backup's name for the same reason: if the process dies between its two renames, the new config
 * is at the name a read prefers, and a backup left from before is never read in its place.
 * </ul>
 */
final class SafeConfigFile {

    private static final Logger LOGGER = new Logger("SafeConfigFile");

    /** Larger than any config; a larger file at the config's name reads as no config. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** What lstat shows at a name. */
    enum Kind {MISSING, REGULAR, OTHER}

    /** The file operations, none of which follows a symbolic link at the name it is given. */
    interface Ops {
        Kind kind(String path) throws IOException;

        /**
         * The contents of the regular file at {@code path}; null when nothing is there.
         *
         * @throws NotConfigException a symbolic link, anything else that is not a regular file, or
         *                            a file larger than {@code maxBytes}
         */
        @Nullable
        byte[] readRegular(String path, int maxBytes) throws IOException;

        /** Creates {@code path}, which must not exist, writes {@code data} and syncs it. */
        void createExclusive(String path, byte[] data) throws IOException;

        /** Removes the name; false when nothing was there. */
        boolean remove(String path) throws IOException;

        /** Renames {@code from} over {@code to}, replacing whatever is at {@code to}. */
        void rename(String from, String to) throws IOException;

        /** The names in directory {@code dir}; empty when it cannot be listed. */
        String[] list(String dir);
    }

    /** What is at the config's name is not something to read as a config. */
    static final class NotConfigException extends IOException {
        NotConfigException(String message) {
            super(message);
        }
    }

    private final String base;
    private final String dir;
    private final String tempPrefix;
    private final String temp;
    private final String backup;
    private final String newName;
    private final Ops ops;

    SafeConfigFile(File base) {
        this(base, OS_OPS, Os.getpid());
    }

    @VisibleForTesting
    SafeConfigFile(File base, Ops ops, int pid) {
        File absolute = base.getAbsoluteFile();
        this.base = absolute.getPath();
        this.dir = absolute.getParent();
        this.tempPrefix = absolute.getName() + ".tmp";
        this.temp = this.base + ".tmp." + pid;
        this.backup = this.base + ".bak";
        this.newName = this.base + ".new";
        this.ops = ops;
    }

    String getPath() {
        return base;
    }

    /**
     * The config's bytes; null when there is no config, or what is at its name is not a regular
     * file of at most {@link #MAX_BYTES}.
     */
    @Nullable
    synchronized byte[] read() throws IOException {
        String from = base;
        Kind backupKind = kindOrMissing(backup);
        if (backupKind == Kind.REGULAR) {
            try {
                ops.rename(backup, base);
                LOGGER.i("restored %s from %s", base, backup);
            } catch (IOException e) {
                LOGGER.w(e, "cannot move %s to %s; reading it where it is", backup, base);
                from = backup;
            }
        } else if (backupKind == Kind.OTHER) {
            LOGGER.w("%s is not a regular file; removing it unread", backup);
            removeQuietly(backup);
        }
        try {
            return ops.readRegular(from, MAX_BYTES);
        } catch (NotConfigException e) {
            LOGGER.w("%s; reading it as no config", e.getMessage());
            return null;
        }
    }

    /**
     * Replaces the config with {@code data}. On a failure before the new config is complete under
     * one of the config's names, the config is left as it was and the failure thrown.
     */
    synchronized void write(byte[] data) throws IOException {
        removeStaleTemps();
        try {
            ops.createExclusive(temp, data);
        } catch (IOException | RuntimeException e) {
            removeQuietly(temp);
            throw e;
        }
        try {
            ops.rename(temp, backup);
        } catch (IOException | RuntimeException e) {
            // Nothing can be renamed to the backup's name (a directory there, say): straight to
            // the config's name, then the backup goes, or it would be read in place of this write.
            LOGGER.w(e, "cannot rename %s to %s; renaming it to %s", temp, backup, base);
            try {
                ops.rename(temp, base);
            } catch (IOException | RuntimeException e2) {
                removeQuietly(temp);
                throw e2;
            }
            removeQuietly(backup);
            removeQuietly(newName);
            return;
        }
        try {
            ops.rename(backup, base);
        } catch (IOException | RuntimeException e) {
            // The new config is complete at the backup's name, which a read takes first.
            LOGGER.w(e, "cannot rename %s to %s; the config stays at %s until the next read", backup, base, backup);
        }
        // Left by AtomicFile on API 30 and later; never read while the config exists.
        removeQuietly(newName);
    }

    /**
     * Removes temp files writes that did not finish left behind, this process's name included:
     * the fixed name an earlier version of this class used and any {@code .tmp.<pid>}. Each is
     * unlinked by name, never followed.
     */
    private void removeStaleTemps() {
        if (dir == null) {
            removeQuietly(temp);
            return;
        }
        for (String name : ops.list(dir)) {
            if (name.equals(tempPrefix) || name.startsWith(tempPrefix + ".")) {
                removeQuietly(dir + File.separator + name);
            }
        }
    }

    private Kind kindOrMissing(String path) {
        try {
            return ops.kind(path);
        } catch (IOException | RuntimeException e) {
            LOGGER.w(e, "cannot stat %s", path);
            return Kind.MISSING;
        }
    }

    private void removeQuietly(String path) {
        try {
            ops.remove(path);
        } catch (IOException | RuntimeException e) {
            LOGGER.w(e, "cannot remove %s", path);
        }
    }

    /**
     * {@code O_CLOEXEC} is public from API 27 and present, hidden, in earlier libcore. Close-on-exec
     * only keeps a descriptor out of a process started while it is open, so a runtime without the
     * field opens without it.
     */
    private static int cloexec() {
        try {
            return OsConstants.O_CLOEXEC;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code ErrnoException.rethrowAsIOException()} is public only from API 30. */
    private static IOException io(ErrnoException e) {
        return new IOException(e.getMessage(), e);
    }

    private static final Ops OS_OPS = new Ops() {
        @Override
        public Kind kind(String path) throws IOException {
            try {
                return OsConstants.S_ISREG(Os.lstat(path).st_mode) ? Kind.REGULAR : Kind.OTHER;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) return Kind.MISSING;
                throw io(e);
            }
        }

        @Nullable
        @Override
        public byte[] readRegular(String path, int maxBytes) throws IOException {
            FileDescriptor fd;
            try {
                // O_NONBLOCK: opening a FIFO for reading would otherwise wait for a writer. It
                // changes nothing for a regular file.
                fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK
                        | cloexec(), 0);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) return null;
                if (e.errno == OsConstants.ELOOP) throw new NotConfigException(path + " is a symbolic link");
                throw io(e);
            }
            try {
                StructStat st = Os.fstat(fd);
                if (!OsConstants.S_ISREG(st.st_mode)) {
                    throw new NotConfigException(path + " is not a regular file");
                }
                if (st.st_size > maxBytes) {
                    throw new NotConfigException(path + " is larger than " + maxBytes + " bytes");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, st.st_size));
                byte[] buf = new byte[8192];
                while (true) {
                    int n = Os.read(fd, buf, 0, buf.length);
                    if (n <= 0) break;
                    if (out.size() + n > maxBytes) {
                        // Grew after the fstat.
                        throw new NotConfigException(path + " is larger than " + maxBytes + " bytes");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } catch (ErrnoException e) {
                throw io(e);
            } finally {
                closeQuietly(fd);
            }
        }

        @Override
        public void createExclusive(String path, byte[] data) throws IOException {
            FileDescriptor fd;
            try {
                fd = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                        | OsConstants.O_NOFOLLOW | cloexec(), 0660);
            } catch (ErrnoException e) {
                throw io(e);
            }
            try {
                // Closing #419: the config was once world-readable and writable. Root needs no
                // bits; shell gets access through the group. Set again because the umask can clear
                // the group bits at creation.
                Os.fchmod(fd, 0660);
                try {
                    Os.fchown(fd, -1, android.os.Process.SHELL_UID);
                } catch (ErrnoException e) {
                    LOGGER.w("failed to set the group on " + path + ": " + e);
                }
                int off = 0;
                while (off < data.length) {
                    off += Os.write(fd, data, off, data.length - off);
                }
                Os.fsync(fd);
            } catch (ErrnoException e) {
                throw io(e);
            } finally {
                closeQuietly(fd);
            }
        }

        @Override
        public boolean remove(String path) throws IOException {
            try {
                // remove(3) unlinks the name itself; a symbolic link is removed, not followed.
                Os.remove(path);
                return true;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) return false;
                throw io(e);
            }
        }

        @Override
        public void rename(String from, String to) throws IOException {
            try {
                Os.rename(from, to);
            } catch (ErrnoException e) {
                throw io(e);
            }
        }

        @Override
        public String[] list(String dir) {
            // readdir of the directory itself; no entry is opened or followed.
            String[] names = new File(dir).list();
            return names != null ? names : new String[0];
        }

        private void closeQuietly(FileDescriptor fd) {
            try {
                Os.close(fd);
            } catch (ErrnoException | RuntimeException ignored) {
            }
        }
    };
}
