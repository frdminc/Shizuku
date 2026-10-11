package rikka.shizuku.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's config file sits in a directory the shell user owns, and a root server writes it.
 * Nothing at the config's names (the file itself, the write's temp files, and the backup and new
 * file an earlier version's AtomicFile left) may make the server read or write through a symbolic
 * link, and no failure or interrupted write may lose the config. These tests run the file's read
 * and write logic over java.nio operations on a real directory with real symbolic links; [NioOps]
 * opens, creates and renames the way the device operations do (no-follow open, exclusive create,
 * rename over the name), and [FaultyOps] fails or stops a chosen operation.
 */
class SafeConfigFileTest {
    @TempDir
    lateinit var dir: Path

    private val pid = 4242
    private val base get() = dir.resolve("shizuku.json")
    private val temp get() = dir.resolve("shizuku.json.tmp.$pid")
    private val legacyTemp get() = dir.resolve("shizuku.json.tmp")
    private val backup get() = dir.resolve("shizuku.json.bak")
    private val newFile get() = dir.resolve("shizuku.json.new")
    private val outside get() = dir.resolve("outside")

    private fun file(ops: SafeConfigFile.Ops = NioOps()) = SafeConfigFile(base.toFile(), ops, pid)

    private fun put(path: Path, text: String) = Files.write(path, text.toByteArray())

    private fun text(path: Path) = String(Files.readAllBytes(path))

