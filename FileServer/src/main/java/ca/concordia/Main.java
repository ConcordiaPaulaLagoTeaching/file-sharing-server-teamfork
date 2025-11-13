package ca.concordia;

import ca.concordia.server.FileServer;

public class Main {
    public static void main(String[] args) {
        // Example configuration. Change freely — the FS code works with any positive valid values.
        int PORT        = 12345;
        String DISK     = "filesystem.dat";
        int BLOCKSIZE   = 256;   // bytes
        int MAXFILES    = 128;   // entries
        int MAXBLOCKS   = 512;   // data blocks

        // totalBytes must fit header + entries + fnodes + data
        int totalBytes  = 24                                  // header
                        + MAXFILES * 16                       // entries
                        + MAXBLOCKS * 4                       // fnodes
                        + MAXBLOCKS * BLOCKSIZE;              // data

        FileServer server = new FileServer(PORT, DISK, totalBytes, BLOCKSIZE, MAXFILES, MAXBLOCKS);
        server.start();
    }
}