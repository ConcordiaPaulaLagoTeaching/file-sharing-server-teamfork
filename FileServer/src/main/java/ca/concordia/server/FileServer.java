package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FileServer {

    private final FileSystemManager fs;
    private final ServerConfig cfg;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ThreadPoolExecutor pool;

    public FileServer(FileSystemManager fs, ServerConfig cfg) {
        this.fs = Objects.requireNonNull(fs, "fs");
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    public void start() {
        if (running) return;
        running = true;

        // Named thread factory for easier debugging
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger c = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "client-worker-" + c.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        // Bounded queue + backpressure to avoid OOM under load
        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(cfg.queueCapacity);

        pool = new ThreadPoolExecutor(
                cfg.coreThreads,
                cfg.maxThreads,
                cfg.keepAliveMillis, TimeUnit.MILLISECONDS,
                q,
                tf,
                new ThreadPoolExecutor.AbortPolicy() // we'll handle rejection at submit time
        );
        pool.allowCoreThreadTimeOut(true);

        try (ServerSocket ss = new ServerSocket(cfg.port)) {
            this.serverSocket = ss;
            ss.setReuseAddress(true);
            System.out.println("[Server] Listening on port " + cfg.port +
                    " | pool " + cfg.coreThreads + "/" + cfg.maxThreads +
                    " | queue " + cfg.queueCapacity);

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "server-shutdown"));

            // Accept loop
            while (running) {
                try {
                    Socket client = ss.accept();
                    client.setSoTimeout(cfg.clientSoTimeoutMillis);

                    try {
                        pool.execute(new ClientHandler(client, fs));
                    } catch (RejectedExecutionException rex) {
                        // Backpressure: pool saturated – respond and close quickly
                        ClientHandler.respondAndClose(client, "ERROR server busy, try again later");
                    }
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[Server] Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}

        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[Server] Shutdown complete");
    }
}
