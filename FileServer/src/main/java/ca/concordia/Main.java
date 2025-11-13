package ca.concordia;

import ca.concordia.filesystem.FileSystemManager;
import ca.concordia.server.FileServer;
import ca.concordia.server.ServerConfig;

public class Main {
    public static void main(String[] args) {
        // ==== Filesystem sizing ====
        String DISK     = "filesystem.dat";
        int BLOCKSIZE   = 256;
        int MAXFILES    = 128;
        int MAXBLOCKS   = 1024;

        int totalBytes  = 24
                        + MAXFILES * 16
                        + MAXBLOCKS * 4
                        + MAXBLOCKS * BLOCKSIZE;

        FileSystemManager fs = new FileSystemManager(
                DISK, totalBytes, BLOCKSIZE, MAXFILES, MAXBLOCKS);

        // ==== Server config ====
        int PORT = 12345;
        ServerConfig cfg = ServerConfig.sensibleDefaults(PORT);

        new FileServer(fs, cfg).start();
    }
}
