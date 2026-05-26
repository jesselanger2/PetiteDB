package edu.yu.dbimpl.log;

import edu.yu.dbimpl.file.*;

import java.util.Iterator;
import java.util.NoSuchElementException;

import static edu.yu.dbimpl.log.LogMgr.LAST_POS_POINTER;

/**
 * A class that implements reverse iteration over log records.
 * It is not thread-safe and is intended for single-threaded recovery.
 */
public class LogIterator implements Iterator<byte[]> {

    private final FileMgrBase fm;
    private BlockIdBase currentBlk;
    private final PageBase page;
    private int currentRecPosition;

    public LogIterator(FileMgrBase fm, BlockIdBase startBlk) {
        this.fm = fm;
        this.currentBlk = startBlk;
        this.page = new Page(fm.blockSize());
        moveToBlock(currentBlk);
    }

    @Override
    public boolean hasNext() {
        // True if there's another record in this block, or another block to move to.
        return currentRecPosition < fm.blockSize() || currentBlk.number() > 0;
    }

    @Override
    public byte[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more log records");
        }
        // If we've finished the current block, move to the previous one.
        if (currentRecPosition >= fm.blockSize()) {
            moveToBlock(new BlockId(currentBlk.fileName(), currentBlk.number() - 1));
        }
        byte[] rec = page.getBytes(currentRecPosition);
        // Move the pointer to the start of the *next* record
        currentRecPosition += Integer.BYTES + rec.length;
        return rec;
    }

    /**
     * Moves to the specified block and reads it into the page.
     * @param blk
     */
    private void moveToBlock(BlockIdBase blk) {
        currentBlk = blk;
        fm.read(currentBlk, page);
        // Set the record position to the start of the last record in this block
        currentRecPosition = page.getInt(LAST_POS_POINTER);
    }
}
