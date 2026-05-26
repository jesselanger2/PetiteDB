package edu.yu.dbimpl.record;

import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.PageBase;
import edu.yu.dbimpl.tx.TxBase;

import java.sql.Types;

public class RecordPage extends RecordPageBase {

    private static final int IN_USE_FLAG_OFFSET = 0;
    private static final int IN_USE_FLAG_SIZE = 1;

    private final TxBase tx;
    private final BlockIdBase blk;
    private final LayoutBase layout;

    public RecordPage(TxBase tx, BlockIdBase blk, LayoutBase layout) {
        super(tx, blk, layout);
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (blk == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }
        if (layout == null) {
            throw new IllegalArgumentException("Layout cannot be null");
        }

        // Check that block can hold at least one record
        int blockSize = tx.blockSize();
        if (blockSize < layout.slotSize()) {
            throw new IllegalArgumentException("Block too small to hold at least one record");
        }

        this.tx = tx;
        this.blk = blk;
        this.layout = layout;

        // Pin the block in the buffer pool
        tx.pin(blk);
    }

    @Override
    public int getInt(int slot, String fldname) {
        validateSlot(slot);
        validateFieldType(fldname, Types.INTEGER);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        return tx.getInt(blk, offset);
    }

    @Override
    public String getString(int slot, String fldname) {
        validateSlot(slot);
        validateFieldType(fldname, Types.VARCHAR);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        return tx.getString(blk, offset);
    }

    @Override
    public boolean getBoolean(int slot, String fldname) {
        validateSlot(slot);
        validateFieldType(fldname, Types.BOOLEAN);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        return tx.getBoolean(blk, offset);
    }

    @Override
    public double getDouble(int slot, String fldname) {
        validateSlot(slot);
        validateFieldType(fldname, Types.DOUBLE);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        return tx.getDouble(blk, offset);
    }

    @Override
    public void setInt(int slot, String fldname, int val) {
        validateSlot(slot);
        validateFieldType(fldname, Types.INTEGER);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        tx.setInt(blk, offset, val, true);
    }

    @Override
    public void setString(int slot, String fldname, String val) {
        validateSlot(slot);
        validateFieldType(fldname, Types.VARCHAR);
        ensureSlotInUse(slot);

        // Check that string doesn't exceed logical length
        int logicalLength = layout.schema().length(fldname);
        if (PageBase.logicalLength(val) > logicalLength) {
            throw new IllegalArgumentException(
                    "String length exceeds schema length for field " + fldname);
        }

        int offset = getFieldOffset(slot, fldname);
        tx.setString(blk, offset, val, true);
    }

    @Override
    public void setBoolean(int slot, String fldname, boolean val) {
        validateSlot(slot);
        validateFieldType(fldname, Types.BOOLEAN);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        tx.setBoolean(blk, offset, val, true);
    }

    @Override
    public void setDouble(int slot, String fldname, double val) {
        validateSlot(slot);
        validateFieldType(fldname, Types.DOUBLE);
        ensureSlotInUse(slot);

        int offset = getFieldOffset(slot, fldname);
        tx.setDouble(blk, offset, val, true);
    }

    @Override
    public void delete(int slot) {
        validateSlot(slot);
        ensureSlotInUse(slot);

        int offset = getSlotOffset(slot) + IN_USE_FLAG_OFFSET;
        tx.setBoolean(blk, offset, false, true);
    }

    @Override
    public void format() {
        int slot = 0;
        while (isValidSlot(slot)) {
            // Set in-use flag to false
            int flagOffset = getSlotOffset(slot) + IN_USE_FLAG_OFFSET;
            tx.setBoolean(blk, flagOffset, false, false);

            // Initialize all fields to default values (without logging)
            for (String fldname : layout.schema().fields()) {
                int fieldOffset = getFieldOffset(slot, fldname);
                int type = layout.schema().type(fldname);

                switch (type) {
                    case Types.INTEGER:
                        tx.setInt(blk, fieldOffset, 0, false);
                        break;
                    case Types.BOOLEAN:
                        tx.setBoolean(blk, fieldOffset, false, false);
                        break;
                    case Types.DOUBLE:
                        tx.setDouble(blk, fieldOffset, 0.0, false);
                        break;
                    case Types.VARCHAR:
                        tx.setString(blk, fieldOffset, "", false);
                        break;
                }
            }
            slot++;
        }
    }

