package edu.yu.dbimpl.tx.recovery;

/** Interface supported by all Log Record types.
 *
 * NOTE: implementing this interface is optional because LogRecords are an
 * implementation detail of the recovery manager.  The interface specification
 * is only a design suggestion.  See lecture for more explanation.
 *
 * @author Avraham Leff
 */

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.file.BlockIdBase;

import java.nio.ByteBuffer;

interface LogRecord {

    // Log record operation types
    int CHECKPOINT = 0;
    int START = 1;
    int COMMIT = 2;
    int ROLLBACK = 3;
    int SETINT = 4;
    int SETBOOLEAN = 5;
    int SETDOUBLE = 6;
    int SETSTRING = 7;
    int SETBYTES = 8;

    /** Returns the log record's type.
     *
     * @return the log record's type
     */
    int op();

    /** Returns the transaction id stored with the log record.
     *
     * @return the log record's transaction id
     */
    int txNumber();

    /**
     * Undoes the operation encoded by this log record.  The "undo" semantics
     * may not apply to all LogRecord types, and they are free to provide a no-op
     * implementation.
     *
     * @param buff the buffer to which the undo operation is applied
     */
    default void undo(BufferBase buff) {
        // Default implementation is no-op
    }

    /**
     * Serialize this log record to bytes for writing to log.
     */
    byte[] toBytes();

    /**
     * Get the block this log record applies to (if applicable).
     */
    default BlockIdBase getBlock() {
        return null;
    }

    /**
     * Factory method to create a log record from bytes.
     */
    static LogRecord createLogRecord(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int op = bb.getInt();

        return switch (op) {
            case CHECKPOINT -> new CheckpointLogRecord();
            case START -> StartLogRecord.fromBytes(bb);
            case COMMIT -> CommitLogRecord.fromBytes(bb);
            case ROLLBACK -> RollbackLogRecord.fromBytes(bb);
            case SETINT -> SetIntLogRecord.fromBytes(bb);
            case SETBOOLEAN -> SetBooleanLogRecord.fromBytes(bb);
            case SETDOUBLE -> SetDoubleLogRecord.fromBytes(bb);
            case SETSTRING -> SetStringLogRecord.fromBytes(bb);
            case SETBYTES -> SetBytesLogRecord.fromBytes(bb);
            default -> throw new IllegalArgumentException("Unknown log record type: " + op);
        };
    }
}