package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;

import java.nio.ByteBuffer;

/**
 * A START log record marks the beginning of a transaction.
 */
public class StartLogRecord implements LogRecord {

    private final int txnum;

    public StartLogRecord(int txnum) {
        this.txnum = txnum;
    }

    @Override
    public int op() {
        return START;
    }

    @Override
    public int txNumber() {
        return txnum;
    }

    @Override
    public void undo(BufferBase buff) {
        // Start records don't need undo
    }

    @Override
    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES * 2);
        bb.putInt(START);
        bb.putInt(txnum);
        return bb.array();
    }

    static StartLogRecord fromBytes(ByteBuffer bb) {
        int txnum = bb.getInt();
        return new StartLogRecord(txnum);
    }

    @Override
    public String toString() {
        return "START <" + txnum + ">";
    }
}