package edu.yu.dbimpl.index;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.metadata.TableMgr;
import edu.yu.dbimpl.metadata.TableMgrBase;
import edu.yu.dbimpl.query.Datum;
import edu.yu.dbimpl.query.DatumBase;
import edu.yu.dbimpl.record.*;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static edu.yu.dbimpl.index.IndexMgrBase.IndexType;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IndexModuleTest {

    private static final String DB_DIR = "IndexModuleTestDB";
    private static final String LOG_FILE = "index_test_log";
    private static final int BLOCK_SIZE = 400;
    private static final int BUFFER_SIZE = 100;
    private static final int MAX_WAIT_TIME = 500;

    private static TxMgrBase txMgr;

    @BeforeAll
    static void setupDatabase() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        props.setProperty(DBConfiguration.N_STATIC_HASH_BUCKETS, "8");
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

    // Helper method to wrap test logic with proper tx handling
    private void runTest(TestLogic logic) {
        TxBase tx = txMgr.newTx();
        try {
            logic.run(tx);
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
        }
    }

    @FunctionalInterface
    interface TestLogic {
        void run(TxBase tx) throws Exception;
    }

    // ========== IndexDescriptor Tests ==========

    @Test
    @Order(1)
    void testIndexDescriptorCreation() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "TestTable1";
            tableMgr.createTable(tableName, schema, tx);

            IndexDescriptorBase descriptor = new IndexDescriptor(
                    tableName,
                    schema,
                    "nameIndex",
                    "name",
                    IndexType.STATIC_HASH
            );

            assertNotNull(descriptor);
            assertEquals(tableName, descriptor.getTableName());
            assertEquals("nameIndex", descriptor.getIndexName());
            assertEquals("name", descriptor.getFieldName());
            assertEquals(IndexType.STATIC_HASH, descriptor.getIndexType());
            assertNotNull(descriptor.getIndexedTableSchema());
        });
    }

    @Test
    @Order(2)
    void testIndexDescriptorValidation() {
        SchemaBase schema = new Schema();
        schema.addIntField("id");

        // Null table name
        assertThrows(IllegalArgumentException.class, () ->
                new IndexDescriptor(null, schema, "idx", "id", IndexType.STATIC_HASH)
        );

        // Empty table name
        assertThrows(IllegalArgumentException.class, () ->
                new IndexDescriptor("", schema, "idx", "id", IndexType.STATIC_HASH)
        );

        // Null schema
        assertThrows(IllegalArgumentException.class, () ->
                new IndexDescriptor("table", null, "idx", "id", IndexType.STATIC_HASH)
        );

        // Field doesn't exist in schema
        assertThrows(IllegalArgumentException.class, () ->
                new IndexDescriptor("table", schema, "idx", "nonexistent", IndexType.STATIC_HASH)
        );
    }

    // ========== IndexMgr Tests ==========

    @Test
    @Order(3)
    void testIndexMgrPersistDescriptor() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 30);

            String tableName = "Student";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(
                    tx, tableName, "name", IndexType.STATIC_HASH
            );

            assertTrue(indexId >= 0, "Index ID should be non-negative");
        });
    }

    @Test
    @Order(4)
    void testIndexMgrGetDescriptor() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("empId");
            schema.addDoubleField("salary");

            String tableName = "Employee";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(
                    tx, tableName, "salary", IndexType.STATIC_HASH
            );

            IndexDescriptorBase descriptor = indexMgr.get(tx, indexId);

            assertNotNull(descriptor);
            assertEquals(tableName, descriptor.getTableName());
            assertEquals("salary", descriptor.getFieldName());
        });
    }

    @Test
    @Order(5)
    void testIndexMgrIndexIds() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);
            schema.addDoubleField("gpa");

            String tableName = "Course";
            tableMgr.createTable(tableName, schema, tx);

            int id1 = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);
            int id2 = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            int id3 = indexMgr.persistIndexDescriptor(tx, tableName, "gpa", IndexType.STATIC_HASH);

            Set<Integer> indexIds = indexMgr.indexIds(tx, tableName);

            assertNotNull(indexIds);
            assertEquals(3, indexIds.size());
            assertTrue(indexIds.contains(id1));
            assertTrue(indexIds.contains(id2));
            assertTrue(indexIds.contains(id3));
        });
    }

    @Test
    @Order(6)
    void testIndexMgrDuplicateIndexReturnsExisting() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");

            String tableName = "DupTest";
            tableMgr.createTable(tableName, schema, tx);

            int id1 = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);
            int id2 = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);

            assertEquals(id1, id2, "Duplicate index should return same ID");
        });
    }

    // ========== StaticHashIndex Basic Tests ==========

    @Test
    @Order(7)
    void testIndexInstantiation() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "InstantiateTest";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            assertNotNull(index);
            index.close();
        });
    }

    @Test
    @Order(8)
    void testIndexInsertAndRetrieve() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "InsertRetrieveTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("name", "Alice");
            RID rid = ts.getRid();
            ts.close();

            DatumBase nameValue = new Datum("Alice");
            index.insert(nameValue, rid);

            index.beforeFirst(nameValue);
            assertTrue(index.next(), "Should find the record");
            assertEquals(rid, index.getRID());
            assertFalse(index.next(), "Should be no more records");

            index.close();
        });
    }

    @Test
    @Order(9)
    void testIndexMultipleRecordsSameKey() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("dept", 20);

            String tableName = "MultiRecordTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "dept", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            List<RID> expectedRids = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                ts.insert();
                ts.setInt("id", i);
                ts.setString("dept", "CS");
                RID rid = ts.getRid();
                expectedRids.add(rid);

                DatumBase deptValue = new Datum("CS");
                index.insert(deptValue, rid);
            }
            ts.close();

            DatumBase searchKey = new Datum("CS");
            index.beforeFirst(searchKey);

            Set<RID> retrievedRids = new HashSet<>();
            while (index.next()) {
                retrievedRids.add(index.getRID());
            }

            assertEquals(5, retrievedRids.size());
            for (RID expectedRid : expectedRids) {
                assertTrue(retrievedRids.contains(expectedRid));
            }

            index.close();
        });
    }

    @Test
    @Order(10)
    void testIndexDelete() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "DeleteTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("name", "Bob");
            RID rid = ts.getRid();
            ts.close();

            DatumBase nameValue = new Datum("Bob");
            index.insert(nameValue, rid);

            index.beforeFirst(nameValue);
            assertTrue(index.next());

            index.delete(nameValue, rid);

            index.beforeFirst(nameValue);
            assertFalse(index.next(), "Should not find deleted record");

            index.close();
        });
    }

    @Test
    @Order(11)
    void testIndexWithTableScanIntegration() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);
            schema.addDoubleField("gpa");

            String tableName = "IntegrationTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            for (int i = 0; i < 20; i++) {
                ts.insert();
                ts.setInt("id", i);
                ts.setString("name", "Student" + (i % 5));
                ts.setDouble("gpa", 2.0 + (i % 4));
                RID rid = ts.getRid();

                DatumBase nameValue = new Datum("Student" + (i % 5));
                index.insert(nameValue, rid);
            }
            ts.close();

            DatumBase searchKey = new Datum("Student2");
            index.beforeFirst(searchKey);

            TableScan ts2 = new TableScan(tx, tableName, layout);
            int count = 0;
            while (index.next()) {
                RID rid = index.getRID();
                ts2.moveToRid(rid);
                String name = ts2.getString("name");
                assertEquals("Student2", name);
                count++;
            }
            ts2.close();

            assertEquals(4, count, "Should find 4 records with Student2");

            index.close();
        });
    }

    @Test
    @Order(12)
    void testLargeDatasetWithIndex() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("category", 15);

            String tableName = "LargeDataset";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "category", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            for (int i = 0; i < 50; i++) {
                ts.insert();
                ts.setInt("id", i);
                ts.setString("category", "Cat" + (i % 10));
                RID rid = ts.getRid();

                DatumBase categoryValue = new Datum("Cat" + (i % 10));
                index.insert(categoryValue, rid);
            }
            ts.close();

            DatumBase searchKey = new Datum("Cat5");
            index.beforeFirst(searchKey);

            int count = 0;
            while (index.next()) {
                count++;
            }

            assertEquals(5, count, "Should find 5 records for Cat5");

            index.close();
        });
    }

    @Test
    @Order(13)
    void testIndexPersistenceAcrossTransactions() {
        int indexId;
        RID rid1;
        LayoutBase layout;
        String tableName = "PersistTest";

        // Transaction 1: Create table, index, and populate
        TxBase tx1 = txMgr.newTx();
        TableMgrBase tableMgr1;
        IndexMgrBase indexMgr1;
        try {
            tableMgr1 = new TableMgr(tx1);
            indexMgr1 = new IndexMgr(tx1, tableMgr1);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("code", 10);

            layout = tableMgr1.createTable(tableName, schema, tx1);

            indexId = indexMgr1.persistIndexDescriptor(tx1, tableName, "code", IndexType.STATIC_HASH);
            IndexBase index1 = indexMgr1.instantiate(tx1, indexId);

            TableScan ts1 = new TableScan(tx1, tableName, layout);
            ts1.insert();
            ts1.setInt("id", 1);
            ts1.setString("code", "ABC123");
            rid1 = ts1.getRid();
            ts1.close();

            index1.insert(new Datum("ABC123"), rid1);
            index1.close();
            tx1.commit();
        } catch (Exception e) {
            tx1.rollback();
            throw e;
        }

        // Transaction 2: Query using persisted index
        TxBase tx2 = txMgr.newTx();
        try {

            TableMgrBase tableMgr2 = new TableMgr(tx2);
            IndexMgrBase indexMgr2 = new IndexMgr(tx2, tableMgr2);

            // Get the layout from catalog (simulating what happens after restart)
            LayoutBase layout2 = tableMgr2.getLayout(tableName, tx2);
            assertNotNull(layout2, "Layout should persist");

            // Instantiate index and query
            IndexBase index2 = indexMgr2.instantiate(tx2, indexId);
            TableScan ts2 = new TableScan(tx2, tableName, layout2);

            index2.beforeFirst(new Datum("ABC123"));
            assertTrue(index2.next(), "Should find persisted index record");

            RID retrievedRid = index2.getRID();
            assertEquals(rid1, retrievedRid);

            // Use TableScan to verify we can access the actual data
            ts2.moveToRid(retrievedRid);
            assertEquals("ABC123", ts2.getString("code"));
            assertEquals(1, ts2.getInt("id"));

            ts2.close();
            index2.close();
            tx2.commit();
        } catch (Exception e) {
            tx2.rollback();
            throw e;
        }
    }

    @Test
    @Order(14)
    void testCompleteWorkflow() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            String tableName = "CompleteWorkflowTest";
            String fieldName = "score";

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addDoubleField(fieldName);
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, fieldName, IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            for (int i = 0; i < 15; i++) {
                ts.insert();
                ts.setInt("id", i);
                double score = (i % 5) * 10.0 + 50.0;
                ts.setDouble(fieldName, score);
                RID rid = ts.getRid();

                index.insert(new Datum(score), rid);
            }
            ts.close();

            DatumBase searchKey = new Datum(70.0);
            index.beforeFirst(searchKey);

            TableScan queryTs = new TableScan(tx, tableName, layout);
            int foundCount = 0;
            while (index.next()) {
                RID rid = index.getRID();
                queryTs.moveToRid(rid);
                double retrievedScore = queryTs.getDouble(fieldName);
                assertEquals(70.0, retrievedScore, 0.001);
                foundCount++;
            }
            queryTs.close();

            assertEquals(3, foundCount, "Should find 3 records with score 70.0");

            index.close();
        });
    }

    @Test
    @Order(15)
    void testIndexIntegerField() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addIntField("age");

            String tableName = "IntIndexTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "age", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setInt("age", 25);
            RID rid = ts.getRid();
            ts.close();

            DatumBase ageValue = new Datum(25);
            index.insert(ageValue, rid);

            index.beforeFirst(ageValue);
            assertTrue(index.next());
            assertEquals(rid, index.getRID());

            index.close();
        });
    }

    @Test
    @Order(16)
    void testIndexBooleanField() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addBooleanField("active");

            String tableName = "BoolIndexTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "active", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setBoolean("active", true);
            RID rid = ts.getRid();
            ts.close();

            DatumBase activeValue = new Datum(true);
            index.insert(activeValue, rid);

            index.beforeFirst(activeValue);
            assertTrue(index.next());
            assertEquals(rid, index.getRID());

            index.close();
        });
    }

    @Test
    @Order(17)
    void testIndexDeleteSpecificRecord() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "DeleteSpecificTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);

            // Insert two records with same key
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("name", "Charlie");
            RID rid1 = ts.getRid();

            ts.insert();
            ts.setInt("id", 2);
            ts.setString("name", "Charlie");
            RID rid2 = ts.getRid();
            ts.close();

            DatumBase nameValue = new Datum("Charlie");
            index.insert(nameValue, rid1);
            index.insert(nameValue, rid2);

            // Delete only one
            index.delete(nameValue, rid1);

            // Verify only one remains
            index.beforeFirst(nameValue);
            assertTrue(index.next());
            RID remaining = index.getRID();
            assertEquals(rid2, remaining);
            assertFalse(index.next());

            index.close();
        });
    }

    @Test
    @Order(18)
    void testIndexDeleteAll() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "DeleteAllTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            for (int i = 0; i < 10; i++) {
                ts.insert();
                ts.setInt("id", i);
                ts.setString("name", "Name" + i);
                RID rid = ts.getRid();

                DatumBase nameValue = new Datum("Name" + i);
                index.insert(nameValue, rid);
            }
            ts.close();

            // Delete all index records
            index.deleteAll();

            // Verify all are gone
            for (int i = 0; i < 10; i++) {
                DatumBase searchKey = new Datum("Name" + i);
                index.beforeFirst(searchKey);
                assertFalse(index.next(), "Should not find any records after deleteAll");
            }

            index.close();
        });
    }

    @Test
    @Order(19)
    void testIndexSearchNonExistentKey() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "NonExistentKeyTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("name", "Diana");
            RID rid = ts.getRid();
            ts.close();

            DatumBase nameValue = new Datum("Diana");
            index.insert(nameValue, rid);

            // Search for different key
            DatumBase searchKey = new Datum("NonExistent");
            index.beforeFirst(searchKey);
            assertFalse(index.next(), "Should not find non-existent key");

            index.close();
        });
    }

    @Test
    @Order(20)
    void testIndexBeforeFirstValidation() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");

            String tableName = "ValidationTest";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            // Null search key
            assertThrows(IllegalArgumentException.class, () -> index.beforeFirst(null));

            // Wrong type
            assertThrows(IllegalArgumentException.class, () ->
                    index.beforeFirst(new Datum("string"))
            );

            index.close();
        });
    }

    @Test
    @Order(21)
    void testIndexNextWithoutBeforeFirst() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");

            String tableName = "NextWithoutBeforeFirstTest";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            assertThrows(IllegalStateException.class, index::next);

            index.close();
        });
    }

    @Test
    @Order(22)
    void testIndexUpdateScenario() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("status", 10);

            String tableName = "UpdateTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "status", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            // Insert initial record
            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("status", "active");
            RID rid = ts.getRid();

            // Insert index entry
            DatumBase oldValue = new Datum("active");
            index.insert(oldValue, rid);

            // Simulate update: delete old index entry
            index.delete(oldValue, rid);

            // Update the record
            ts.moveToRid(rid);
            ts.setString("status", "inactive");

            // Insert new index entry
            DatumBase newValue = new Datum("inactive");
            index.insert(newValue, rid);

            // Verify old value not found
            index.beforeFirst(oldValue);
            assertFalse(index.next());

            // Verify new value found
            index.beforeFirst(newValue);
            assertTrue(index.next());
            assertEquals(rid, index.getRID());

            ts.close();
            index.close();
        });
    }

    @Test
    @Order(23)
    void testMultipleIndicesOnSameTable() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);
            schema.addDoubleField("salary");

            String tableName = "MultiIndexTable";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int nameIndexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            int salaryIndexId = indexMgr.persistIndexDescriptor(tx, tableName, "salary", IndexType.STATIC_HASH);

            IndexBase nameIndex = indexMgr.instantiate(tx, nameIndexId);
            IndexBase salaryIndex = indexMgr.instantiate(tx, salaryIndexId);

            // Insert record
            TableScan ts = new TableScan(tx, tableName, layout);
            ts.insert();
            ts.setInt("id", 1);
            ts.setString("name", "Eve");
            ts.setDouble("salary", 75000.0);
            RID rid = ts.getRid();
            ts.close();

            // Insert into both indices
            nameIndex.insert(new Datum("Eve"), rid);
            salaryIndex.insert(new Datum(75000.0), rid);

            // Query by name
            nameIndex.beforeFirst(new Datum("Eve"));
            assertTrue(nameIndex.next());
            assertEquals(rid, nameIndex.getRID());

            // Query by salary
            salaryIndex.beforeFirst(new Datum(75000.0));
            assertTrue(salaryIndex.next());
            assertEquals(rid, salaryIndex.getRID());

            nameIndex.close();
            salaryIndex.close();
        });
    }

    @Test
    @Order(24)
    void testIndexWithHashCollisions() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("data", 20);

            String tableName = "CollisionTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "data", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            // Insert many records that may hash to same bucket
            TableScan ts = new TableScan(tx, tableName, layout);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String value = "Value" + i;
                values.add(value);

                ts.insert();
                ts.setInt("id", i);
                ts.setString("data", value);
                RID rid = ts.getRid();

                index.insert(new Datum(value), rid);
            }
            ts.close();

            // Verify each can be found
            for (String value : values) {
                index.beforeFirst(new Datum(value));
                assertTrue(index.next(), "Should find " + value);
                assertFalse(index.next(), "Should find exactly one " + value);
            }

            index.close();
        });
    }

    @Test
    @Order(25)
    void testIndexEmptyTable() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");

            String tableName = "EmptyTableTest";
            tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            // Don't insert anything, just search
            index.beforeFirst(new Datum(999));
            assertFalse(index.next(), "Empty index should return false");

            index.close();
        });
    }

    @Test
    @Order(26)
    void testIndexMgrDeleteAll() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            // Create table with index
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            String tableName = "DeleteAllMgrTest";
            tableMgr.createTable(tableName, schema, tx);
            indexMgr.persistIndexDescriptor(tx, tableName, "id", IndexType.STATIC_HASH);

            // Verify it exists
            assertNotNull(tableMgr.getLayout(tableName, tx));

            // Delete all
            indexMgr.deleteAll(tx, tableName);

            // Verify metadata is gone
            assertNull(tableMgr.getLayout(tableName, tx));

            // Verify indices are gone
            Set<Integer> ids = indexMgr.indexIds(tx, tableName);
            assertTrue(ids.isEmpty());
        });
    }

    @Test
    @Order(27)
    void testPerformanceComparison() {
        runTest(tx -> {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            String tableName = "PerformanceTest";
            LayoutBase layout = tableMgr.createTable(tableName, schema, tx);

            int indexId = indexMgr.persistIndexDescriptor(tx, tableName, "name", IndexType.STATIC_HASH);
            IndexBase index = indexMgr.instantiate(tx, indexId);

            // Insert many records
            TableScan ts = new TableScan(tx, tableName, layout);
            int numRecords = 100;
            RID targetRid = null;
            for (int i = 0; i < numRecords; i++) {
                ts.insert();
                ts.setInt("id", i);
                String name = "Name" + i;
                ts.setString("name", name);
                RID rid = ts.getRid();

                index.insert(new Datum(name), rid);

                if (i == 75) {
                    targetRid = rid;
                }
            }
            ts.close();

            // Search with index
            long startWithIndex = System.nanoTime();
            index.beforeFirst(new Datum("Name75"));
            index.next();
            RID foundRid = index.getRID();
            long endWithIndex = System.nanoTime();

            assertEquals(targetRid, foundRid);

            // Search without index (full scan)
            TableScan scanTs = new TableScan(tx, tableName, layout);
            long startWithoutIndex = System.nanoTime();
            scanTs.beforeFirst();
            while (scanTs.next()) {
                if (scanTs.getString("name").equals("Name75")) {
                    break;
                }
            }
            long endWithoutIndex = System.nanoTime();
            scanTs.close();

            long indexTime = endWithIndex - startWithIndex;
            long scanTime = endWithoutIndex - startWithoutIndex;

            assertNotNull(foundRid);

            index.close();

            System.out.println("Index search time (ns): " + indexTime);
            System.out.println("Full scan time (ns): " + scanTime);
        });
    }

    @Test
    @Order(28)
    void testDemoUsagePattern() {
        int nameIndexId;
        LayoutBase layout;
        String tableName = "students";
        String studentFieldNameName = "name";
        String studentFieldNameGPA = "gpa";

        // First transaction: create table, index, and populate
        TxBase tx = txMgr.newTx();
        try {
            TableMgrBase tableMgr = new TableMgr(tx);
            IndexMgrBase indexMgr = new IndexMgr(tx, tableMgr);

            SchemaBase schema = new Schema();
            schema.addStringField(studentFieldNameName, 20);
            schema.addDoubleField(studentFieldNameGPA);
            layout = tableMgr.createTable(tableName, schema, tx);

            TableScan ts = new TableScan(tx, tableName, layout);

            IndexType indexType = IndexType.STATIC_HASH;
            nameIndexId = indexMgr.persistIndexDescriptor(tx, tableName,
                    studentFieldNameName, indexType);

            Set<Integer> ids = indexMgr.indexIds(tx, tableName);
            assertEquals(1, ids.size());
            assertTrue(ids.contains(nameIndexId));

            IndexDescriptorBase studentNameDescriptor = indexMgr.get(tx, nameIndexId);
            assertNotNull(studentNameDescriptor);
            assertEquals(tableName, studentNameDescriptor.getTableName());
            assertEquals(studentFieldNameName, studentNameDescriptor.getFieldName());

            IndexBase nameIndex = indexMgr.instantiate(tx, nameIndexId);

            int nRecords = 10;
            for (int i = 0; i < nRecords; i++) {
                ts.insert();
                String name = studentFieldNameName + "_" + i;
                double gpa = (double) (i % 4) + 0.3;
                ts.setString(studentFieldNameName, name);
                ts.setDouble(studentFieldNameGPA, gpa);

                RID rid = ts.getRid();
                DatumBase nameValue = new Datum(name);
                nameIndex.insert(nameValue, rid);
            }

            ts.close();
            nameIndex.close();
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }

        // Second transaction: query using the index
        TxBase tx2 = txMgr.newTx();
        try {
            // Get layout from catalog
            TableMgrBase tableMgr2 = new TableMgr(tx2);
            IndexMgrBase indexMgr2 = new IndexMgr(tx2, tableMgr2);
            LayoutBase layout2 = tableMgr2.getLayout(tableName, tx2);

            DatumBase name7 = new Datum(studentFieldNameName + "_" + 7);
            TableScan ts2 = new TableScan(tx2, tableName, layout2);
            IndexBase nameIndex2 = indexMgr2.instantiate(tx2, nameIndexId);

            nameIndex2.beforeFirst(name7);

            boolean found = false;
            while (nameIndex2.next()) {
                RID rid = nameIndex2.getRID();
                ts2.moveToRid(rid);
                double retrievedGPA = ts2.getDouble(studentFieldNameGPA);
                String retrievedName = ts2.getString(studentFieldNameName);

                assertEquals("name_7", retrievedName);
                assertEquals(3.3, retrievedGPA, 0.01);
                found = true;
            }

            assertTrue(found, "Should find the student record");

            ts2.close();
            nameIndex2.close();
            tx2.commit();
        } catch (Exception e) {
            tx2.rollback();
            throw e;
        }
    }
}