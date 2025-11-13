package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal TCP server wrapper. Adjust the constructor arguments as you like.
 */
public class FileServer {

    private final FileSystemManager fs;
    private final int port;
    private final ExecutorService pool;

    /**
     * @param port       TCP port
     * @param diskPath   backing file path
     * @param totalBytes total disk size (bytes) >= header + entries + fnodes + blocks*blockSize
     * @param blockSize  BLOCKSIZE (bytes per block) > 0
     * @param maxFiles   MAXFILES  > 0
     * @param maxBlocks  MAXBLOCKS > 0
     */
    public FileServer(int port, String diskPath, int totalBytes, int blockSize, int maxFiles, int maxBlocks) {
        this.port = port;
        this.fs = new FileSystemManager(diskPath, totalBytes, blockSize, maxFiles, maxBlocks);
        this.pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on " + port);
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client);
                pool.execute(new ClientHandler(client, fs));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            pool.shutdownNow();
        }
    }
}