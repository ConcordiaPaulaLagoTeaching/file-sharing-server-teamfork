package ca.concordia.filesystem.datastructures;

public class FNode {
    public static final short FREE = (short) -1;
    public static final short END  = (short) -2;

    private short blockIndex;
    private short nextIndex;

    public FNode(short blockIndex, short nextIndex) {
        this.blockIndex = blockIndex;
        this.nextIndex = nextIndex;
    }
    public FNode() {
        this.blockIndex = -1;
        this.nextIndex = FREE;
    }

    public short getBlockIndex() { return blockIndex; }
    public short getNextIndex()  { return nextIndex;  }

    public void setBlockIndex(short i) { this.blockIndex = i; }
    public void setNextIndex(short n)  { this.nextIndex  = n; }

    public boolean isFree() { return nextIndex == FREE; }
}
