package edu.yu.dbimpl.metadata;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.record.*;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Types;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TableMgrTest {

    private static final String DB_DIR = "TableMgrTestDB";
    private static final String LOG_FILE = "test_log";
    private static final int BLOCK_SIZE = 400;
    private static final int BUFFER_SIZE = 100;
    private static final int MAX_WAIT_TIME = 500;

    private static TxMgrBase txMgr;

    @BeforeAll
    static void setupDatabase() {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        File dbDirectory = new File(DB_DIR);
        FileMgrBase fileMgr = new FileMgr(dbDirectory, BLOCK_SIZE);
        LogMgrBase logMgr = new LogMgr(fileMgr, LOG_FILE);
        BufferMgrBase bufferMgr = new BufferMgr(fileMgr, logMgr, BUFFER_SIZE, MAX_WAIT_TIME);
        txMgr = new TxMgr(fileMgr, logMgr, bufferMgr, MAX_WAIT_TIME);
    }

    @AfterAll
    static void cleanup() {
        File dbDir = new File(DB_DIR);
        if (dbDir.exists()) {
            deleteDirectory(dbDir);
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    @Test
    @Order(1)
    void testCatalogTablesCreated() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Verify catalog tables exist
        LayoutBase tblcatLayout = tableMgr.getLayout(TableMgrBase.TABLE_META_DATA_TABLE, tx);
        assertNotNull(tblcatLayout, "Table catalog should exist");

        LayoutBase fldcatLayout = tableMgr.getLayout(TableMgrBase.FIELD_META_DATA_TABLE, tx);
        assertNotNull(fldcatLayout, "Field catalog should exist");

        // Verify catalog table schemas
        assertTrue(tblcatLayout.schema().hasField(TableMgrBase.TABLE_NAME));
        assertTrue(fldcatLayout.schema().hasField(TableMgrBase.TABLE_NAME));

        tx.commit();
    }

    @Test
    @Order(2)
    void testCreateSimpleTable() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create a simple schema
        SchemaBase schema = new Schema();
        schema.addIntField("id");
        schema.addStringField("name", 20);

        String tableName = "Student";
        LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

        assertNotNull(layout, "Layout should be created");
        assertEquals(2, layout.schema().fields().size(), "Schema should have 2 fields");
        assertTrue(layout.schema().hasField("id"));
        assertTrue(layout.schema().hasField("name"));
        assertEquals(Types.INTEGER, layout.schema().type("id"));
        assertEquals(Types.VARCHAR, layout.schema().type("name"));

        tx.commit();
    }

    @Test
    @Order(3)
    void testGetLayoutForExistingTable() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Get layout for table created in previous test
        LayoutBase layout = tableMgr.getLayout("Student", tx);

        assertNotNull(layout, "Should retrieve existing layout");
        assertTrue(layout.schema().hasField("id"));
        assertTrue(layout.schema().hasField("name"));
        assertEquals(Types.INTEGER, layout.schema().type("id"));
        assertEquals(Types.VARCHAR, layout.schema().type("name"));
        assertEquals(20, layout.schema().length("name"));

        tx.commit();
    }

    @Test
    @Order(4)
    void testGetLayoutForNonExistentTable() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        LayoutBase layout = tableMgr.getLayout("NonExistent", tx);

        assertNull(layout, "Should return null for non-existent table");

        tx.commit();
    }

    @Test
    @Order(5)
    void testCreateTableWithMultipleTypes() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        schema.addIntField("age");
        schema.addDoubleField("gpa");
        schema.addBooleanField("enrolled");
        schema.addStringField("email", 50);

        String tableName = "StudentInfo";
        LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

        assertNotNull(layout);
        assertEquals(4, layout.schema().fields().size());
        assertEquals(Types.INTEGER, layout.schema().type("age"));
        assertEquals(Types.DOUBLE, layout.schema().type("gpa"));
        assertEquals(Types.BOOLEAN, layout.schema().type("enrolled"));
        assertEquals(Types.VARCHAR, layout.schema().type("email"));

        tx.commit();
    }

    @Test
    @Order(6)
    void testCreateDuplicateTableThrowsException() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        schema.addIntField("id");

        // Should throw because "Student" already exists
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable("Student", schema, tx);
        });

        tx.rollback();
    }

    @Test
    @Order(7)
    void testReplaceTableMetadata() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create initial table
        SchemaBase oldSchema = new Schema();
        oldSchema.addIntField("id");
        oldSchema.addStringField("name", 10);

        String tableName = "Employee";
        LayoutBase oldLayout = tableMgr.createTable(tableName, oldSchema, tx);
        assertNotNull(oldLayout);
        assertEquals(2, oldLayout.schema().fields().size());

        // Replace with new schema
        SchemaBase newSchema = new Schema();
        newSchema.addIntField("emp_id");
        newSchema.addStringField("full_name", 50);
        newSchema.addDoubleField("salary");

        LayoutBase returnedLayout = tableMgr.replace(tableName, newSchema, tx);

        // Verify returned layout is the old one
        assertNotNull(returnedLayout);
        assertEquals(2, returnedLayout.schema().fields().size());
        assertTrue(returnedLayout.schema().hasField("id"));
        assertTrue(returnedLayout.schema().hasField("name"));

        // Verify new layout is now in catalog
        LayoutBase newLayout = tableMgr.getLayout(tableName, tx);
        assertNotNull(newLayout);
        assertEquals(3, newLayout.schema().fields().size());
        assertTrue(newLayout.schema().hasField("emp_id"));
        assertTrue(newLayout.schema().hasField("full_name"));
        assertTrue(newLayout.schema().hasField("salary"));
        assertFalse(newLayout.schema().hasField("id"));
        assertFalse(newLayout.schema().hasField("name"));

        tx.commit();
    }

    @Test
    @Order(8)
    void testReplaceWithNullSchemaDeletesMetadata() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create a table
        SchemaBase schema = new Schema();
        schema.addIntField("id");

        String tableName = "TempTable";
        tableMgr.createTable(tableName, schema, tx);

        // Replace with null schema (delete metadata only)
        LayoutBase oldLayout = tableMgr.replace(tableName, null, tx);
        assertNotNull(oldLayout, "Should return old layout");

        // Verify metadata is gone
        LayoutBase layout = tableMgr.getLayout(tableName, tx);
        assertNull(layout, "Metadata should be deleted");

        tx.commit();
    }

    @Test
    @Order(9)
    void testReplaceNonExistentTableThrowsException() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        schema.addIntField("id");

        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.replace("DoesNotExist", schema, tx);
        });

        tx.rollback();
    }

    @Test
    @Order(10)
    void testTableNameValidation() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        schema.addIntField("id");

        // Test null table name
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable(null, schema, tx);
        });

        // Test empty table name
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable("", schema, tx);
        });

        // Test whitespace-only table name
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable("   ", schema, tx);
        });

        // Test table name exceeding max length
        String longName = "a".repeat(TableMgrBase.MAX_LENGTH_PER_NAME + 1);
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable(longName, schema, tx);
        });

        tx.rollback();
    }

    @Test
    @Order(11)
    void testSchemaValidation() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Test null schema
        assertThrows(IllegalArgumentException.class, () -> {
            tableMgr.createTable("TestTable", null, tx);
        });

        tx.rollback();
    }

    @Test
    @Order(12)
    void testLayoutOffsets() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        schema.addIntField("id");        // 4 bytes
        schema.addBooleanField("active"); // 1 byte
        schema.addDoubleField("score");   // 8 bytes
        schema.addStringField("name", 20); // 20 + 4 bytes

        String tableName = "OffsetTest";
        LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

        // Verify offsets are properly calculated (starting after 1-byte flag)
        assertTrue(layout.offset("id") > 0);
        assertTrue(layout.offset("active") > layout.offset("id"));
        assertTrue(layout.offset("score") > layout.offset("active"));
        assertTrue(layout.offset("name") > layout.offset("score"));

        // Verify slot size accounts for all fields plus flag
        assertTrue(layout.slotSize() > (4 + 1 + 8 + 24));

        tx.commit();
    }

    @Test
    @Order(13)
    void testUseTableWithTableScan() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create table
        SchemaBase schema = new Schema();
        schema.addIntField("id");
        schema.addStringField("name", 15);

        String tableName = "ScanTest";
        LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

        // Use TableScan to insert records
        TableScan ts = new TableScan(tx, tableName, layout);
        for (int i = 0; i < 10; i++) {
            ts.insert();
            ts.setInt("id", i);
            ts.setString("name", "Name" + i);
        }
        ts.close();

        // Read back records
        ts = new TableScan(tx, tableName, layout);
        int count = 0;
        ts.beforeFirst();
        while (ts.next()) {
            int id = ts.getInt("id");
            String name = ts.getString("name");
            assertEquals("Name" + id, name);
            count++;
        }
        ts.close();

        assertEquals(10, count, "Should have 10 records");

        tx.commit();
    }

    @Test
    @Order(14)
    void testPersistenceAcrossTransactions() {
        // Create table in first transaction
        TxBase tx1 = txMgr.newTx();
        TableMgrBase tableMgr1 = new TableMgr(tx1);

        SchemaBase schema = new Schema();
        schema.addIntField("value");
        schema.addStringField("description", 30);

        String tableName = "PersistTest";
        tableMgr1.createTable(tableName, schema, tx1);
        tx1.commit();

        // Verify in second transaction
        TxBase tx2 = txMgr.newTx();
        TableMgrBase tableMgr2 = new TableMgr(tx2);

        LayoutBase layout = tableMgr2.getLayout(tableName, tx2);
        assertNotNull(layout, "Layout should persist across transactions");
        assertEquals(2, layout.schema().fields().size());
        assertTrue(layout.schema().hasField("value"));
        assertTrue(layout.schema().hasField("description"));

        tx2.commit();
    }

    @Test
    @Order(15)
    void testMultipleTablesInCatalog() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create multiple tables
        for (int i = 0; i < 5; i++) {
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("data", 10 + i);

            String tableName = "MultiTable" + i;
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);
            assertNotNull(layout);
        }

        // Verify all tables exist
        for (int i = 0; i < 5; i++) {
            String tableName = "MultiTable" + i;
            LayoutBase layout = tableMgr.getLayout(tableName, tx);
            assertNotNull(layout, "Table " + tableName + " should exist");
            assertEquals(10 + i, layout.schema().length("data"));
        }

        tx.commit();
    }

    @Test
    @Order(16)
    void testCatalogTablesAreAccessible() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Verify we can scan the catalog tables
        LayoutBase tblcatLayout = tableMgr.getLayout(TableMgrBase.TABLE_META_DATA_TABLE, tx);
        TableScan tblcatScan = new TableScan(tx, TableMgrBase.TABLE_META_DATA_TABLE, tblcatLayout);

        int tableCount = 0;
        tblcatScan.beforeFirst();
        while (tblcatScan.next()) {
            String tableName = tblcatScan.getString(TableMgrBase.TABLE_NAME);
            assertNotNull(tableName);
            assertFalse(tableName.trim().isEmpty());
            tableCount++;
        }
        tblcatScan.close();

        assertTrue(tableCount > 0, "Should have tables in catalog");

        // Verify we can scan the field catalog
        LayoutBase fldcatLayout = tableMgr.getLayout(TableMgrBase.FIELD_META_DATA_TABLE, tx);
        TableScan fldcatScan = new TableScan(tx, TableMgrBase.FIELD_META_DATA_TABLE, fldcatLayout);

        int fieldCount = 0;
        fldcatScan.beforeFirst();
        while (fldcatScan.next()) {
            fieldCount++;
        }
        fldcatScan.close();

        assertTrue(fieldCount > 0, "Should have fields in catalog");

        tx.commit();
    }

    @Test
    @Order(17)
    void testFieldNamesWithMaxLength() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        SchemaBase schema = new Schema();
        String maxLengthName = "a".repeat(TableMgrBase.MAX_LENGTH_PER_NAME);
        schema.addIntField(maxLengthName);

        String tableName = "MaxFieldTest";
        LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

        assertNotNull(layout);
        assertTrue(layout.schema().hasField(maxLengthName));

        tx.commit();
    }

    @Test
    @Order(18)
    void testComplexSchemaRoundTrip() {
        TxBase tx = txMgr.newTx();
        TableMgrBase tableMgr = new TableMgr(tx);

        // Create a complex schema
        SchemaBase originalSchema = new Schema();
        originalSchema.addIntField("int_field");
        originalSchema.addDoubleField("double_field");
        originalSchema.addBooleanField("bool_field");
        originalSchema.addStringField("str_field1", 10);
        originalSchema.addStringField("str_field2", 25);
        originalSchema.addIntField("int_field2");

        String tableName = "ComplexTest";
        LayoutBase createdLayout = tableMgr.createTable(tableName, originalSchema, tx);

        // Retrieve the layout
        LayoutBase retrievedLayout = tableMgr.getLayout(tableName, tx);

        // Verify all fields match
        assertEquals(createdLayout.schema().fields().size(),
                retrievedLayout.schema().fields().size());

        for (String field : originalSchema.fields()) {
            assertTrue(retrievedLayout.schema().hasField(field));
            assertEquals(originalSchema.type(field), retrievedLayout.schema().type(field));
            assertEquals(originalSchema.length(field), retrievedLayout.schema().length(field));
            assertEquals(createdLayout.offset(field), retrievedLayout.offset(field));
        }

        assertEquals(createdLayout.slotSize(), retrievedLayout.slotSize());

        tx.commit();
    }
}