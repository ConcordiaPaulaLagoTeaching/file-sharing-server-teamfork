package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileSystemManager {
    private final int MAXFILES = 5;
    private final int MAXBLOCKS = 10;
    private static FileSystemManager instance; // singleton
    private final RandomAccessFile disk;
    private final ReentrantLock globalLock = new ReentrantLock();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final int BLOCK_SIZE = 128; // Example block size

    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks

    // ==================== CONSTRUCTOR ====================
    public FileSystemManager(String filename, int totalSize) {
        if (instance == null) {
            try {
                // 1. Create or open the disk file
                disk = new RandomAccessFile(filename, "rw");

                // 2. Ensure the file has the correct size (totalSize bytes)
                if (disk.length() < totalSize) {
                    disk.setLength(totalSize);
                }

                // 3. Initialize metadata
                inodeTable = new FEntry[MAXFILES];
                freeBlockList = new boolean[MAXBLOCKS];
                for (int i = 0; i < MAXBLOCKS; i++) {
                    freeBlockList[i] = true; // all blocks free
                }

                instance = this;
                System.out.println("File system initialized: " + filename);
                System.out.println("Disk size: " + totalSize + " bytes");
                System.out.println("Max files: " + MAXFILES + ", Max blocks: " + MAXBLOCKS);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize FileSystemManager: " + e.getMessage(), e);
            }
        } else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
    }

    // ==================== CREATE FILE ====================
    public void createFile(String fileName) throws Exception {
        lock.writeLock().lock();
        try {
            if (fileName.length() > 11)
                throw new Exception("ERROR: filename too large");

            // Check if file already exists
            for (FEntry entry : inodeTable)
                if (entry != null && fileName.equals(entry.getFilename()))
                    throw new Exception("ERROR: file already exists");

            // Find a free inode slot
            for (int i = 0; i < MAXFILES; i++) {
                if (inodeTable[i] == null) {
                    inodeTable[i] = new FEntry(fileName, (short) 0, (short) -1);
                    System.out.println("[CREATE] File created: " + fileName);
                    return;
                }
            }

            throw new Exception("ERROR: maximum file limit reached");
        } finally {
            lock.writeLock().unlock();

        }
    }

    // ==================== WRITE FILE ====================
    public void writeFile(String fileName, byte[] data) throws Exception {
         lock.writeLock().lock();
        try {
            FEntry entry = findEntry(fileName);
            if (entry == null) throw new Exception("ERROR: file does not exist");

            int requiredBlocks = (int) Math.ceil(data.length / (double) BLOCK_SIZE);
            if (countFreeBlocks() < requiredBlocks)
                throw new Exception("ERROR: not enough space to write file");

            // Clear old blocks if file already had content
            clearBlocks(entry);

            // Write new data
            int offset = 0;
            for (int i = 0; i < requiredBlocks; i++) {
                int freeBlock = getFreeBlock();
                freeBlockList[freeBlock] = false;
                entry.addBlock((short) freeBlock);

                int toWrite = Math.min(BLOCK_SIZE, data.length - offset);
                disk.seek((long) freeBlock * BLOCK_SIZE);
                disk.write(data, offset, toWrite);
                offset += toWrite;
            }

            // Update metadata
            if (!entry.getBlockChain().isEmpty()) {
                entry.setFirstBlock(entry.getBlockChain().getFirst());
            }
            entry.setSize(data.length);

            System.out.println("[WRITE] " + fileName + " (" + data.length + " bytes, " + requiredBlocks + " blocks)");
        } finally {
            lock.writeLock().unlock();

        }
    }

    // ==================== READ FILE ====================
    public byte[] readFile(String fileName) throws Exception {
        lock.readLock().lock();
        try {
            FEntry entry = findEntry(fileName);
            if (entry == null) throw new Exception("ERROR: file does not exist");
            if (entry.getFirstBlock() == -1) throw new Exception("ERROR: file is empty");

            byte[] buffer = new byte[entry.getSize()];
            int bytesRead = 0;

            for (short block : entry.getBlockChain()) {
                int toRead = Math.min(BLOCK_SIZE, buffer.length - bytesRead);
                disk.seek((long) block * BLOCK_SIZE);
                disk.read(buffer, bytesRead, toRead);
                bytesRead += toRead;
            }

            System.out.println("[READ] " + fileName + " (" + buffer.length + " bytes)");
            return buffer;
        } finally {
            lock.readLock().unlock();

        }
    }

    // ==================== DELETE FILE ====================
    public void deleteFile(String fileName) throws Exception {
        lock.writeLock().lock();

        try {
            for (int i = 0; i < MAXFILES; i++) {
                FEntry entry = inodeTable[i];
                if (entry != null && fileName.equals(entry.getFilename())) {
                    clearBlocks(entry);
                    inodeTable[i] = null;
                    System.out.println("[DELETE] " + fileName + " deleted.");
                    return;
                }
            }
            throw new Exception("ERROR: file " + fileName + " does not exist");
        } finally {
            lock.writeLock().unlock();

        }
    }

    // ==================== LIST FILES ====================
    public String[] listFiles() {
        lock.readLock().lock();
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (FEntry e : inodeTable)
                if (e != null)
                    names.add(e.getFilename());
            return names.toArray(new String[0]);
        } finally {
            lock.readLock().unlock();

        }
    }

    // ==================== HELPERS ====================
    private FEntry findEntry(String name) {
        for (FEntry e : inodeTable)
            if (e != null && e.getFilename().equals(name)) return e;
        return null;
    }

    private int countFreeBlocks() {
        int c = 0;
        for (boolean b : freeBlockList) if (b) c++;
        return c;
    }

    private int getFreeBlock() throws Exception {
        for (int i = 0; i < MAXBLOCKS; i++)
            if (freeBlockList[i]) return i;
        throw new Exception("No free blocks available");
    }

    private void clearBlocks(FEntry entry) throws Exception {
        for (short block : entry.getBlockChain()) {
            freeBlockList[block] = true;

            // Overwrite with zeroes for security
            disk.seek((long) block * BLOCK_SIZE);
            byte[] zeros = new byte[BLOCK_SIZE];
            disk.write(zeros);
        }

        entry.clearBlocks();
    }
}
