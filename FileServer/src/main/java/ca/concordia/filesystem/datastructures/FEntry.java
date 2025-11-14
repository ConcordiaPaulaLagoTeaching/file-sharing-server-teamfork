package ca.concordia.filesystem.datastructures;

public class FEntry {
    private String filename;     // empty => free slot
    private short size;          // store as short; interpret with Short.toUnsignedInt
    private short firstBlock;    // -1 if none

    public FEntry() {
        this.filename = "";
        this.size = 0;
        this.firstBlock = -1;
    }

    public String getFilename() { return filename; }
    public int getUnsignedSize() { return Short.toUnsignedInt(size); }
    public short getRawSize() { return size; }
    public short getFirstBlock() { return firstBlock; }
    public boolean isFree() { return filename == null || filename.isEmpty(); }

    public void setFilename(String name) {
        if (name == null) name = "";
        if (name.length() > 11) throw new IllegalArgumentException("filename > 11 chars");
        this.filename = name;
    }
    public void setUnsignedSize(int sz) {
        if (sz < 0 || sz > 0xFFFF) throw new IllegalArgumentException("size out of range (0..65535)");
        this.size = (short) (sz & 0xFFFF);
    }
    public void setFirstBlock(short fb) { this.firstBlock = fb; }

    @Override public String toString() {
        return "FEntry{'" + filename + "', size=" + getUnsignedSize() + ", first=" + firstBlock + "}";
    }
}
