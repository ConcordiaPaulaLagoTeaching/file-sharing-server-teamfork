package ca.concordia;

import ca.concordia.filesystem.FileSystemManager;
import ca.concordia.server.FileServer;
import ca.concordia.server.ServerConfig;

public class Main {
    public static void main(String[] args) {
        // ==== Filesystem sizing (Task 1 parameters, still fully flexible) ====
        String DISK     = "filesystem.dat";
        int BLOCKSIZE   = 256;   // bytes per block
        int MAXFILES    = 128;   // number of file entries
        int MAXBLOCKS   = 1024;  // number of data blocks

        int totalBytes  = 24                                  // header
                        + MAXFILES * 16                       // entries
                        + MAXBLOCKS * 4                       // fnodes
                        + MAXBLOCKS * BLOCKSIZE;              // data

        FileSystemManager fs = new FileSystemManager(
                DISK, totalBytes, BLOCKSIZE, MAXFILES, MAXBLOCKS);

        // ==== Multithreaded server config (Task 2) ====
        int PORT = 12345;
        ServerConfig cfg = ServerConfig.sensibleDefaults(PORT);

        // Start the multithreaded server (acceptor thread + worker pool)
        new FileServer(fs, cfg).start();
    }
}
