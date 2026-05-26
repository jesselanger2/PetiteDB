package edu.yu.dbimpl.index;

import edu.yu.dbimpl.query.DatumBase;
import edu.yu.dbimpl.record.*;
import edu.yu.dbimpl.tx.TxBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Types;

/**
 * Static hash index implementation.
 * Uses a fixed number of buckets determined at index creation time.
 */
public class StaticHashIndex implements IndexBase {

    private static final Logger logger = LogManager.getLogger(StaticHashIndex.class);

    // Schema field names for index records
    private static final String FIELD_BLOCK = "block";
    private static final String FIELD_SLOT = "slot";

    private final TxBase tx;
    private final IndexDescriptorBase descriptor;
    private final int numBuckets;
    private final LayoutBase indexLayout;

    // Current search state
    private DatumBase searchKey;
    private TableScan currentScan;
    private int currentBucket;

    public StaticHashIndex(TxBase tx, IndexDescriptorBase descriptor, int numBuckets) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("Index descriptor cannot be null");
        }
        if (numBuckets <= 0) {
            throw new IllegalArgumentException("Number of buckets must be positive");
        }

        this.tx = tx;
        this.descriptor = descriptor;
        this.numBuckets = numBuckets;
        this.searchKey = null;
        this.currentScan = null;
        this.currentBucket = -1;

        // Create schema for index records
        SchemaBase indexSchema = createIndexSchema();
        this.indexLayout = new Layout(indexSchema);

        logger.debug("Created StaticHashIndex for table={}, field={}, buckets={}",
                descriptor.getTableName(), descriptor.getFieldName(), numBuckets);
    }

    /**
     * Creates the schema for index records.
     * Schema includes: indexed field + block number + slot number
     */
    private SchemaBase createIndexSchema() {
        SchemaBase schema = new Schema();

        // Add the indexed field
        String fieldName = descriptor.getFieldName();
        int fieldType = descriptor.getIndexedTableSchema().type(fieldName);
        int fieldLength = descriptor.getIndexedTableSchema().length(fieldName);

        schema.addField(fieldName, fieldType, fieldLength);

        // Add RID fields (block and slot)
        schema.addIntField(FIELD_BLOCK);
        schema.addIntField(FIELD_SLOT);

        return schema;
    }

    @Override
    public void beforeFirst(DatumBase searchKey) {
        if (searchKey == null) {
            throw new IllegalArgumentException("Search key cannot be null");
        }

        // Validate search key type matches indexed field type
        int expectedType = descriptor.getIndexedTableSchema().type(descriptor.getFieldName());
        if (searchKey.getSQLType() != expectedType) {
            throw new IllegalArgumentException(
                    "Search key type " + searchKey.getSQLType() +
                            " does not match indexed field type " + expectedType);
        }

        // Close any existing scan
        if (currentScan != null) {
            currentScan.close();
            currentScan = null;
        }

        this.searchKey = searchKey;
        this.currentBucket = hash(searchKey);

        // Open scan on the appropriate bucket file
        String bucketFileName = getBucketFileName(currentBucket);
        currentScan = new TableScan(tx, bucketFileName, indexLayout);
        currentScan.beforeFirst();

        logger.debug("Positioned index before first record with key={} in bucket={}",
                searchKey, currentBucket);
    }

    @Override
    public boolean next() {
        if (searchKey == null || currentScan == null) {
            throw new IllegalStateException(
                    "beforeFirst must be called with a search key before calling next");
        }

        // Scan through the bucket looking for matching keys
        while (currentScan.next()) {
            DatumBase recordKey = currentScan.getVal(descriptor.getFieldName());
            if (recordKey.equals(searchKey)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public RID getRID() {
        if (searchKey == null || currentScan == null) {
            throw new IllegalStateException("Index is not positioned on a valid record");
        }

        int block = currentScan.getInt(FIELD_BLOCK);
        int slot = currentScan.getInt(FIELD_SLOT);
        return new RID(block, slot);
    }

    @Override
    public void insert(DatumBase value, RID rid) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (rid == null) {
            throw new IllegalArgumentException("RID cannot be null");
        }

        // Validate value type
        int expectedType = descriptor.getIndexedTableSchema().type(descriptor.getFieldName());
        if (value.getSQLType() != expectedType) {
            throw new IllegalArgumentException(
                    "Value type " + value.getSQLType() +
                            " does not match indexed field type " + expectedType);
        }

        // Determine which bucket
        int bucket = hash(value);
        String bucketFileName = getBucketFileName(bucket);

        // Open a scan on the bucket file
        TableScan scan = new TableScan(tx, bucketFileName, indexLayout);
        try {
            // Insert new index record
            scan.insert();
            setIndexRecordValue(scan, value);
            scan.setInt(FIELD_BLOCK, rid.blockNumber());
            scan.setInt(FIELD_SLOT, rid.slot());

            logger.debug("Inserted index record: value={}, rid={}, bucket={}",
                    value, rid, bucket);
        } finally {
            scan.close();
        }
    }

    @Override
    public void delete(DatumBase value, RID rid) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (rid == null) {
            throw new IllegalArgumentException("RID cannot be null");
        }

        // Validate value type
        int expectedType = descriptor.getIndexedTableSchema().type(descriptor.getFieldName());
        if (value.getSQLType() != expectedType) {
            throw new IllegalArgumentException(
                    "Value type " + value.getSQLType() +
                            " does not match indexed field type " + expectedType);
        }

        // Determine which bucket
        int bucket = hash(value);
        String bucketFileName = getBucketFileName(bucket);

        // Open a scan on the bucket file
        TableScan scan = new TableScan(tx, bucketFileName, indexLayout);
        try {
            scan.beforeFirst();
            while (scan.next()) {
                DatumBase recordValue = scan.getVal(descriptor.getFieldName());
                int recordBlock = scan.getInt(FIELD_BLOCK);
                int recordSlot = scan.getInt(FIELD_SLOT);

                if (recordValue.equals(value) &&
                        recordBlock == rid.blockNumber() &&
                        recordSlot == rid.slot()) {
                    scan.delete();
                    logger.debug("Deleted index record: value={}, rid={}, bucket={}",
                            value, rid, bucket);
                    return;
                }
            }

            logger.warn("Index record not found for deletion: value={}, rid={}", value, rid);
        } finally {
            scan.close();
        }
    }

    @Override
    public void deleteAll() {
        logger.info("Deleting all index records for table={}, field={}",
                descriptor.getTableName(), descriptor.getFieldName());

        // Delete records from all buckets
        for (int bucket = 0; bucket < numBuckets; bucket++) {
            String bucketFileName = getBucketFileName(bucket);
            TableScan scan = new TableScan(tx, bucketFileName, indexLayout);
            try {
                scan.beforeFirst();
                while (scan.next()) {
                    scan.delete();
                }
            } finally {
                scan.close();
            }
        }
    }

    @Override
    public void close() {
        if (currentScan != null) {
            currentScan.close();
            currentScan = null;
        }
        searchKey = null;
        currentBucket = -1;
    }

    /**
     * Computes the hash bucket for a given value.
     */
    private int hash(DatumBase value) {
        int hashCode = value.hashCode();
        return Math.abs(hashCode) % numBuckets;
    }

    /**
     * Returns the file name for a given bucket.
     */
    private String getBucketFileName(int bucket) {
        return descriptor.getTableName() + "_" +
                descriptor.getIndexName() + "_" +
                bucket;
    }

    /**
     * Sets the indexed field value in the current scan record.
     */
    private void setIndexRecordValue(TableScan scan, DatumBase value) {
        String fieldName = descriptor.getFieldName();
        int fieldType = value.getSQLType();

        switch (fieldType) {
            case Types.INTEGER -> scan.setInt(fieldName, value.asInt());
            case Types.VARCHAR -> scan.setString(fieldName, value.asString());
            case Types.BOOLEAN -> scan.setBoolean(fieldName, value.asBoolean());
            case Types.DOUBLE -> scan.setDouble(fieldName, value.asDouble());
            default -> throw new IllegalArgumentException("Unsupported field type: " + fieldType);
        }
    }
}