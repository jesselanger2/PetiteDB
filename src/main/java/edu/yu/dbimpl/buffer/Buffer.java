package edu.yu.dbimpl.buffer;

import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.file.Page;
import edu.yu.dbimpl.file.PageBase;
import edu.yu.dbimpl.log.LogMgrBase;

import java.util.Objects;

public class Buffer extends BufferBase {

    private final FileMgrBase fm;
    private final LogMgrBase lm;
    private final PageBase page;
    private BlockIdBase blk = null;
    private int pinCount = 0;
    private boolean isDirty = false;
    private int modifyingTx = -1; // -1 indicates no transaction
    private int lsn = -1; // -1 indicates no log record
    private boolean referenceBit = false; // For CLOCK eviction policy

    public Buffer(FileMgrBase fileMgr, LogMgrBase logMgr) {
        super(fileMgr, logMgr);
        this.fm = fileMgr;
        this.lm = logMgr;
        this.page = new Page(fm.blockSize());
    }

    @Override
    public PageBase contents() {
        return page;
    }

    @Override
    public BlockIdBase block() {
        return blk;
    }

    @Override
    public void setModified(int txnum, int lsn) {
        if (txnum < 0) {
            throw new IllegalArgumentException("Transaction number must be non-negative");
        }
        this.isDirty = true;
        this.modifyingTx = txnum;
        // Only set the LSN if it's a valid, new log record
        if (lsn >= 0) {
            this.lsn = lsn;
        }
    }

    @Override
    public boolean isPinned() {
        return pinCount > 0;
    }

    /**
     * Flushes the buffer to disk if it is dirty.
     */
    void flush() {
        if (isDirty) {
            if (lsn >= 0) {
                lm.flush(lsn);
            }
            fm.write(blk, page);
            isDirty = false;
            modifyingTx = -1;
            lsn = -1;
        }
    }

    /**
     * Assigns this buffer to a new disk block.
     * This method reads the new block's contents into the page and
     * resets the buffer's state.
     *
     * @param b the block to assign this buffer to
     */
    void assignToBlock(BlockIdBase b) {
        fm.read(b, page);
        this.blk = b;
        this.isDirty = false;
        this.modifyingTx = -1;
        this.lsn = -1;
        this.referenceBit = true; // Set reference bit when assigned to a block
    }

    int getModifyingTx() {
        return modifyingTx;
    }

    void incrementPinCount() {
        this.pinCount++;
        this.referenceBit = true; // Set reference bit when pinned
    }

    void decrementPinCount() {
        if (pinCount > 0) {
            this.pinCount--;
        }
    }

    boolean getReferenceBit() {
        return referenceBit;
    }

    void clearReferenceBit() {
        this.referenceBit = false;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Buffer buffer)) return false;
        return pinCount == buffer.pinCount &&
                isDirty == buffer.isDirty &&
                modifyingTx == buffer.modifyingTx &&
                lsn == buffer.lsn &&
                Objects.equals(fm, buffer.fm) &&
                Objects.equals(lm, buffer.lm) &&
                Objects.equals(page, buffer.page) &&
                Objects.equals(blk, buffer.blk);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fm, lm, page, blk, pinCount, isDirty, modifyingTx, lsn);
    }

    @Override
    public String toString() {
        return "Buffer{" +
                "pinCount=" + pinCount +
                ", isDirty=" + isDirty +
                ", modifyingTx=" + modifyingTx +
                ", lsn=" + lsn +
                '}';
    }
}
