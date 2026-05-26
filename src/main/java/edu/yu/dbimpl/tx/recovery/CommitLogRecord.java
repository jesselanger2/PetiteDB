package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;

import java.nio.ByteBuffer;

/**
 * A COMMIT log record marks the successful completion of a transaction.
 * When this record is flushed to disk, the transaction is durable.
 */
public class CommitLogRecord implements LogRecord {

    private final int txnum;

    public CommitLogRecord(int txnum) {
        this.txnum = txnum;
    }

    @Override
    public int op() {
        return COMMIT;
    }

    @Override
    public int txNumber() {
        return txnum;
    }

    @Override
    public void undo(BufferBase buff) {
        // Commit records don't need undo
    }

    @Override
    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES * 2);
        bb.putInt(COMMIT);
        bb.putInt(txnum);
        return bb.array();
    }

    static CommitLogRecord fromBytes(ByteBuffer bb) {
        int txnum = bb.getInt();
        return new CommitLogRecord(txnum);
    }

    @Override
    public String toString() {
        return "COMMIT <" + txnum + ">";
    }
}