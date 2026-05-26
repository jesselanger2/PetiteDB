package edu.yu.dbimpl.index;

import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.metadata.TableMgrBase;
import edu.yu.dbimpl.record.*;
import edu.yu.dbimpl.tx.TxBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexMgr extends IndexMgrBase {

    private static final Logger logger = LogManager.getLogger(IndexMgr.class);

    // Catalog table and field names
    private static final String INDEX_CATALOG_TABLE = "idxcat";
    private static final String FIELD_INDEX_ID = "indexid";
    private static final String FIELD_TABLE_NAME = "tblname";
    private static final String FIELD_INDEX_NAME = "idxname";
    private static final String FIELD_FIELD_NAME = "fldname";
    private static final String FIELD_INDEX_TYPE = "idxtype";

    private final TableMgrBase tableMgr;
    private final int numBuckets;
    private final AtomicInteger nextIndexId;

    // Cache for index descriptors
    private final Map<Integer, IndexDescriptorBase> descriptorCache;
    // Map from table name to set of index IDs
    private final Map<String, Set<Integer>> tableIndexMap;

    public IndexMgr(TxBase tx, TableMgrBase tableMgr) {
        super(tx, tableMgr);

        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (tableMgr == null) {
            throw new IllegalArgumentException("TableMgr cannot be null");
        }

        this.tableMgr = tableMgr;
        this.numBuckets = DBConfiguration.INSTANCE.get().nStaticHashBuckets();
        this.nextIndexId = new AtomicInteger(0);
        this.descriptorCache = new ConcurrentHashMap<>();
        this.tableIndexMap = new ConcurrentHashMap<>();

        boolean isNew = DBConfiguration.INSTANCE.get().isDBStartup();
        logger.info("IndexMgr initialization - fresh database: {}, numBuckets: {}",
                isNew, numBuckets);

        // Check if catalog already exists
        LayoutBase existingCatalog = tableMgr.getLayout(INDEX_CATALOG_TABLE, tx);

        if (existingCatalog == null) {
            // Catalog doesn't exist, create it
            createIndexCatalog(tx);
            logger.info("Created new index catalog");
        } else {
            // Catalog exists, load it
            loadIndexCatalog(tx);
            logger.info("Loaded existing index catalog");
        }
    }

    @Override
    public synchronized int persistIndexDescriptor(TxBase tx, String tableName,
                                                   String fieldName, IndexType indexType) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be null or empty");
        }
        if (indexType == null) {
            throw new IllegalArgumentException("Index type cannot be null");
        }

        // Verify that table exists in catalog
        LayoutBase tableLayout = tableMgr.getLayout(tableName, tx);
        if (tableLayout == null) {
            throw new IllegalArgumentException(
                    "Table " + tableName + " does not exist in catalog");
        }

        // Verify that field exists in table schema
        if (!tableLayout.schema().hasField(fieldName)) {
            throw new IllegalArgumentException(
                    "Field " + fieldName + " does not exist in table " + tableName);
        }

        // Check if index already exists
        Integer existingId = findExistingIndex(tx, tableName, fieldName);
        if (existingId != null) {
            logger.debug("Index already exists: table={}, field={}, id={}",
                    tableName, fieldName, existingId);
            return existingId;
        }

        // Create new index descriptor
        int indexId = nextIndexId.getAndIncrement();
        String indexName = fieldName; // Index name is same as field name per spec

        IndexDescriptorBase descriptor = new IndexDescriptor(
                tableName,
                tableLayout.schema(),
                indexName,
                fieldName,
                indexType
        );

        // Persist to catalog
        storeIndexDescriptor(tx, indexId, descriptor);

        // Cache the descriptor
        descriptorCache.put(indexId, descriptor);
        tableIndexMap.computeIfAbsent(tableName, k -> ConcurrentHashMap.newKeySet())
                .add(indexId);

        logger.info("Created index: id={}, table={}, field={}, type={}",
                indexId, tableName, fieldName, indexType);

        return indexId;
    }

    @Override
    public Set<Integer> indexIds(TxBase tx, String tableName) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        // Verify that table exists
        LayoutBase layout = tableMgr.getLayout(tableName, tx);
        if (layout == null) {
            throw new IllegalArgumentException(
                    "Table " + tableName + " does not exist in catalog");
        }

        Set<Integer> ids = tableIndexMap.get(tableName);
        return ids != null ? new HashSet<>(ids) : Collections.emptySet();
    }

    @Override
    public IndexDescriptorBase get(TxBase tx, int indexId) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }

        return descriptorCache.get(indexId);
    }

    @Override
    public IndexBase instantiate(TxBase tx, int indexDescriptorId) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }

        IndexDescriptorBase descriptor = descriptorCache.get(indexDescriptorId);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "No index descriptor found with id: " + indexDescriptorId);
        }

        // Currently only support STATIC_HASH
        if (descriptor.getIndexType() != IndexType.STATIC_HASH) {
            throw new UnsupportedOperationException(
                    "Index type " + descriptor.getIndexType() + " is not supported");
        }

        return new StaticHashIndex(tx, descriptor, numBuckets);
    }

    @Override
    public synchronized void deleteAll(TxBase tx, String tableName) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        logger.info("Deleting all data and metadata for table: {}", tableName);

        // Get all index IDs for this table
        Set<Integer> indexIds = indexIds(tx, tableName);

        // Delete index data for each index
        for (int indexId : indexIds) {
            IndexDescriptorBase descriptor = get(tx, indexId);
            if (descriptor != null) {
                // Delete all index records
                IndexBase index = instantiate(tx, indexId);
                try {
                    index.deleteAll();
                } finally {
                    index.close();
                }

                // Delete from catalog
                deleteIndexFromCatalog(tx, indexId);

                // Remove from caches
                descriptorCache.remove(indexId);
            }
        }

        // Remove table from index map
        tableIndexMap.remove(tableName);

        // Delete table metadata (delegated to TableMgr)
        tableMgr.replace(tableName, null, tx);

        logger.info("Deleted all data and metadata for table: {}", tableName);
    }

    /**
     * Creates the index catalog table.
     */
    private void createIndexCatalog(TxBase tx) {
        SchemaBase schema = new Schema();
        schema.addIntField(FIELD_INDEX_ID);
        schema.addStringField(FIELD_TABLE_NAME, TableMgrBase.MAX_LENGTH_PER_NAME);
        schema.addStringField(FIELD_INDEX_NAME, TableMgrBase.MAX_LENGTH_PER_NAME);
        schema.addStringField(FIELD_FIELD_NAME, TableMgrBase.MAX_LENGTH_PER_NAME);
        schema.addIntField(FIELD_INDEX_TYPE);

        tableMgr.createTable(INDEX_CATALOG_TABLE, schema, tx);
        logger.info("Created index catalog table");
    }

    /**
     * Loads existing index catalog into memory.
     */
    private void loadIndexCatalog(TxBase tx) {
        LayoutBase catalogLayout = tableMgr.getLayout(INDEX_CATALOG_TABLE, tx);
        if (catalogLayout == null) {
            logger.warn("Index catalog table not found, creating it");
            createIndexCatalog(tx);
            return;
        }

        TableScan scan = new TableScan(tx, INDEX_CATALOG_TABLE, catalogLayout);
        try {
            int maxId = -1;
            scan.beforeFirst();
            while (scan.next()) {
                int indexId = scan.getInt(FIELD_INDEX_ID);
                String tableName = scan.getString(FIELD_TABLE_NAME);
                String indexName = scan.getString(FIELD_INDEX_NAME);
                String fieldName = scan.getString(FIELD_FIELD_NAME);
                int typeOrdinal = scan.getInt(FIELD_INDEX_TYPE);
                IndexType indexType = IndexType.values()[typeOrdinal];

                // Get table schema from TableMgr
                LayoutBase tableLayout = tableMgr.getLayout(tableName, tx);
                if (tableLayout == null) {
                    logger.warn("Table {} not found for index {}, skipping", tableName, indexId);
                    continue;
                }

                // Recreate descriptor
                IndexDescriptorBase descriptor = new IndexDescriptor(
                        tableName,
                        tableLayout.schema(),
                        indexName,
                        fieldName,
                        indexType
                );

                descriptorCache.put(indexId, descriptor);
                tableIndexMap.computeIfAbsent(tableName, k -> ConcurrentHashMap.newKeySet())
                        .add(indexId);

                maxId = Math.max(maxId, indexId);
            }

            // Set next ID
            nextIndexId.set(maxId + 1);
            logger.info("Loaded {} index descriptors from catalog", descriptorCache.size());
        } finally {
            scan.close();
        }
    }

    /**
     * Stores an index descriptor in the catalog.
     */
    private void storeIndexDescriptor(TxBase tx, int indexId, IndexDescriptorBase descriptor) {
        LayoutBase catalogLayout = tableMgr.getLayout(INDEX_CATALOG_TABLE, tx);
        TableScan scan = new TableScan(tx, INDEX_CATALOG_TABLE, catalogLayout);
        try {
            scan.insert();
            scan.setInt(FIELD_INDEX_ID, indexId);
            scan.setString(FIELD_TABLE_NAME, descriptor.getTableName());
            scan.setString(FIELD_INDEX_NAME, descriptor.getIndexName());
            scan.setString(FIELD_FIELD_NAME, descriptor.getFieldName());
            scan.setInt(FIELD_INDEX_TYPE, descriptor.getIndexType().ordinal());
        } finally {
            scan.close();
        }
    }

    /**
     * Finds an existing index for the given table and field.
     */
    private Integer findExistingIndex(TxBase tx, String tableName, String fieldName) {
        LayoutBase catalogLayout = tableMgr.getLayout(INDEX_CATALOG_TABLE, tx);
        TableScan scan = new TableScan(tx, INDEX_CATALOG_TABLE, catalogLayout);
        try {
            scan.beforeFirst();
            while (scan.next()) {
                String tblName = scan.getString(FIELD_TABLE_NAME);
                String fldName = scan.getString(FIELD_FIELD_NAME);
                if (tblName.equals(tableName) && fldName.equals(fieldName)) {
                    return scan.getInt(FIELD_INDEX_ID);
                }
            }
        } finally {
            scan.close();
        }
        return null;
    }

    /**
     * Deletes an index descriptor from the catalog.
     */
    private void deleteIndexFromCatalog(TxBase tx, int indexId) {
        LayoutBase catalogLayout = tableMgr.getLayout(INDEX_CATALOG_TABLE, tx);
        TableScan scan = new TableScan(tx, INDEX_CATALOG_TABLE, catalogLayout);
        try {
            scan.beforeFirst();
            while (scan.next()) {
                int id = scan.getInt(FIELD_INDEX_ID);
                if (id == indexId) {
                    scan.delete();
                    logger.debug("Deleted index {} from catalog", indexId);
                    return;
                }
            }
        } finally {
            scan.close();
        }
    }
}