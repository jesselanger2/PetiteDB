package edu.yu.dbimpl.metadata;

import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.record.*;
import edu.yu.dbimpl.tx.TxBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

public class TableMgr extends TableMgrBase {

    private static final Logger logger = LogManager.getLogger(TableMgr.class);

    // Field names for the table catalog
    private static final String TABLE_SLOTSIZE = "slotsize";

    // Field names for the field catalog
    private static final String FIELD_TABLE_NAME = "tblname";
    private static final String FIELD_NAME = "fldname";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_LENGTH = "length";
    private static final String FIELD_OFFSET = "offset";

    // Cache for layouts to avoid repeated catalog lookups
    private final Map<String, LayoutBase> layoutCache;

    public TableMgr(TxBase tx) {
        super(tx);
        this.layoutCache = new HashMap<>();

        boolean isNew = DBConfiguration.INSTANCE.get().isDBStartup();
        logger.info("TableMgr initialization - fresh database: {}", isNew);

        if (isNew) {
            // Create the catalog tables for the first time
            logger.info("Creating catalog tables");
            createCatalogTables(tx);
        } else {
            // Load existing catalog into cache
            logger.info("Loading existing catalog");
            loadCatalog(tx);
        }
    }

    @Override
    public LayoutBase getLayout(String tableName, TxBase tx) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        // Check cache first
        if (layoutCache.containsKey(tableName)) {
            return layoutCache.get(tableName);
        }

        // Try to load from catalog
        LayoutBase layout = loadLayoutFromCatalog(tableName, tx);
        if (layout != null) {
            layoutCache.put(tableName, layout);
        }

