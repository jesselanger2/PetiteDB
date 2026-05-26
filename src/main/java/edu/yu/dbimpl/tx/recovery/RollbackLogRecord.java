package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.tx.TxBase;

import java.nio.ByteBuffer;

/**
 * A ROLLBACK log record marks that a transaction was rolled back.
 * This indicates the transaction's changes were undone.
 */
public class RollbackLogRecord implements LogRecord {

    private final int txnum;

    public RollbackLogRecord(int txnum) {
        this.txnum = txnum;
    }

    @Override
    public int op() {
        return ROLLBACK;
    }

    @Override
    public int txNumber() {
        return txnum;
    }

    @Override
    public void undo(BufferBase buff) {
        // Rollback records don't need undo
    }

    @Override
    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES * 2);
        bb.putInt(ROLLBACK);
        bb.putInt(txnum);
        return bb.array();
    }

    static RollbackLogRecord fromBytes(ByteBuffer bb) {
        int txnum = bb.getInt();
        return new RollbackLogRecord(txnum);
    }

    @Override
    public String toString() {
        return "ROLLBACK <" + txnum + ">";
    }
}