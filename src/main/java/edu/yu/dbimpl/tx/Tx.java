package edu.yu.dbimpl.tx;

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.file.BlockId;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.concurrency.ConcurrencyMgr;
import edu.yu.dbimpl.tx.recovery.RecoveryMgr;

import java.util.HashMap;
import java.util.Map;

public class Tx implements TxBase {

    private final int txnum;
    private Status status;
    private final BufferMgrBase bm;
    private final FileMgrBase fm;
    private final ConcurrencyMgr cm;
    private final RecoveryMgr rm;

    private final Map<BlockIdBase, BufferBase> pinnedBuffers;

    public Tx(int txnum, FileMgrBase fm, LogMgrBase lm, BufferMgrBase bm, TxMgrBase txMgr) {
        this.status = Status.ACTIVE;
        this.txnum = txnum;
        this.fm = fm;
        this.bm = bm;
        this.pinnedBuffers = new HashMap<>();
        this.cm = new ConcurrencyMgr(txMgr, txnum);
        this.rm = new RecoveryMgr(this, lm, bm);
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public int txnum() {
        return txnum;
    }

    @Override
    public void commit() {
        ensureActive();
        // Change the transaction's status to COMMITTING
        status = Status.COMMITTING;
        // Flush all modified buffers and write a commit record to the log
        rm.commit();
        // Release all locks
        cm.release();
        // Unpin all buffers
        unpinAll();
        // Change the transaction's status to COMMITTED
        status = Status.COMMITTED;
    }

    @Override
    public void rollback() {
        ensureActive();
        // Change the transaction's status to ROLLING_BACK
        status = Status.ROLLING_BACK;
        // Undo all modified values and write rollback record
        rm.rollback();
        // Release all locks
        cm.release();
        // Unpin all buffers
        unpinAll();
        // Change the transaction's status to ROLLED_BACK
        status = Status.ROLLED_BACK;
    }

    @Override
    public void recover() {
        ensureActive();
        // Change the transaction's status to RECOVERING
        status = Status.RECOVERING;
        // Flush all modified buffers
        bm.flushAll(txnum);
        // Rollback all uncommitted transactions
        rm.recover();
        // Change the transaction's status to RECOVERED
        status = Status.RECOVERED;
    }

    @Override
    public void pin(BlockIdBase blk) {
        if (blk == null) {
            throw new IllegalArgumentException("BlockId cannot be null");
        }
        ensureActive();
        // If the block is not already pinned, pin it and add it to the map
        if (!pinnedBuffers.containsKey(blk)) {
            BufferBase buff = bm.pin(blk);
            pinnedBuffers.put(blk, buff);
        }
    }

    @Override
    public void unpin(BlockIdBase blk) {
        ensureActive();
        // Check if the block is pinned by this transaction
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalArgumentException("Block is not pinned by this transaction");
        }
        // Unpin the buffer and remove it from the map
        bm.unpin(buff);
        pinnedBuffers.remove(blk);
    }

    @Override
    public int getInt(BlockIdBase blk, int offset) {
        ensureActive();
        // Acquire an SLock on the block
        cm.sLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // Return the integer value at the specified offset
        return buff.contents().getInt(offset);
    }

    @Override
    public boolean getBoolean(BlockIdBase blk, int offset) {
        ensureActive();
        // Acquire an SLock on the block
        cm.sLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // Return the boolean value at the specified offset
        return buff.contents().getBoolean(offset);
    }

    @Override
    public double getDouble(BlockIdBase blk, int offset) {
        ensureActive();
        // Acquire an SLock on the block
        cm.sLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // Return the double value at the specified offset
        return buff.contents().getDouble(offset);
    }

    @Override
    public String getString(BlockIdBase blk, int offset) {
        ensureActive();
        // Acquire an SLock on the block
        cm.sLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // Return the string value at the specified offset
        return buff.contents().getString(offset);
    }