    private fun isRegular(path: Path) = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    private fun mode(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    private fun exists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun names() = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    /** A file outside the config's names, readable and writable by its owner only. */
    private fun putOutsideFile(): Path {
        put(outside, "outside")
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-------"))
        return outside
    }

    private fun assertOutsideFileUnchanged() {
        assertEquals("outside", text(outside), "the link's target was written")
        assertEquals("rw-------", mode(outside), "the link's target had its mode changed")
    }

    // --- write ---------------------------------------------------------------------------------

    @Test
    fun `a write through a symbolic link at the config name leaves its target alone and puts a regular file there`() {
        putOutsideFile()
        Files.createSymbolicLink(base, outside)

        file().write("{\"v\":1}".toByteArray())

        assertOutsideFileUnchanged()
        assertTrue(isRegular(base), "the config is still a symbolic link")
        assertEquals("{\"v\":1}", text(base))
    }

    @Test
    fun `a write does not create the target of a dangling symbolic link at the config name`() {
        val target = dir.resolve("created-by-the-server")
        Files.createSymbolicLink(base, target)

        file().write("{}".toByteArray())

        assertFalse(exists(target), "the write created the link's target")
        assertTrue(isRegular(base))
    }

    @Test
    fun `a write does not follow a symbolic link at its temp name`() {
        putOutsideFile()
        Files.createSymbolicLink(temp, outside)

        file().write("{\"v\":2}".toByteArray())

        assertOutsideFileUnchanged()
        assertTrue(isRegular(base))
        assertEquals("{\"v\":2}", text(base))
        assertEquals(listOf("outside", "shizuku.json"), names())
    }

    @Test
    fun `a write does not follow a symbolic link at the temp name an earlier version used`() {
        putOutsideFile()
        Files.createSymbolicLink(legacyTemp, outside)

        file().write("{\"v\":2}".toByteArray())

        assertOutsideFileUnchanged()
        assertEquals("{\"v\":2}", text(base))
        assertEquals(listOf("outside", "shizuku.json"), names())
    }

    @Test
    fun `a write does not follow a symbolic link at the new name an earlier version used`() {
        putOutsideFile()
        Files.createSymbolicLink(newFile, outside)

        file().write("{\"v\":3}".toByteArray())

        assertOutsideFileUnchanged()
        assertEquals("{\"v\":3}", text(base))
        assertFalse(exists(newFile), "the new name is left behind")
    }

    @Test
    fun `a write does not follow a symbolic link at the backup name`() {
        putOutsideFile()
        put(base, "{\"v\":0}")
        Files.createSymbolicLink(backup, outside)

        file().write("{\"v\":4}".toByteArray())

        assertOutsideFileUnchanged()
        assertTrue(isRegular(base))
        assertEquals("{\"v\":4}", text(base))
        assertFalse(exists(backup), "the backup name is left behind")
    }

    @Test
    fun `a written config is owner and group read-write only`() {
        file().write("{}".toByteArray())

        assertEquals("rw-rw----", mode(base))
    }

    @Test
    fun `a write removes the backup and new file an earlier version left`() {
        put(base, "{\"v\":0}")
        put(backup, "{\"v\":-1}")
        put(newFile, "{\"v\":-2}")

        file().write("{\"v\":5}".toByteArray())

        assertEquals("{\"v\":5}", text(base))
        assertFalse(exists(backup))
        assertFalse(exists(newFile))
        assertContentEquals("{\"v\":5}".toByteArray(), file().read(), "a leftover backup won over the new config")
    }

    @Test
    fun `temp files that writes which did not finish left are removed, whatever process left them`() {
        put(temp, "half a")
        put(legacyTemp, "half b")
        put(dir.resolve("shizuku.json.tmp.17"), "half c")
        put(dir.resolve("shizuku.json.tmpx"), "not a temp name")

        file().write("{\"v\":6}".toByteArray())

        assertEquals("{\"v\":6}", text(base))
        assertEquals(listOf("shizuku.json", "shizuku.json.tmpx"), names())
    }

    @Test
    fun `an earlier process's temp name that cannot be removed does not stop a write`() {
        val stuck = dir.resolve("shizuku.json.tmp.17")
        Files.createDirectory(stuck)
        put(stuck.resolve("x"), "x")

        file().write("{\"v\":7}".toByteArray())

        assertEquals("{\"v\":7}", text(base))
    }

    @Test
    fun `a temp name that cannot be removed fails the write and keeps the config`() {
        put(base, "{\"v\":8}")
        Files.createDirectory(temp)
        put(temp.resolve("x"), "x")

        assertFailsWith<IOException> { file().write("{\"v\":9}".toByteArray()) }

        assertEquals("{\"v\":8}", text(base))
        assertContentEquals("{\"v\":8}".toByteArray(), file().read())
    }

    // --- write failures ------------------------------------------------------------------------

    @Test
    fun `a temp file that cannot be written fails the write, keeps the config and leaves no temp file`() {
        put(base, "{\"v\":10}")
        val ops = FaultyOps(NioOps()).apply { failCreate = true }

        assertFailsWith<IOException> { file(ops).write("{\"v\":11}".toByteArray()) }

        assertEquals("{\"v\":10}", text(base))
        assertEquals(listOf("shizuku.json"), names())
    }

    @Test
    fun `a temp file that cannot be renamed fails the write, keeps the config and leaves no temp file`() {
        put(base, "{\"v\":12}")
        val ops = FaultyOps(NioOps()).apply { failRenameFrom = temp.toString() }

        assertFailsWith<IOException> { file(ops).write("{\"v\":13}".toByteArray()) }

        assertEquals("{\"v\":12}", text(base))
        assertEquals(listOf("shizuku.json"), names())
    }

    @Test
    fun `a backup name that cannot be renamed to still lets the write in, and no backup is read in its place`() {
        put(base, "{\"v\":14}")
        val ops = FaultyOps(NioOps()).apply { failRenameTo = backup.toString() }
        put(backup, "{\"v\":-14}")

        file(ops).write("{\"v\":15}".toByteArray())

        assertEquals("{\"v\":15}", text(base))
        assertFalse(exists(backup))
        assertContentEquals("{\"v\":15}".toByteArray(), file().read())
    }

    @Test
    fun `a new config that cannot be moved from the backup name to the config name is still the one read`() {
        put(base, "{\"v\":16}")
        val ops = FaultyOps(NioOps()).apply { failRenameFrom = backup.toString() }

        file(ops).write("{\"v\":17}".toByteArray())

        assertContentEquals("{\"v\":17}".toByteArray(), file().read())
        assertEquals("{\"v\":17}", text(base))
    }

    @Test
    fun `a write stopped at any step leaves the config it replaced or the new one, and the new one once it is complete`() {
        // A backup can be there at a write: a read whose move of it failed, or a server of an
        // earlier version writing at the same time. It holds an older config than the one in use.
        var step = 0
        while (true) {
            step++
            val sub = Files.createDirectory(dir.resolve("step$step"))
            val cfg = sub.resolve("shizuku.json")
            put(cfg, "{\"v\":\"in use\"}")
            put(sub.resolve("shizuku.json.bak"), "{\"v\":\"older\"}")
            val ops = FaultyOps(NioOps()).apply { stopAtStep = step }

            val stopped =
                try {
                    SafeConfigFile(cfg.toFile(), ops, pid).write("{\"v\":\"new\"}".toByteArray())
                    false
                } catch (e: Stopped) {
                    true
                }

            val read = String(SafeConfigFile(cfg.toFile(), NioOps(), pid + 1).read()!!)
            if (ops.installed) {
                assertEquals("{\"v\":\"new\"}", read, "stopped at step $step (${ops.lastOp}) after the new config was renamed into place")
            } else {
                // AtomicFile's rule: the backup is the good copy until a write replaces it.
                assertEquals("{\"v\":\"older\"}", read, "stopped at step $step (${ops.lastOp})")
            }
            if (!stopped) break
            assertTrue(step < 20, "the write did not finish")
        }
    }

    // --- directory sync --------------------------------------------------------------------------

    // A rename is on disk only once its directory is: on f2fs one that was not synced waits for the
    // next checkpoint, and a power loss before it brings the previous config back.

    @Test
    fun `a write syncs the directory after its last rename`() {
        put(base, "{\"v\":24}")
        val ops = FaultyOps(NioOps())

        file(ops).write("{\"v\":25}".toByteArray())

        assertSyncedAfterLastRename(ops)
    }

    @Test
    fun `a write that renames straight to the config name syncs the directory after it`() {
        put(base, "{\"v\":26}")
        val ops = FaultyOps(NioOps()).apply { failRenameTo = backup.toString() }

        file(ops).write("{\"v\":27}".toByteArray())

        assertSyncedAfterLastRename(ops)
    }

    @Test
    fun `a write whose new config stays at the backup name syncs the directory after it`() {
        put(base, "{\"v\":28}")
        val ops = FaultyOps(NioOps()).apply { failRenameFrom = backup.toString() }

        file(ops).write("{\"v\":29}".toByteArray())

        assertSyncedAfterLastRename(ops)
    }

    @Test
    fun `a directory sync that fails still counts as saved`() {
        put(base, "{\"v\":30}")
        val ops = FaultyOps(NioOps()).apply { failSyncDir = true }

        file(ops).write("{\"v\":31}".toByteArray())

        assertTrue(ops.log.contains("syncDir $dir"), "the directory sync was not tried: ${ops.log}")
        assertEquals("{\"v\":31}", text(base))
        assertContentEquals("{\"v\":31}".toByteArray(), file().read())
    }

    @Test
    fun `a write that fails does not sync the directory`() {
        put(base, "{\"v\":32}")
        val ops = FaultyOps(NioOps()).apply { failCreate = true }

        assertFailsWith<IOException> { file(ops).write("{\"v\":33}".toByteArray()) }

        assertFalse(ops.log.any { it.startsWith("syncDir") }, "synced after a failed write: ${ops.log}")
    }

    private fun assertSyncedAfterLastRename(ops: FaultyOps) {
        val lastRename = ops.log.indexOfLast { it.startsWith("rename") }
        val sync = ops.log.lastIndexOf("syncDir $dir")
        assertTrue(lastRename >= 0, "no rename: ${ops.log}")
        assertTrue(sync > lastRename, "the directory was not synced after the last rename: ${ops.log}")
    }

    // --- read ----------------------------------------------------------------------------------

    @Test
    fun `a symbolic link at the config name reads as no config and its target is not loaded`() {
        put(outside, "{\"packages\":[{\"uid\":10123,\"flags\":2}]}")
        Files.createSymbolicLink(base, outside)

        assertNull(file().read())
        assertEquals("{\"packages\":[{\"uid\":10123,\"flags\":2}]}", text(outside))
    }

    @Test
    fun `a symbolic link at the backup name is not loaded and does not replace the config`() {
        put(outside, "{\"v\":\"outside\"}")
        put(base, "{\"v\":18}")
        Files.createSymbolicLink(backup, outside)

        assertContentEquals("{\"v\":18}".toByteArray(), file().read())
        assertTrue(isRegular(base), "the backup's link was moved over the config")
        assertEquals("{\"v\":\"outside\"}", text(outside))
    }

    @Test
    fun `a directory at the config name reads as no config`() {
        Files.createDirectory(base)

        assertNull(file().read())
    }

    @Test
    fun `no config file reads as no config`() {
        assertNull(file().read())
    }

    @Test
    fun `a file larger than the cap reads as no config`() {
        Files.write(base, ByteArray(SafeConfigFile.MAX_BYTES + 1) { ' '.code.toByte() })

        assertNull(file().read())
    }

    @Test
    fun `a file at the cap is read`() {
        Files.write(base, ByteArray(SafeConfigFile.MAX_BYTES) { ' '.code.toByte() })

        assertEquals(SafeConfigFile.MAX_BYTES, file().read()!!.size)
    }

    @Test
    fun `a written config reads back`() {
        val json = "{\"version\":2,\"packages\":[{\"uid\":10123,\"flags\":2,\"packages\":[\"a.b\"]}]}"
        file().write(json.toByteArray())

        assertContentEquals(json.toByteArray(), file().read())
    }

    // --- upgrade from AtomicFile ---------------------------------------------------------------

    @Test
    fun `a config an earlier version wrote reads as it is`() {
        put(base, "{\"v\":19}")

        assertContentEquals("{\"v\":19}".toByteArray(), file().read())
        assertEquals("{\"v\":19}", text(base))
    }

    @Test
    fun `a backup an earlier version left is the good copy and is moved back to the config name`() {
        // AtomicFile kept the last good config as the backup while it wrote the new one; a write
        // that never finished leaves a partial config and the backup.
        put(base, "{\"v\":")
        put(backup, "{\"v\":20}")

        assertContentEquals("{\"v\":20}".toByteArray(), file().read())
        assertEquals("{\"v\":20}", text(base))
        assertFalse(exists(backup))
    }

    @Test
    fun `a backup that cannot be moved back is read where it is`() {
        put(base, "{\"v\":")
        put(backup, "{\"v\":21}")
        val ops = FaultyOps(NioOps()).apply { failRenameFrom = backup.toString() }

        assertContentEquals("{\"v\":21}".toByteArray(), file(ops).read())
        assertEquals("{\"v\":21}", text(backup), "the backup was changed")
    }

    @Test
    fun `a backup alone is read`() {
        put(backup, "{\"v\":22}")

        assertContentEquals("{\"v\":22}".toByteArray(), file().read())
        assertTrue(isRegular(base))
    }

    @Test
    fun `a new file an earlier version left does not replace the config`() {
        put(base, "{\"v\":23}")
        put(newFile, "{\"v\":-23}")

        assertContentEquals("{\"v\":23}".toByteArray(), file().read())
    }

    /** Thrown where [FaultyOps] stops a write, as if the process had died there. */
    private class Stopped : Error()

    /**
     * Fails chosen operations with an IOException, or stops at the n-th operation that changes the
     * directory as if the process died there (nothing after it runs).
     */
    private class FaultyOps(private val ops: SafeConfigFile.Ops) : SafeConfigFile.Ops by ops {
        var failCreate = false
        var failRenameFrom: String? = null
        var failRenameTo: String? = null
        var failSyncDir = false
        var stopAtStep = 0
        /** Every operation that changes or syncs the directory, in order. */
        val log = mutableListOf<String>()
        /** The temp file was renamed to one of the config's names. */
        var installed = false
        var lastOp = ""
        private var steps = 0

        private fun step(op: String) {
            lastOp = op
            log.add(op)
            if (++steps == stopAtStep) throw Stopped()
        }

        override fun createExclusive(path: String, data: ByteArray) {
            step("create $path")
            if (failCreate) throw IOException("cannot create $path")
            ops.createExclusive(path, data)
        }

        override fun rename(from: String, to: String) {
            step("rename $from to $to")
            if (from == failRenameFrom || to == failRenameTo) throw IOException("cannot rename $from to $to")
            ops.rename(from, to)
            if (from.contains(".tmp")) installed = true
        }

        override fun remove(path: String): Boolean {
            step("remove $path")
            return ops.remove(path)
        }

        override fun syncDir(dir: String) {
            step("syncDir $dir")
            if (failSyncDir) throw IOException("cannot sync $dir")
            ops.syncDir(dir)
        }
    }

    /**
     * The file operations over java.nio, each without following a symbolic link at the name it is
     * given, as the device operations over android.system.Os do.
     */
    internal class NioOps : SafeConfigFile.Ops {
        private fun attrs(path: String): BasicFileAttributes? =
            try {
                Files.readAttributes(Path.of(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                null
            }

        override fun kind(path: String): SafeConfigFile.Kind {
            val a = attrs(path) ?: return SafeConfigFile.Kind.MISSING
            return if (a.isRegularFile) SafeConfigFile.Kind.REGULAR else SafeConfigFile.Kind.OTHER
        }

        override fun readRegular(path: String, maxBytes: Int): ByteArray? {
            val a = attrs(path) ?: return null
            if (!a.isRegularFile) throw SafeConfigFile.NotConfigException("$path is not a regular file")
            // NOFOLLOW_LINKS on open is O_NOFOLLOW: a link swapped in after the check fails here.
            FileChannel.open(Path.of(path), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                if (ch.size() > maxBytes) throw SafeConfigFile.NotConfigException("$path is larger than $maxBytes bytes")
                val out = ByteBuffer.allocate(ch.size().toInt())
                while (out.hasRemaining() && ch.read(out) >= 0) Unit
                return out.array()
            }
        }

        override fun createExclusive(path: String, data: ByteArray) {
            val p = Path.of(path)
            try {
                FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { ch ->
                    Files.getFileAttributeView(p, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE))
                    val buf = ByteBuffer.wrap(data)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
            } catch (e: FileAlreadyExistsException) {
                throw IOException("$path exists", e)
            }
        }

        override fun remove(path: String): Boolean = Files.deleteIfExists(Path.of(path))

        override fun rename(from: String, to: String) {
            Files.move(Path.of(from), Path.of(to), StandardCopyOption.ATOMIC_MOVE)
        }

        override fun list(dir: String): Array<String> = Path.of(dir).toFile().list() ?: emptyArray()

        override fun syncDir(dir: String) {
            FileChannel.open(Path.of(dir), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }
    }
}
