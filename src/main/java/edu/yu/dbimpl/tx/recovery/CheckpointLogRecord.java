package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;

import java.nio.ByteBuffer;

/**
 * A CHECKPOINT log record marks a point in the log where recovery can stop.
 * Written after recovery completes to avoid re-processing old log records.
 */
public class CheckpointLogRecord implements LogRecord {

    public CheckpointLogRecord() {
        // No fields needed
    }

    @Override
    public int op() {
        return CHECKPOINT;
    }

    @Override
    public int txNumber() {
        return -1; // Checkpoint is not associated with a transaction
    }

    @Override
    public void undo(BufferBase buff) {
        // Checkpoint records don't need undo
    }

    @Override
    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES);
        bb.putInt(CHECKPOINT);
        return bb.array();
    }

    @Override
    public String toString() {
        return "CHECKPOINT";
    }
}