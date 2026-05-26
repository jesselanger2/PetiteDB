package edu.yu.dbimpl.file;

import java.nio.ByteBuffer;
import java.util.Objects;

public class Page extends PageBase {

    private final ByteBuffer buffer;

    public Page(int blocksize) {
        super(blocksize);
        buffer = ByteBuffer.allocateDirect(blocksize);
    }

    public Page(byte[] b) {
        super(b);
        buffer = ByteBuffer.wrap(b);
    }

    @Override
    public int getInt(int offset) {
        if (offset < 0 || offset + Integer.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        return buffer.getInt(offset);
    }

    @Override
    public void setInt(int offset, int n) {
        if (offset < 0 || offset + Integer.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        buffer.putInt(offset, n);
    }

    @Override
    public double getDouble(int offset) {
        if (offset < 0 || offset + Double.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        return buffer.getDouble(offset);
    }

    @Override
    public void setDouble(int offset, double d) {
        if (offset < 0 || offset + Double.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        buffer.putDouble(offset, d);
    }

    @Override
    public boolean getBoolean(int offset) {
        if (offset < 0 || offset + 1 > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        return buffer.get(offset) != 0;
    }

    @Override
    public void setBoolean(int offset, boolean d) {
        if (offset < 0 || offset + 1 > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        buffer.put(offset, (byte) (d ? 1 : 0));
    }

    @Override
    public byte[] getBytes(int offset) {
        if (offset < 0 || offset + Integer.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        int length = buffer.getInt(offset);
        if (length < 0 || offset + Integer.BYTES + length > buffer.capacity()) {
            throw new IllegalArgumentException(
                    "Invalid byte array data at offset " + offset + " with stored length " + length
            );
        }
        byte[] b = new byte[length];
        // Use a duplicate buffer to avoid changing the original buffer's position state
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(offset + Integer.BYTES);
        duplicate.get(b);
        return b;
    }

    @Override
    public void setBytes(int offset, byte[] b) {
        if (offset < 0 || offset + Integer.BYTES + b.length > buffer.capacity()) {
            throw new IllegalArgumentException("Byte array too large to fit in block at offset: " + offset);
        }
        buffer.putInt(offset, b.length);
        // Use a duplicate buffer to avoid changing the original buffer's position state
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(offset + Integer.BYTES);
        duplicate.put(b);
    }

    @Override
    public String getString(int offset) {
        if (offset < 0 || offset + Integer.BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        int length = buffer.getInt(offset);
        if (length < 0 || offset + Integer.BYTES + length > buffer.capacity()) {
            throw new IllegalArgumentException("Invalid string length: " + length);
        }
        byte[] strBytes = getBytes(offset);
        return new String(strBytes, CHARSET);
    }

    @Override
    public void setString(int offset, String s) {
        byte[] strBytes = s.getBytes(CHARSET);
        setBytes(offset, strBytes);
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Page page)) return false;
        return Objects.equals(buffer, page.buffer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(buffer);
    }

    @Override
    public String toString() {
        return "Page{" +
                "buffer=" + buffer +
                '}';
    }
}