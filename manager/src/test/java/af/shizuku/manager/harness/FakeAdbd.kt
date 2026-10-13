package af.shizuku.manager.harness

import af.shizuku.manager.adb.AdbMessage
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import af.shizuku.manager.adb.AdbProtocol.A_AUTH
import af.shizuku.manager.adb.AdbProtocol.A_CLSE
import af.shizuku.manager.adb.AdbProtocol.A_CNXN
import af.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import af.shizuku.manager.adb.AdbProtocol.A_OKAY
import af.shizuku.manager.adb.AdbProtocol.A_OPEN
import af.shizuku.manager.adb.AdbProtocol.A_VERSION
import af.shizuku.manager.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loopback adbd speaking AUTH by default, or optional STLS with a fresh certificate per
 * connection. Each key offer made while the key
 * is not yet authorised counts as one dialog ([offers]) and then waits, as adbd does, for the
 * user's answer. [accept] completes the handshake. [reject] and [silent] look the same on the
 * wire, as on a real device: adbd sends nothing for a denied dialog and keeps the connection open
 * and unauthorised (AOSP adbd_auth.cpp, DenyUsbDevice), so the client only gives up at its own
 * deadline; a key offered again on that connection raises a new dialog. A key offered while a
 * dialog is still up is queued behind it and raises its own dialog once that one is answered, even
 * when the answer was Allow: adbd prompts for every offer, on an authorised connection too, so
 * offers after acceptance count as well. Connections that close before saying anything (the
 * starter's port probes) are ignored.
 */
class FakeAdbd(
    private val onShell: (String) -> Unit = {},
    port: Int = 0,
    authorized: Boolean = false,
    // "tcpip:<port>": adbd restarts listening on that port (the caller opens it).
    private val onTcpip: (Int) -> Unit = {},
    private val tls: Boolean = false,
    private val fixedCertificate: Boolean = false,
    private val fixedKey: Boolean = false,
    private val omitTlsCnxn: Boolean = false,
    private val silentHello: Boolean = false,
    // Answers every A_OPEN with this chunk as output, again and again every [outputGapMs], until the
    // client hangs up: a command that never ends.
    private val endlessOutput: ByteArray? = null,
    private val outputGapMs: Long = 0,
) : Closeable {
    enum class Answer { ACCEPT, REJECT, SILENT }

    // Bound with SO_REUSEADDR so a port a closed instance held can be listened on again.
    private val server =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50)
        }
    val port: Int get() = server.localPort

    private val offerCount = AtomicInteger(0)
    val offers: Int get() = offerCount.get()
    private val signatureCount = AtomicInteger(0)
    val signatures: Int get() = signatureCount.get()
    val activeConnections: Int get() = open.size
    val clientCertificates = AtomicInteger(0)
    private val sharedKey by lazy {
        java.security.KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
    }
    private val fixedContext by lazy { newTlsContext() }
    val certificates = CopyOnWriteArrayList<java.security.cert.X509Certificate>()
    val greetingSizes = CopyOnWriteArrayList<Int>()

    private val helloCount = AtomicInteger(0)

    /** Connections that said hello (port probes that close first are not counted). */
    val connections: Int get() = helloCount.get()
    private val offered = Semaphore(0)
    private val answers = LinkedBlockingQueue<Answer>()
    private val open = CopyOnWriteArrayList<Socket>()

    /** The key has been accepted once; adbd then answers its signature without a dialog. */
    @Volatile
    var authorized = authorized

    /** Runs on adbd's thread when a client says hello, before it can record or offer anything. */
    @Volatile
    var onHello: () -> Unit = {}

    init {
        Thread({ acceptLoop() }, "fake-adbd").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits for the next not-yet-awaited key offer, calling [pump] while it waits (the caller may
     * be the thread other work needs, e.g. Robolectric's main looper).
     */
    fun awaitOffer(
        timeoutMs: Long = 30_000,
        pump: () -> Unit = {},
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!offered.tryAcquire(10, TimeUnit.MILLISECONDS)) {
            check(System.currentTimeMillis() < deadline) { "no key offer within ${timeoutMs}ms (offers=$offers)" }
            pump()
        }
    }

    fun accept() = answers.put(Answer.ACCEPT)

    fun reject() = answers.put(Answer.REJECT)

    fun silent() = answers.put(Answer.SILENT)

    /** Drops every connection, as a dying peer process would. */
    fun dropAll() = open.forEach { runCatching { it.close() } }

    override fun close() {
        runCatching { server.close() }
        dropAll()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            open += s
            Thread({ serve(s) }, "fake-adbd-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val stream = PushbackInputStream(s.getInputStream())
            var input = DataInputStream(stream)
            var output = s.getOutputStream()
            val hello = read(input) ?: return
            if (hello.command != A_CNXN) return
            greetingSizes += hello.toByteArray().size
            helloCount.incrementAndGet()
            onHello()
            if (silentHello) {
                // Wait for EOF, so tests observe the client's deadline closing the socket.
                while (input.read() != -1) { }
                return
            }
            if (tls) {
                write(output, AdbMessage(af.shizuku.manager.adb.AdbProtocol.A_STLS, 0x01000000, 0, ByteArray(0)))
                check(read(input)?.command == af.shizuku.manager.adb.AdbProtocol.A_STLS)
                val socket = (if (fixedCertificate) fixedContext else newTlsContext()).socketFactory.createSocket(s, "127.0.0.1", s.port, true) as javax.net.ssl.SSLSocket
                socket.useClientMode = false
                socket.needClientAuth = true
                socket.startHandshake()
                input = DataInputStream(socket.inputStream)
                output = socket.outputStream
                if (omitTlsCnxn) return
            } else {
                write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
                val signature = read(input) ?: return
                check(signature.command == A_AUTH && signature.arg0 == ADB_AUTH_SIGNATURE) { "expected the token signature" }
                signatureCount.incrementAndGet()
                if (!authorized) {
                    write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
                    while (true) {
                        val key = read(input) ?: return
                        check(key.command == A_AUTH && key.arg0 == ADB_AUTH_RSAPUBLICKEY) { "expected the public key" }
                        offerCount.incrementAndGet()
                        offered.release()
                        when (awaitAnswer(s, stream)) {
                            Answer.ACCEPT -> break
                            // Nothing is sent: the connection stays open until the client closes it
                            // or offers the key again.
                            Answer.REJECT, Answer.SILENT -> continue
                            null -> return
                        }
                    }
                    authorized = true
                }
            }
            write(output, AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "device::"))
            while (true) {
                val message = read(input) ?: return
                // A queued or late offer: adbd shows its dialog even though this key is accepted.
                if (message.command == A_AUTH && message.arg0 == ADB_AUTH_RSAPUBLICKEY) offerCount.incrementAndGet()
                if (message.command != A_OPEN) continue
                val command = message.data?.let { String(it).trimEnd('\u0000') }.orEmpty()
                if (command.startsWith("shell:")) onShell(command)
                if (command.startsWith("tcpip:")) onTcpip(command.removePrefix("tcpip:").toIntOrNull() ?: 0)
                write(output, AdbMessage(A_OKAY, REMOTE_ID, message.arg0, ByteArray(0)))
                if (endlessOutput != null) {
                    while (true) {
                        write(output, AdbMessage(A_WRTE, REMOTE_ID, message.arg0, endlessOutput))
                        if (outputGapMs > 0) Thread.sleep(outputGapMs)
                    }
                }
                write(output, AdbMessage(A_CLSE, REMOTE_ID, message.arg0, ByteArray(0)))
            }
        } catch (_: IOException) {
        } finally {
            open -= s
            runCatching { s.close() }
        }
    }

    /** Each accepted connection gets a fresh self-signed certificate, like adbd. */
    private fun newTlsContext(): javax.net.ssl.SSLContext {
        val pair =
            if (fixedKey) {
                sharedKey
            } else {
                java.security.KeyPairGenerator
                    .getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair()
            }
        val name =
            org.bouncycastle.asn1.x500
                .X500Name("CN=fake-adbd")
        val now = System.currentTimeMillis()
        val builder =
            org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name,
                java.math.BigInteger(128, java.security.SecureRandom()),
                java.util.Date(now - 60_000),
                java.util.Date(now + 3_600_000),
                name,
                pair.public,
            )
        val encoded =
            builder
                .build(
                    org.bouncycastle.operator.jcajce
                        .JcaContentSignerBuilder("SHA256withRSA")
                        .build(pair.private),
                ).encoded
        val cert =
            java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(encoded.inputStream()) as java.security.cert.X509Certificate
        certificates += cert
        val store =
            java.security.KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", pair.private, charArrayOf(), arrayOf(cert))
            }
        val keys =
            javax.net.ssl.KeyManagerFactory
                .getInstance(
                    javax.net.ssl.KeyManagerFactory
                        .getDefaultAlgorithm(),
                ).apply {
                    init(store, charArrayOf())
                }
        val trust =
            object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<out java.security.cert.X509Certificate>,
                    authType: String,
                ) {
                    clientCertificates.incrementAndGet()
                }

                override fun checkServerTrusted(
                    chain: Array<out java.security.cert.X509Certificate>,
                    authType: String,
                ) { }
            }
        return javax.net.ssl.SSLContext
            .getInstance("TLSv1.3")
            .apply { init(keys.keyManagers, arrayOf(trust), null) }
    }

    // Polls rather than blocking on the queue so that a connection the client has already closed
    // gives up and cannot consume an answer meant for a later one. Null: the peer went away. A byte
    // the client sends meanwhile (a key offered again) is pushed back for the next read.
    private fun awaitAnswer(
        s: Socket,
        stream: PushbackInputStream,
    ): Answer? {
        s.soTimeout = 100
        try {
            var pending = false
            while (true) {
                answers.poll()?.let { return it }
                if (pending) {
                    Thread.sleep(10)
                    continue
                }
                try {
                    val b = stream.read()
                    if (b == -1) return null
                    stream.unread(b)
                    pending = true
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            if (!s.isClosed) s.soTimeout = 0
        }
    }

    private fun read(input: DataInputStream): AdbMessage? {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val crc = b.int
        val magic = b.int
        val data = ByteArray(length).also { input.readFully(it) }
        return AdbMessage(command, arg0, arg1, length, crc, magic, data).also { it.validateOrThrow() }
    }

    private fun write(
        output: OutputStream,
        message: AdbMessage,
    ) {
        output.write(message.toByteArray())
        output.flush()
    }

    private companion object {
        const val REMOTE_ID = 100
    }
}