    @Override
    public byte[] getBytes(BlockIdBase blk, int offset) {
        ensureActive();
        // Acquire an SLock on the block
        cm.sLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // Return the byte array value at the specified offset
        return buff.contents().getBytes(offset);
    }

    @Override
    public void setInt(BlockIdBase blk, int offset, int val, boolean okToLog) {
        ensureActive();
        // Acquire an XLock on the block
        cm.xLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // If logging is enabled, create an update log record
        int lsn = -1;
        if (okToLog) {
            lsn = rm.setInt(buff, offset, val);
        }
        // Set the modified value in the buffer and mark it as modified
        buff.contents().setInt(offset, val);
        buff.setModified(txnum, lsn);
    }

    @Override
    public void setBoolean(BlockIdBase blk, int offset, boolean val, boolean okToLog) {
        ensureActive();
        // Acquire an XLock on the block
        cm.xLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // If logging is enabled, create an update log record
        int lsn = -1;
        if (okToLog) {
            lsn = rm.setBoolean(buff, offset, val);
        }
        // Set the modified value in the buffer and mark it as modified
        buff.contents().setBoolean(offset, val);
        buff.setModified(txnum, lsn);
    }

    @Override
    public void setDouble(BlockIdBase blk, int offset, double val, boolean okToLog) {
        ensureActive();
        // Acquire an XLock on the block
        cm.xLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // If logging is enabled, create an update log record
        int lsn = -1;
        if (okToLog) {
            lsn = rm.setDouble(buff, offset, val);
        }
        // Set the modified value in the buffer and mark it as modified
        buff.contents().setDouble(offset, val);
        buff.setModified(txnum, lsn);
    }

    @Override
    public void setString(BlockIdBase blk, int offset, String val, boolean okToLog) {
        ensureActive();
        // Acquire an XLock on the block
        cm.xLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // If logging is enabled, create an update log record
        int lsn = -1;
        if (okToLog) {
            lsn = rm.setString(buff, offset, val);
        }
        // Set the modified value in the buffer and mark it as modified
        buff.contents().setString(offset, val);
        buff.setModified(txnum, lsn);
    }

    @Override
    public void setBytes(BlockIdBase blk, int offset, byte[] val, boolean okToLog) {
        ensureActive();
        // Acquire an XLock on the block
        cm.xLock(blk);
        // Get the buffer for the block
        BufferBase buff = pinnedBuffers.get(blk);
        if (buff == null) {
            throw new IllegalStateException("Block is not pinned by this transaction");
        }
        // If logging is enabled, create an update log record
        int lsn = -1;
        if (okToLog) {
            lsn = rm.setBytes(buff, offset, val);
        }
        // Set the modified value in the buffer and mark it as modified
        buff.contents().setBytes(offset, val);
        buff.setModified(txnum, lsn);
    }

    @Override
    public int size(String filename) {
        ensureActive();
        // Create a virtual block for file-level locking
        BlockIdBase dummyBlk = new BlockId(filename, Integer.MAX_VALUE);
        // Acquire an SLock on the virtual block
        cm.sLock(dummyBlk);
        return fm.length(filename);
    }

    @Override
    public BlockIdBase append(String filename) {
        ensureActive();
        // Create a virtual block for file-level locking
        BlockIdBase dummyBlk = new BlockId(filename, Integer.MAX_VALUE);
        // Acquire an XLock on the virtual block
        cm.xLock(dummyBlk);
        return fm.append(filename);
    }

    @Override
    public int blockSize() {
        return fm.blockSize();
    }

    @Override
    public int availableBuffs() {
        ensureActive();
        return bm.available();
    }

    // Helper method to ensure the transaction is active before performing any operations
    private void ensureActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Transaction is not active");
        }
    }

    // Helper method to unpin all buffers pinned by this transaction
    private void unpinAll() {
        for (BufferBase buff : pinnedBuffers.values()) {
            bm.unpin(buff);
        }
        pinnedBuffers.clear();
    }

    @Override
    public String toString() {
        return "Tx{txnum=" + txnum + ", status=" + status + "}";
    }
}
