package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;
import ca.concordia.filesystem.datastructures.FNode;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tiny on-disk filesystem with explicit FEntry[] and FNode[] arrays.
 *
 * Readers–writer rules for Task 3:
 *  - Many concurrent readers: readFile(), listFiles() use rw.readLock()
 *  - Single writer with mutual exclusion: createFile(), writeFile(), deleteFile() use rw.writeLock()
 *  - Fair lock prevents starvation of writers or readers during heavy traffic.
 *
 * Deadlock prevention:
 *  - Exactly one internal lock (rw); no nested lock ordering issues
 *  - Always unlock in finally blocks
 *  - No callbacks while holding locks; only internal I/O to our own RandomAccessFile
 */
public class FileSystemManager {

    // ======= Test hook for Task 3 demo (optional) =======
    // If > 0, writeFile() will sleep this many milliseconds while holding the write lock,
    // making it easy to see readers block behind a writer during the demo harness.
    public static volatile int TEST_IN_WRITE_DELAY_MS = 0;

    // ---- header constants ----
    private static final int MAGIC = 0x46535632; // "FSV2"
    private static final int HEADER_BYTES = 24;
    private static final int FENTRY_BYTES = 16; // 12 name + 2 size + 2 first
    private static final int FNODE_BYTES  = 4;  // 2 blockIndex + 2 nextIndex

    // ---- persistent config ----
    private final String path;
    private final int totalBytes;
    private final int blockSize;
    private final int maxFiles;
    private final int maxBlocks;

