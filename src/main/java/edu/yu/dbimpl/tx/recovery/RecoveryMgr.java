package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.TxBase;

import java.util.*;

public class RecoveryMgr extends RecoveryMgrBase {

    private final LogMgrBase logMgr;
    private final BufferMgrBase bufferMgr;
    private final int txnum;

    public RecoveryMgr(TxBase tx, LogMgrBase logMgr, BufferMgrBase bufferMgr) {
        super(tx, logMgr, bufferMgr);
        this.logMgr = logMgr;
        this.bufferMgr = bufferMgr;
        this.txnum = tx.txnum();
        // Write START record
        writeStartRecord();
    }

    @Override
    public void commit() {
        // Flush all modified buffers
        bufferMgr.flushAll(txnum);
        // Write commit record
        int lsn = writeCommitRecord();
        // Flush log to ensure commit record is on disk
        logMgr.flush(lsn);
    }

    @Override
    public void rollback() {
        // Undo all changes made by this transaction
        doRollback();
        // Flush all modified buffers
        bufferMgr.flushAll(txnum);
        // Write rollback record
        int lsn = writeRollbackRecord();
        // Flush log
        logMgr.flush(lsn);
    }

    @Override
    public void recover() {
        // Flush all modified buffers first
        doRecover();
        // Flush all modified buffers to ensure undone changes are persisted
        bufferMgr.flushAll(txnum);
        // Write checkpoint record
        writeCheckpointRecord();
    }

    @Override
    public int setInt(BufferBase buff, int offset, int newval) {
        int oldval = buff.contents().getInt(offset);
        BlockIdBase blk = buff.block();
        return writeSetIntRecord(blk, offset, oldval);
    }

    @Override
    public int setBoolean(BufferBase buff, int offset, boolean newval) {
        boolean oldval = buff.contents().getBoolean(offset);
        BlockIdBase blk = buff.block();
        return writeSetBooleanRecord(blk, offset, oldval);
    }

    @Override
    public int setDouble(BufferBase buff, int offset, double newval) {
        double oldval = buff.contents().getDouble(offset);
        BlockIdBase blk = buff.block();
        return writeSetDoubleRecord(blk, offset, oldval);
    }

    @Override
    public int setString(BufferBase buff, int offset, String newval) {
        String oldval = buff.contents().getString(offset);
        BlockIdBase blk = buff.block();
        return writeSetStringRecord(blk, offset, oldval);
    }

    @Override
    public int setBytes(BufferBase buff, int offset, byte[] newval) {
        byte[] oldval = buff.contents().getBytes(offset);
        BlockIdBase blk = buff.block();
        return writeSetBytesRecord(blk, offset, oldval);
    }

    // Private helper methods

    /**
     * Rollback this transaction by undoing all its changes.
     *
     * Scans the log backward from the most recent record, undoing all
     * update operations until the START record is found.
     */
    private void doRollback() {
        Iterator<byte[]> iter = logMgr.iterator();
        while (iter.hasNext()) {
            byte[] bytes = iter.next();
            LogRecord rec = LogRecord.createLogRecord(bytes);

            if (rec.txNumber() == txnum) {
                if (rec.op() == LogRecord.START) {
                    // Reached the start of this transaction
                    return;
                } else if (rec.op() >= LogRecord.SETINT && rec.op() <= LogRecord.SETBYTES) {
                    // Undo this operation directly via buffer manager
                    undoLogRecord(rec);
                }
            }
        }
    }

    /**
     * Recover the database by undoing all uncommitted transactions.
     *
     * This is called on system startup. It scans the entire log backward,
     * identifying committed and rolled-back transactions, then undoing
     * all other transactions.
     *
     * Uses Undo-Only recovery: no redo phase needed because commits
     * flush all buffers to disk.
     */
    private void doRecover() {
        Set<Integer> committedTxs = new HashSet<>();
        Set<Integer> rolledBackTxs = new HashSet<>();

        // Stage 1: Undo - scan backward through entire log
        Iterator<byte[]> iter = logMgr.iterator();
        while (iter.hasNext()) {
            byte[] bytes = iter.next();
            LogRecord rec = LogRecord.createLogRecord(bytes);

            if (rec.op() == LogRecord.COMMIT) {
                committedTxs.add(rec.txNumber());
            } else if (rec.op() == LogRecord.ROLLBACK) {
                rolledBackTxs.add(rec.txNumber());
            } else if (rec.op() >= LogRecord.SETINT && rec.op() <= LogRecord.SETBYTES) {
                // If this transaction is not committed or rolled back, undo it
                if (!committedTxs.contains(rec.txNumber()) &&
                        !rolledBackTxs.contains(rec.txNumber())) {
                    undoLogRecord(rec);
                }
            }
        }
    }

    /**
     * Undo a single log record by directly manipulating the buffer.
     *
     * This method pins the buffer, applies the undo operation, marks it modified,
     * and unpins it. This is used during rollback and recovery.
     *
     * @param rec the log record to undo
     */
    private void undoLogRecord(LogRecord rec) {
        BlockIdBase blk = rec.getBlock();
        if (blk == null) {
            // This log record doesn't have a block
            return;
        }
        // Pin the buffer for this block
        BufferBase buff = bufferMgr.pin(blk);
        try {
            // Apply the undo directly to the buffer's page
            rec.undo(buff);
            // Mark as modified but don't log this operation
            buff.setModified(txnum, -1);
        } finally {
            // Unpin the buffer
            bufferMgr.unpin(buff);
        }
    }

    private void writeStartRecord() {
        LogRecord rec = new StartLogRecord(txnum);
        logMgr.append(rec.toBytes());
    }

    private int writeCommitRecord() {
        LogRecord rec = new CommitLogRecord(txnum);
        return logMgr.append(rec.toBytes());
    }

    private int writeRollbackRecord() {
        LogRecord rec = new RollbackLogRecord(txnum);
        return logMgr.append(rec.toBytes());
    }

    private void writeCheckpointRecord() {
        LogRecord rec = new CheckpointLogRecord();
        int lsn = logMgr.append(rec.toBytes());
        logMgr.flush(lsn);
    }

    private int writeSetIntRecord(BlockIdBase blk, int offset, int oldval) {
        LogRecord rec = new SetIntLogRecord(txnum, blk, offset, oldval);
        return logMgr.append(rec.toBytes());
    }

    private int writeSetBooleanRecord(BlockIdBase blk, int offset, boolean oldval) {
        LogRecord rec = new SetBooleanLogRecord(txnum, blk, offset, oldval);
        return logMgr.append(rec.toBytes());
    }

    private int writeSetDoubleRecord(BlockIdBase blk, int offset, double oldval) {
        LogRecord rec = new SetDoubleLogRecord(txnum, blk, offset, oldval);
        return logMgr.append(rec.toBytes());
    }

    private int writeSetStringRecord(BlockIdBase blk, int offset, String oldval) {
        LogRecord rec = new SetStringLogRecord(txnum, blk, offset, oldval);
        return logMgr.append(rec.toBytes());
    }

    private int writeSetBytesRecord(BlockIdBase blk, int offset, byte[] oldval) {
        LogRecord rec = new SetBytesLogRecord(txnum, blk, offset, oldval);
        return logMgr.append(rec.toBytes());
    }
}