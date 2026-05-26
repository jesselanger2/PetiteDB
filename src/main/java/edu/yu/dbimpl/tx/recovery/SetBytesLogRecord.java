package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.file.BlockId;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.PageBase;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A SETBYTES log record logs an update to a byte array value.
 * Contains the old value needed for undo.
 */
public class SetBytesLogRecord implements LogRecord {

    private final int txnum;
    private final BlockIdBase blk;
    private final int offset;
    private final byte[] oldValue;

    public SetBytesLogRecord(int txnum, BlockIdBase blk, int offset, byte[] oldValue) {
        this.txnum = txnum;
        this.blk = blk;
        this.offset = offset;
        this.oldValue = oldValue;
    }

    @Override
    public int op() {
        return SETBYTES;
    }

    @Override
    public int txNumber() {
        return txnum;
    }

    @Override
    public BlockIdBase getBlock() {
        return blk;
    }

    @Override
    public void undo(BufferBase buff) {
        validateBuffer(buff);
        PageBase page = buff.contents();
        page.setBytes(offset, oldValue);
    }

    @Override
    public byte[] toBytes() {
        byte[] filenameBytes = blk.fileName().getBytes(StandardCharsets.UTF_8);
        int size = Integer.BYTES * 6 + filenameBytes.length + oldValue.length;

        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.putInt(SETBYTES);
        bb.putInt(txnum);
        bb.putInt(filenameBytes.length);
        bb.put(filenameBytes);
        bb.putInt(blk.number());
        bb.putInt(offset);
        bb.putInt(oldValue.length);
        bb.put(oldValue);

        return bb.array();
    }

    static SetBytesLogRecord fromBytes(ByteBuffer bb) {
        int txnum = bb.getInt();

        int filenameLen = bb.getInt();
        byte[] filenameBytes = new byte[filenameLen];
        bb.get(filenameBytes);
        String filename = new String(filenameBytes, StandardCharsets.UTF_8);

        int blknum = bb.getInt();
        int offset = bb.getInt();

        int oldValueLen = bb.getInt();
        byte[] oldValue = new byte[oldValueLen];
        bb.get(oldValue);

        BlockIdBase blk = new BlockId(filename, blknum);
        return new SetBytesLogRecord(txnum, blk, offset, oldValue);
    }

    private void validateBuffer(BufferBase buff) {
        if (buff == null || buff.block() == null) {
            throw new IllegalArgumentException("Buffer must be pinned to a block");
        }
        if (!buff.block().equals(blk)) {
            throw new IllegalArgumentException(
                    "Buffer block " + buff.block() + " does not match log record block " + blk);
        }
    }

    @Override
    public String toString() {
        return "SETBYTES <" + txnum + ", " + blk + ", " + offset + ", " +
                Arrays.toString(oldValue) + ">";
    }
}