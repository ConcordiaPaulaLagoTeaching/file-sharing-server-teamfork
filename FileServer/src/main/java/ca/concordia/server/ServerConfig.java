package ca.concordia.server;

/** Centralized knobs for the server and thread pool. */
public final class ServerConfig {
    public final int port;

    // Thread pool sizing for thousands of clients (tunable)
    public final int coreThreads;
    public final int maxThreads;
    public final int queueCapacity;
    public final long keepAliveMillis;

    // Socket timeouts (defensive)
    public final int clientSoTimeoutMillis;

    public ServerConfig(
            int port,
            int coreThreads,
            int maxThreads,
            int queueCapacity,
            long keepAliveMillis,
            int clientSoTimeoutMillis
    ) {
        this.port = port;
        this.coreThreads = coreThreads;
        this.maxThreads = maxThreads;
        this.queueCapacity = queueCapacity;
        this.keepAliveMillis = keepAliveMillis;
        this.clientSoTimeoutMillis = clientSoTimeoutMillis;
    }

    public static ServerConfig sensibleDefaults(int port) {
        int cpu = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new ServerConfig(
                port,
                cpu * 2,          // core
                2048,              // max threads: supports thousands of concurrent clients
                10_000,            // pending tasks
                30_000L,           // keep-alive for idle threads (ms)
                60_000             // client read timeout (ms)
        );
    }
}