    // ---- locks: many readers, single writer (fair) ----
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);

    // ---- file handle ----
    private final RandomAccessFile disk;

    // ---- in-memory mirrors ----
    private final FEntry[] entries;
    private final FNode[]  fnodes;

    // ---- computed offsets ----
    private final long entriesOff;
    private final long fnodesOff;
    private final long dataOff;

    public FileSystemManager(String diskPath, int totalBytes, int blockSize, int maxFiles, int maxBlocks) {
        if (totalBytes <= 0 || blockSize <= 0 || maxFiles <= 0 || maxBlocks <= 0)
            throw new IllegalArgumentException("All parameters must be positive");

        long need = HEADER_BYTES
                + (long) FENTRY_BYTES * maxFiles
                + (long) FNODE_BYTES  * maxBlocks
                + (long) blockSize    * maxBlocks;
        if (need > totalBytes)
            throw new IllegalArgumentException("totalBytes too small for given BLOCKSIZE/MAXFILES/MAXBLOCKS");

        this.path = diskPath;
        this.totalBytes = totalBytes;
        this.blockSize = blockSize;
        this.maxFiles  = maxFiles;
        this.maxBlocks = maxBlocks;

        this.entries = new FEntry[maxFiles];
        this.fnodes  = new FNode[maxBlocks];

        this.entriesOff = HEADER_BYTES;
        this.fnodesOff  = entriesOff + (long)FENTRY_BYTES * maxFiles;
        this.dataOff    = fnodesOff  + (long)FNODE_BYTES  * maxBlocks;

        try {
            File f = new File(path);
            boolean exists = f.exists();
            this.disk = new RandomAccessFile(f, "rw");
            if (!exists || disk.length() < totalBytes) disk.setLength(totalBytes);

            if (!exists) {
                for (int i = 0; i < maxFiles; i++) entries[i] = new FEntry();
                for (short i = 0; i < maxBlocks; i++) {
                    FNode n = new FNode();
                    n.setBlockIndex(i);
                    n.setNextIndex(FNode.FREE);
                    fnodes[i] = n;
                }
                writeHeader();
                flushAllEntries();
                flushAllFNodes();
                zeroAllData();
            } else {
                if (!tryLoadHeader()) {
                    for (int i = 0; i < maxFiles; i++) entries[i] = new FEntry();
                    for (short i = 0; i < maxBlocks; i++) {
                        FNode n = new FNode();
                        n.setBlockIndex(i);
                        n.setNextIndex(FNode.FREE);
                        fnodes[i] = n;
                    }
                    writeHeader();
                    flushAllEntries();
                    flushAllFNodes();
                    zeroAllData();
                } else {
                    loadAllEntries();
                    loadAllFNodes();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open/initialize disk: " + e.getMessage(), e);
        }
    }

    // ======================== Public API ========================

    public void createFile(String filename) throws Exception {
        rw.writeLock().lock();
        try {
            validateName(filename);
            if (findEntryIndex(filename) >= 0) throw new Exception("file already exists");
            int slot = findFreeEntryIndex();
            if (slot < 0) throw new Exception("no free file entries");
            FEntry e = new FEntry();
            e.setFilename(filename);
            e.setUnsignedSize(0);
            e.setFirstBlock((short)-1);
            entries[slot] = e;
            flushEntry(slot);
        } finally {
            rw.writeLock().unlock();
        }
    }

    public void deleteFile(String filename) throws Exception {
        rw.writeLock().lock();
        try {
            int idx = findEntryIndex(filename);
            if (idx < 0) throw new Exception("file not found");
            FEntry e = entries[idx];

            short head = e.getFirstBlock();
            if (head >= 0) {
                var chain = followChain(head);
                for (short b : chain) zeroBlock(b);
                freeChain(head);
                flushAllFNodes();
            }
            entries[idx] = new FEntry();
            flushEntry(idx);
        } finally {
            rw.writeLock().unlock();
        }
    }

    public void writeFile(String filename, byte[] contents) throws Exception {
        rw.writeLock().lock();
        ArrayList<Short> newly = new ArrayList<>();
        boolean committed = false;
        try {
            // ---- Task 3 hook: simulate a long write while holding the write lock ----
            if (TEST_IN_WRITE_DELAY_MS > 0) {
                try { Thread.sleep(TEST_IN_WRITE_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            int idx = findEntryIndex(filename);
            if (idx < 0) throw new Exception("file not found");
            FEntry e = entries[idx];

            int newSize = Math.min(contents == null ? 0 : contents.length, 0xFFFF);
            int neededBlocks = (newSize == 0) ? 0 : (int)Math.ceil((double)newSize / blockSize);

            short newHead = (short)-1;
            if (neededBlocks > 0) {
                if (countFreeNodes() < neededBlocks) throw new Exception("insufficient free blocks");

                short prev = -1;
                int written = 0;
                for (int i = 0; i < neededBlocks; i++) {
                    short node = allocFreeNode();
                    newly.add(node);
                    fnodes[node].setNextIndex(FNode.END);

                    int chunk = Math.min(blockSize, newSize - written);
                    writeBlock(node, contents, written, chunk);
                    if (chunk < blockSize) zeroTail(node, chunk);
                    written += chunk;

                    if (prev == -1) newHead = node;
                    else fnodes[prev].setNextIndex(node);
                    prev = node;
                }
            }

            flushAllFNodes();
            short oldHead = e.getFirstBlock();
            e.setUnsignedSize(newSize);
            e.setFirstBlock(newHead);
            flushEntry(idx);
            committed = true;

            if (oldHead >= 0) {
                var oldChain = followChain(oldHead);
                for (short b : oldChain) zeroBlock(b);
                freeChain(oldHead);
                flushAllFNodes();
            }
        } catch (Exception ex) {
            if (!committed && !newly.isEmpty()) {
                try {
                    for (short n : newly) {
                        zeroBlock(n);
                        fnodes[n].setNextIndex(FNode.FREE);
                    }
                    flushAllFNodes();
                } catch (IOException ignored) { }
            }
            throw ex;
        } finally {
            rw.writeLock().unlock();
        }
    }

    public byte[] readFile(String filename) throws Exception {
        rw.readLock().lock();
        try {
            int idx = findEntryIndex(filename);
            if (idx < 0) throw new Exception("file not found");
            FEntry e = entries[idx];
            int size = e.getUnsignedSize();
            if (size == 0) return new byte[0];

            short cur = e.getFirstBlock();
            if (cur < 0) throw new Exception("file is corrupt (no head)");

            byte[] out = new byte[size];
            int off = 0;
            while (cur >= 0 && off < size) {
                int chunk = Math.min(blockSize, size - off);
                readBlock(cur, out, off, chunk);
                off += chunk;
                short nx = fnodes[cur].getNextIndex();
                if (nx == FNode.END) break;
                if (nx < 0) throw new IOException("corrupt chain");
                cur = nx;
            }
            return out;
        } finally {
            rw.readLock().unlock();
        }
    }

    public String[] listFiles() {
        rw.readLock().lock();
        try {
            ArrayList<String> names = new ArrayList<>();
            for (FEntry e : entries) if (e != null && !e.isFree()) names.add(e.getFilename());
            return names.toArray(new String[0]);
        } finally {
            rw.readLock().unlock();
        }
    }

    // ======================== helpers & persistence ========================

    private void validateName(String n) throws Exception {
        if (n == null || n.isBlank()) throw new Exception("invalid filename");
        if (n.length() > 11) throw new Exception("filename too long (>11)");
    }

    private int findEntryIndex(String name) {
        for (int i = 0; i < entries.length; i++) {
            FEntry e = entries[i];
            if (e != null && !e.isFree() && name.equals(e.getFilename())) return i;
        }
        return -1;
    }

    private int findFreeEntryIndex() {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null || entries[i].isFree()) return i;
        }
        return -1;
    }

    private int countFreeNodes() {
        int c = 0;
        for (FNode n : fnodes) if (n.getNextIndex() == FNode.FREE) c++;
        return c;
    }

    private short allocFreeNode() throws Exception {
        for (short i = 0; i < fnodes.length; i++) {
            if (fnodes[i].getNextIndex() == FNode.FREE) {
                fnodes[i].setBlockIndex(i);
                fnodes[i].setNextIndex(FNode.END);
                return i;
            }
        }
        throw new Exception("no free blocks");
    }

    private ArrayList<Short> followChain(short head) throws IOException {
        ArrayList<Short> list = new ArrayList<>();
        short cur = head;
        while (cur >= 0) {
            list.add(cur);
            short nx = fnodes[cur].getNextIndex();
            if (nx == FNode.END) break;
            if (nx < 0) throw new IOException("corrupt chain");
            cur = nx;
        }
        return list;
    }

    private void freeChain(short head) {
        short cur = head;
        while (cur >= 0) {
            short nx = fnodes[cur].getNextIndex();
            fnodes[cur].setNextIndex(FNode.FREE);
            if (nx == FNode.END) break;
            cur = nx;
        }
    }

    private void writeHeader() throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(MAGIC);
        bb.putInt(totalBytes);
        bb.putInt(blockSize);
        bb.putInt(maxFiles);
        bb.putInt(maxBlocks);
        bb.putInt(0);
        disk.seek(0);
        disk.write(bb.array());
    }

    private boolean tryLoadHeader() throws IOException {
        if (disk.length() < HEADER_BYTES) return false;
        disk.seek(0);
        byte[] hdr = new byte[HEADER_BYTES];
        int r = disk.read(hdr);
        if (r < HEADER_BYTES) return false;
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        int tB = bb.getInt();
        int bS = bb.getInt();
        int mF = bb.getInt();
        int mB = bb.getInt();
        bb.getInt(); // reserved
        return magic == MAGIC && tB == totalBytes && bS == blockSize && mF == maxFiles && mB == maxBlocks;
    }

    private void flushAllEntries() throws IOException {
        for (int i = 0; i < maxFiles; i++) flushEntry(i);
    }

    private void flushEntry(int i) throws IOException {
        FEntry e = entries[i];
        if (e == null) e = new FEntry();
        long pos = entriesOff + (long)i * FENTRY_BYTES;
        disk.seek(pos);

        byte[] name = new byte[12];
        if (!e.isFree()) {
            byte[] src = e.getFilename().getBytes(StandardCharsets.US_ASCII);
            int n = Math.min(src.length, 11);
            System.arraycopy(src, 0, name, 0, n);
        }
        disk.write(name);

        ByteBuffer mb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        mb.putShort((short)(e.getRawSize() & 0xFFFF));
        mb.putShort(e.getFirstBlock());
        disk.write(mb.array());
    }

    private void loadAllEntries() throws IOException {
        for (int i = 0; i < maxFiles; i++) {
            long pos = entriesOff + (long)i * FENTRY_BYTES;
            disk.seek(pos);
            byte[] name = new byte[12];
            disk.readFully(name);
            byte[] meta = new byte[4];
            disk.readFully(meta);
            ByteBuffer mb = ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN);
            short sz = mb.getShort();
            short first = mb.getShort();

            String nm = new String(name, StandardCharsets.US_ASCII);
            int nul = nm.indexOf(0);
            if (nul >= 0) nm = nm.substring(0, nul);
            nm = nm.trim();

            FEntry e = new FEntry();
            e.setFilename(nm);
            e.setUnsignedSize(Short.toUnsignedInt(sz));
            e.setFirstBlock(first);
            entries[i] = e;
        }
    }

    private void flushAllFNodes() throws IOException {
        disk.seek(fnodesOff);
        ByteBuffer bb = ByteBuffer.allocate(FNODE_BYTES * maxBlocks).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < maxBlocks; i++) {
            short bi = fnodes[i].getBlockIndex();
            short nx = fnodes[i].getNextIndex();
            bb.putShort(bi);
            bb.putShort(nx);
        }
        disk.write(bb.array());
    }

    private void loadAllFNodes() throws IOException {
        disk.seek(fnodesOff);
        byte[] raw = new byte[FNODE_BYTES * maxBlocks];
        disk.readFully(raw);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < maxBlocks; i++) {
            short bi = bb.getShort();
            short nx = bb.getShort();
            FNode n = new FNode();
            n.setBlockIndex(bi);
            n.setNextIndex(nx);
            fnodes[i] = n;
        }
    }

    private void zeroAllData() throws IOException {
        byte[] zeros = new byte[blockSize];
        for (int i = 0; i < maxBlocks; i++) {
            disk.seek(blockPos(i));
            disk.write(zeros);
        }
    }

    private void zeroBlock(int blockIndex) throws IOException {
        byte[] zeros = new byte[blockSize];
        disk.seek(blockPos(blockIndex));
        disk.write(zeros);
    }

    private void zeroTail(int blockIndex, int from) throws IOException {
        if (from < blockSize) {
            disk.seek(blockPos(blockIndex) + from);
            byte[] zeros = new byte[blockSize - from];
            disk.write(zeros);
        }
    }

    private void writeBlock(int blockIndex, byte[] src, int off, int len) throws IOException {
        disk.seek(blockPos(blockIndex));
        disk.write(src, off, len);
    }

    private void readBlock(int blockIndex, byte[] dst, int off, int len) throws IOException {
        disk.seek(blockPos(blockIndex));
        disk.readFully(dst, off, len);
    }

    private long blockPos(int blockIndex) {
        return dataOff + (long) blockIndex * blockSize;
    }
}
