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

        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger c = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "client-worker-" + c.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(cfg.queueCapacity);

        pool = new ThreadPoolExecutor(
                cfg.coreThreads,
                cfg.maxThreads,
                cfg.keepAliveMillis, TimeUnit.MILLISECONDS,
                q,
                tf,
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.allowCoreThreadTimeOut(true);

        try (ServerSocket ss = new ServerSocket(cfg.port)) {
            this.serverSocket = ss;
            ss.setReuseAddress(true);
            System.out.println("[Server] Listening on port " + cfg.port +
                    " | pool " + cfg.coreThreads + "/" + cfg.maxThreads +
                    " | queue " + cfg.queueCapacity);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "server-shutdown"));

            while (running) {
                try {
                    Socket client = ss.accept();
                    client.setSoTimeout(cfg.clientSoTimeoutMillis);

                    try {
                        // pass cfg so ClientHandler enforces error-handling limits
                        pool.execute(new ClientHandler(client, fs, cfg));
                    } catch (RejectedExecutionException rex) {
                        ClientHandler.respondAndClose(client, "ERROR server busy, try again later");
                    }
                } catch (IOException e) {
                    if (running) System.err.println("[Server] Accept error: " + e.getMessage());
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
