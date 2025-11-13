package ca.concordia.server;

/** Centralized knobs for the server, thread pool, and protocol limits. */
public final class ServerConfig {
    public final int port;

    // Thread pool sizing
    public final int coreThreads;
    public final int maxThreads;
    public final int queueCapacity;
    public final long keepAliveMillis;

    // Socket timeouts
    public final int clientSoTimeoutMillis;

    // --- Protocol / error-handling limits ---
    /** Max bytes of hex payload accepted by WRITE after decoding (defensive) */
    public final int maxPayloadBytes;
    /** Max line length we read from client (characters) */
    public final int maxLineLength;
    /** Upper bound on commands processed per connection (prevents abuse) */
    public final int maxCommandsPerConnection;

    public ServerConfig(
            int port,
            int coreThreads,
            int maxThreads,
            int queueCapacity,
            long keepAliveMillis,
            int clientSoTimeoutMillis,
            int maxPayloadBytes,
            int maxLineLength,
            int maxCommandsPerConnection
    ) {
        this.port = port;
        this.coreThreads = coreThreads;
        this.maxThreads = maxThreads;
        this.queueCapacity = queueCapacity;
        this.keepAliveMillis = keepAliveMillis;
        this.clientSoTimeoutMillis = clientSoTimeoutMillis;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxLineLength = maxLineLength;
        this.maxCommandsPerConnection = maxCommandsPerConnection;
    }

    public static ServerConfig sensibleDefaults(int port) {
        int cpu = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new ServerConfig(
                port,
                cpu * 2,       // core size
                2048,          // max threads
                10_000,        // queue size
                30_000L,       // keep-alive ms
                60_000,        // client SO_TIMEOUT ms
                64 * 1024,     // max payload bytes accepted by WRITE
                256 * 1024,    // max line length (chars)
                2000           // max commands per connection
        );
    }
}