        return layout;
    }

    @Override
    public LayoutBase createTable(String tableName, SchemaBase schema, TxBase tx) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        if (tableName.length() > MAX_LENGTH_PER_NAME) {
            throw new IllegalArgumentException("Table name exceeds maximum length");
        }

        // Check if table already exists
        if (getLayout(tableName, tx) != null) {
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }

        logger.info("Creating table: {}", tableName);

        // Create layout from schema
        LayoutBase layout = new Layout(schema);

        // Store metadata in catalog
        storeTableMetadata(tableName, layout, tx);
        storeFieldMetadata(tableName, layout, tx);

        // Cache the layout
        layoutCache.put(tableName, layout);

        return layout;
    }

    @Override
    public LayoutBase replace(String tableName, SchemaBase schema, TxBase tx) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (tableName.length() > MAX_LENGTH_PER_NAME) {
            throw new IllegalArgumentException("Table name exceeds maximum length");
        }

        // Get existing layout - must exist in catalog
        LayoutBase oldLayout = getLayout(tableName, tx);
        if (oldLayout == null) {
            throw new IllegalArgumentException("Metadata for table does not exist in catalog: " + tableName);
        }

        logger.info("Replacing table metadata: {}", tableName);

        // Delete existing metadata
        deleteTableMetadata(tableName, tx);
        deleteFieldMetadata(tableName, tx);

        // Remove from cache
        layoutCache.remove(tableName);

        // If schema is not null, create new metadata
        if (schema != null) {
            LayoutBase newLayout = new Layout(schema);
            storeTableMetadata(tableName, newLayout, tx);
            storeFieldMetadata(tableName, newLayout, tx);
            layoutCache.put(tableName, newLayout);
        }

        return oldLayout;
    }

    /**
     * Creates the catalog tables on first database startup.
     *
     * @param tx the transaction within which the creation occurs
     */
    private void createCatalogTables(TxBase tx) {
        // Create table catalog schema
        SchemaBase tableSchema = createCatalogTableSchema();

        // Create field catalog schema
        SchemaBase fieldSchema = createCatalogFieldSchema();

        // Create layouts for catalog tables
        LayoutBase tableLayout = new Layout(tableSchema);
        LayoutBase fieldLayout = new Layout(fieldSchema);

        // Cache the catalog layouts before trying to store metadata
        layoutCache.put(TABLE_META_DATA_TABLE, tableLayout);
        layoutCache.put(FIELD_META_DATA_TABLE, fieldLayout);

        // Bootstrap: store metadata about the catalog tables themselves
        storeTableMetadata(TABLE_META_DATA_TABLE, tableLayout, tx);
        storeFieldMetadata(TABLE_META_DATA_TABLE, tableLayout, tx);

        storeTableMetadata(FIELD_META_DATA_TABLE, fieldLayout, tx);
        storeFieldMetadata(FIELD_META_DATA_TABLE, fieldLayout, tx);

        logger.info("Catalog tables created and bootstrapped");
    }

    /**
     * Loads existing catalog into cache on database restart.
     *
     * @param tx the transaction within which the loading occurs
     */
    private void loadCatalog(TxBase tx) {
        // Create the catalog layouts to bootstrap reading
        SchemaBase tableSchema = createCatalogTableSchema();
        LayoutBase tableLayout = new Layout(tableSchema);

        SchemaBase fieldSchema = createCatalogFieldSchema();
        LayoutBase fieldLayout = new Layout(fieldSchema);

        // Cache catalog layouts
        layoutCache.put(TABLE_META_DATA_TABLE, tableLayout);
        layoutCache.put(FIELD_META_DATA_TABLE, fieldLayout);

        logger.info("Catalog layouts loaded into cache");
    }

    /**
     * Creates the schema for the table catalog.
     *
     * @return the schema for the table catalog
     */
    private SchemaBase createCatalogTableSchema() {
        SchemaBase schema = new Schema();
        schema.addStringField(TABLE_NAME, MAX_LENGTH_PER_NAME);
        schema.addIntField(TABLE_SLOTSIZE);
        return schema;
    }

    /**
     * Creates the schema for the field catalog.
     *
     * @return the schema for the field catalog
     */
    private SchemaBase createCatalogFieldSchema() {
        SchemaBase schema = new Schema();
        schema.addStringField(FIELD_TABLE_NAME, MAX_LENGTH_PER_NAME);
        schema.addStringField(FIELD_NAME, MAX_LENGTH_PER_NAME);
        schema.addIntField(FIELD_TYPE);
        schema.addIntField(FIELD_LENGTH);
        schema.addIntField(FIELD_OFFSET);
        return schema;
    }

    /**
     * Loads a table's layout from the catalog.
     *
     * @param tableName the name of the table whose layout is to be loaded
     * @param tx        the transaction within which the loading occurs
     * @return the layout of the specified table, or null if the table is not found
     */
    private LayoutBase loadLayoutFromCatalog(String tableName, TxBase tx) {
        // First get the slot size from table catalog
        TableScan tableScan = new TableScan(tx, TABLE_META_DATA_TABLE,
                layoutCache.get(TABLE_META_DATA_TABLE));

        int slotSize = -1;
        tableScan.beforeFirst();
        while (tableScan.next()) {
            if (tableScan.getString(TABLE_NAME).equals(tableName)) {
                slotSize = tableScan.getInt(TABLE_SLOTSIZE);
                break;
            }
        }
        tableScan.close();

        if (slotSize == -1) {
            // Table not found
            return null;
        }

        // Now get field information from field catalog
        SchemaBase schema = new Schema();
        Map<String, Integer> offsets = new HashMap<>();

        TableScan fieldScan = new TableScan(tx, FIELD_META_DATA_TABLE,
                layoutCache.get(FIELD_META_DATA_TABLE));

        fieldScan.beforeFirst();
        while (fieldScan.next()) {
            if (fieldScan.getString(FIELD_TABLE_NAME).equals(tableName)) {
                String fldname = fieldScan.getString(FIELD_NAME);
                int type = fieldScan.getInt(FIELD_TYPE);
                int length = fieldScan.getInt(FIELD_LENGTH);
                int offset = fieldScan.getInt(FIELD_OFFSET);

                schema.addField(fldname, type, length);
                offsets.put(fldname, offset);
            }
        }
        fieldScan.close();

        // Create and return layout
        return new Layout(schema, offsets, slotSize);
    }

    /**
     * Stores table metadata in the table catalog.
     *
     * @param tableName the name of the table whose metadata is to be stored
     * @param layout    the layout of the table
     * @param tx        the transaction within which the storage occurs
     */
    private void storeTableMetadata(String tableName, LayoutBase layout, TxBase tx) {
        TableScan scan = new TableScan(tx, TABLE_META_DATA_TABLE,
                layoutCache.get(TABLE_META_DATA_TABLE));

        scan.insert();
        scan.setString(TABLE_NAME, tableName);
        scan.setInt(TABLE_SLOTSIZE, layout.slotSize());
        scan.close();

        logger.debug("Stored table metadata for: {}", tableName);
    }

    /**
     * Stores field metadata in the field catalog.
     *
     * @param tableName the name of the table whose field metadata is to be stored
     * @param layout    the layout of the table
     * @param tx        the transaction within which the storage occurs
     */
    private void storeFieldMetadata(String tableName, LayoutBase layout, TxBase tx) {
        TableScan scan = new TableScan(tx, FIELD_META_DATA_TABLE,
                layoutCache.get(FIELD_META_DATA_TABLE));

        SchemaBase schema = layout.schema();
        for (String fldname : schema.fields()) {
            scan.insert();
            scan.setString(FIELD_TABLE_NAME, tableName);
            scan.setString(FIELD_NAME, fldname);
            scan.setInt(FIELD_TYPE, schema.type(fldname));
            scan.setInt(FIELD_LENGTH, schema.length(fldname));
            scan.setInt(FIELD_OFFSET, layout.offset(fldname));
        }
        scan.close();

        logger.debug("Stored field metadata for: {}", tableName);
    }

    /**
     * Deletes table metadata from the table catalog.
     *
     * @param tableName the name of the table whose metadata is to be deleted
     * @param tx        the transaction within which the deletion occurs
     */
    private void deleteTableMetadata(String tableName, TxBase tx) {
        TableScan scan = new TableScan(tx, TABLE_META_DATA_TABLE,
                layoutCache.get(TABLE_META_DATA_TABLE));

        scan.beforeFirst();
        while (scan.next()) {
            if (scan.getString(TABLE_NAME).equals(tableName)) {
                scan.delete();
                break;
            }
        }
        scan.close();

        logger.debug("Deleted table metadata for: {}", tableName);
    }

    /**
     * Deletes field metadata from the field catalog.
     *
     * @param tableName the name of the table whose field metadata is to be deleted
     * @param tx        the transaction within which the deletion occurs
     */
    private void deleteFieldMetadata(String tableName, TxBase tx) {
        TableScan scan = new TableScan(tx, FIELD_META_DATA_TABLE,
                layoutCache.get(FIELD_META_DATA_TABLE));

        scan.beforeFirst();
        while (scan.next()) {
            if (scan.getString(FIELD_TABLE_NAME).equals(tableName)) {
                scan.delete();
                // Don't break - need to delete all fields for this table
            }
        }
        scan.close();

        logger.debug("Deleted field metadata for: {}", tableName);
    }
}