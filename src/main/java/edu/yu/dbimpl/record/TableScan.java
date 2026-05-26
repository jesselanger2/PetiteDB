package edu.yu.dbimpl.record;

import edu.yu.dbimpl.file.BlockId;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.query.Datum;
import edu.yu.dbimpl.query.DatumBase;
import edu.yu.dbimpl.tx.TxBase;

import java.sql.Types;

public class TableScan extends TableScanBase {

    private final TxBase tx;
    private final String tableFileName;
    private final LayoutBase layout;
    private RecordPage recordPage;
    private int currentSlot;
    private BlockIdBase currentBlock;

    public TableScan(TxBase tx, String tblname, LayoutBase layout) {
        super(tx, tblname, layout);
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (tblname == null || tblname.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (layout == null) {
            throw new IllegalArgumentException("Layout cannot be null");
        }

        this.tx = tx;
        this.tableFileName = tblname + ".tbl";
        this.layout = layout;

        // If the file is empty, append a block
        if (tx.size(tableFileName) == 0) {
            moveToNewBlock();
        // Otherwise, move to the first block
        } else {
            moveToBlock(0);
            currentSlot = RecordPageBase.BEFORE_FIRST_SLOT;
        }
    }

    @Override
    public String getTableFileName() {
        return tableFileName;
    }

    @Override
    public void setVal(String fldname, DatumBase val) {
        int type = val.getSQLType();
        switch (type) {
            case Types.INTEGER -> setInt(fldname, val.asInt());
            case Types.VARCHAR -> setString(fldname, val.asString());
            case Types.BOOLEAN -> setBoolean(fldname, val.asBoolean());
            case Types.DOUBLE -> setDouble(fldname, val.asDouble());
            default -> throw new IllegalArgumentException("Unsupported field type: " + type);
        }
    }

    @Override
    public void setInt(String fldname, int val) {
        recordPage.setInt(currentSlot, fldname, val);
    }

    @Override
    public void setDouble(String fldname, double val) {
        recordPage.setDouble(currentSlot, fldname, val);
    }

    @Override
    public void setBoolean(String fldname, boolean val) {
        recordPage.setBoolean(currentSlot, fldname, val);
    }

    @Override
    public void setString(String fldname, String val) {
        recordPage.setString(currentSlot, fldname, val);
    }

    @Override
    public void insert() {
        // Try to insert after current position
        currentSlot = recordPage.insertAfter(currentSlot);

        // If no space in current block, try subsequent blocks
        while (currentSlot < 0) {
            if (atLastBlock()) {
                // No space in any existing block, append a new one
                moveToNewBlock();
            } else {
                // Try the next block
                moveToBlock(currentBlock.number() + 1);
            }
            currentSlot = recordPage.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
        }
    }

    @Override
    public void delete() {
        recordPage.delete(currentSlot);
    }

    @Override
    public RID getRid() {
        return new RID(currentBlock.number(), currentSlot);
    }

    @Override
    public void moveToRid(RID rid) {
        moveToBlock(rid.blockNumber());
        currentSlot = rid.slot();
    }

    @Override
    public void beforeFirst() {
        moveToBlock(0);
        currentSlot = RecordPageBase.BEFORE_FIRST_SLOT;
    }

    @Override
    public boolean next() {
        // Try to find the next in-use slot in the current block
        currentSlot = recordPage.nextAfter(currentSlot);

        while (currentSlot < 0) {
            // No more records in current block, try next block
            if (atLastBlock()) {
                return false;
            }
            moveToBlock(currentBlock.number() + 1);
            currentSlot = recordPage.nextAfter(RecordPageBase.BEFORE_FIRST_SLOT);
        }

        return true;
    }

    @Override
    public int getInt(String fldname) {
        return recordPage.getInt(currentSlot, fldname);
    }

    @Override
    public boolean getBoolean(String fldname) {
        return recordPage.getBoolean(currentSlot, fldname);
    }

    @Override
    public double getDouble(String fldname) {
        return recordPage.getDouble(currentSlot, fldname);
    }

    @Override
    public String getString(String fldname) {
        return recordPage.getString(currentSlot, fldname);
    }

    @Override
    public DatumBase getVal(String fldname) {
        int type = getType(fldname);
        return switch (type) {
            case Types.INTEGER -> new Datum(getInt(fldname));
            case Types.VARCHAR -> new Datum(getString(fldname));
            case Types.BOOLEAN -> new Datum(getBoolean(fldname));
            case Types.DOUBLE -> new Datum(getDouble(fldname));
            default -> throw new IllegalArgumentException("Unsupported field type: " + type);
        };
    }

    @Override
    public boolean hasField(String fldname) {
        return layout.schema().hasField(fldname);
    }

    @Override
    public int getType(String fldname) {
        return layout.schema().type(fldname);
    }

    @Override
    public void close() {
        if (recordPage != null) {
            tx.unpin(currentBlock);
            recordPage = null;
        }
    }

    /**
     * Moves to the specified block number in the table file.
     *
     * @param blockNum the block number to move to
     */
    private void moveToBlock(int blockNum) {
        close();
        currentBlock = new BlockId(tableFileName, blockNum);
        recordPage = new RecordPage(tx, currentBlock, layout);
    }

    /**
     * Appends a new block to the table file and moves to it.
     */
    private void moveToNewBlock() {
        close();
        currentBlock = tx.append(tableFileName);
        recordPage = new RecordPage(tx, currentBlock, layout);
        currentSlot = RecordPageBase.BEFORE_FIRST_SLOT;
    }

    /**
     * Checks if the current block is the last block in the table file.
     *
     * @return true if the current block is the last block, false otherwise
     */
    private boolean atLastBlock() {
        return currentBlock.number() == tx.size(tableFileName) - 1;
    }
}