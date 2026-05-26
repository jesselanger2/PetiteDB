package edu.yu.dbimpl.file;

import java.util.Objects;

public class BlockId extends BlockIdBase {

    private final String filename;
    private final int blknum;

    public BlockId(String filename, int blknum) {
        super(filename, blknum);
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("filename must have a trimmed length greater than 0");
        }
        if (blknum < 0) {
            throw new IllegalArgumentException("blknum must be a non-negative integer");
        }
        this.filename = filename;
        this.blknum = blknum;
    }

    @Override
    public String fileName() {
        return filename;
    }

    @Override
    public int number() {
        return blknum;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BlockId blockId)) return false;
        return blknum == blockId.blknum && Objects.equals(filename, blockId.filename);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, blknum);
    }

    @Override
    public String toString() {
        return "BlockId{" +
                "filename='" + filename + '\'' +
                ", blknum=" + blknum +
                '}';
    }
}