    @Override
    public int nextAfter(int slot) {
        if (slot < -1) {
            throw new IllegalArgumentException("Slot cannot be less than -1");
        }

        return searchAfter(slot, true);
    }

    @Override
    public int insertAfter(int slot) {
        if (slot < -1) {
            throw new IllegalArgumentException("Slot cannot be less than -1");
        }

        int emptySlot = searchAfter(slot, false);
        if (emptySlot >= 0) {
            // Set the in-use flag to true
            int offset = getSlotOffset(emptySlot) + IN_USE_FLAG_OFFSET;
            tx.setBoolean(blk, offset, true, true);
        }
        return emptySlot;
    }

    @Override
    public BlockIdBase block() {
        return blk;
    }

    /**
     * Searches for the next slot after the specified slot that matches the
     * in-use status.
     *
     * @param slot the slot after which to start the search.
     * @param searchForInUse true to search for in-use slots, false for empty slots.
     * @return the next matching slot, or -1 if none found.
     */
    private int searchAfter(int slot, boolean searchForInUse) {
        int currentSlot = slot + 1;

        while (isValidSlot(currentSlot)) {
            int offset = getSlotOffset(currentSlot) + IN_USE_FLAG_OFFSET;
            boolean inUse = tx.getBoolean(blk, offset);

            if (inUse == searchForInUse) {
                return currentSlot;
            }
            currentSlot++;
        }

        return -1;
    }

    /**
     * Validates that the slot is non-negative and within the block's capacity.
     *
     * @param slot the slot to validate.
     * @throws IllegalArgumentException if slot is negative or exceeds block capacity.
     */
    private void validateSlot(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("Slot must be non-negative");
        }
        if (!isValidSlot(slot)) {
            throw new IllegalArgumentException("Slot " + slot + " exceeds block capacity");
        }
    }

    /**
     * Checks if the slot is valid within the block's capacity.
     *
     * @param slot the slot to check.
     * @return true if the slot is valid, false otherwise.
     */
    private boolean isValidSlot(int slot) {
        int slotOffset = getSlotOffset(slot);
        int blockSize = tx.blockSize();
        return slotOffset + layout.slotSize() <= blockSize;
    }

    /**
     * Calculates the byte offset for the given slot.
     *
     * @param slot the slot number.
     * @return the byte offset of the slot within the block.
     */
    private int getSlotOffset(int slot) {
        return slot * layout.slotSize();
    }

    /**
     * Ensures that the specified slot is marked as "in use".
     *
     * @param slot the slot to check.
     * @throws IllegalStateException if the slot is not in use.
     */
    private void ensureSlotInUse(int slot) {
        int offset = getSlotOffset(slot) + IN_USE_FLAG_OFFSET;
        boolean inUse = tx.getBoolean(blk, offset);
        if (!inUse) {
            throw new IllegalStateException("Slot " + slot + " is not in use");
        }
    }

    /**
     * Validates that the field exists in the schema and is of the expected type.
     *
     * @param fldname the field name.
     * @param expectedType the expected SQL type.
     * @throws IllegalArgumentException if the field does not exist or is of the wrong type.
     */
    private void validateFieldType(String fldname, int expectedType) {
        int actualType = layout.schema().type(fldname);
        if (actualType != expectedType) {
            throw new IllegalArgumentException(
                    "Field " + fldname + " has type " + actualType + ", expected " + expectedType);
        }
    }

    /**
     * Calculates the byte offset for the specified field within the given slot.
     *
     * @param slot the slot number.
     * @param fldname the field name.
     * @return the byte offset of the field within the block.
     */
    private int getFieldOffset(int slot, String fldname) {
        return getSlotOffset(slot) + layout.offset(fldname);
    }
}
