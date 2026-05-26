package edu.yu.dbimpl.record;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.query.Datum;
import edu.yu.dbimpl.query.DatumBase;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Types;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RecordModuleTests {

    private static TxMgrBase txMgr;
    private static final String TEST_DIR = "test_record_module";
    private static final int BLOCK_SIZE = 400;

    @BeforeAll
    static void setupDatabase() {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        File dbDir = new File(TEST_DIR);
        FileMgrBase fileMgr = new FileMgr(dbDir, BLOCK_SIZE);
        LogMgrBase logMgr = new LogMgr(fileMgr, "test_log");
        BufferMgrBase bufferMgr = new BufferMgr(fileMgr, logMgr, 10, 500);
        txMgr = new TxMgr(fileMgr, logMgr, bufferMgr, 500);
    }

    @AfterAll
    static void cleanup() {
        File dbDir = new File(TEST_DIR);
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

    // ========== DATUM TESTS ==========

    @Nested
    @DisplayName("Datum Tests")
    class DatumTests {

        @Test
        @DisplayName("Create and retrieve Integer Datum")
        void testIntegerDatum() {
            DatumBase datum = new Datum(42);
            assertEquals(42, datum.asInt());
            assertEquals(Types.INTEGER, datum.getSQLType());
        }

        @Test
        @DisplayName("Create and retrieve String Datum")
        void testStringDatum() {
            DatumBase datum = new Datum("hello");
            assertEquals("hello", datum.asString());
            assertEquals(Types.VARCHAR, datum.getSQLType());
        }

        @Test
        @DisplayName("Create and retrieve Boolean Datum")
        void testBooleanDatum() {
            DatumBase datum = new Datum(true);
            assertTrue(datum.asBoolean());
            assertEquals(Types.BOOLEAN, datum.getSQLType());
        }

        @Test
        @DisplayName("Create and retrieve Double Datum")
        void testDoubleDatum() {
            DatumBase datum = new Datum(3.14);
            assertEquals(3.14, datum.asDouble(), 0.001);
            assertEquals(Types.DOUBLE, datum.getSQLType());
        }

        @Test
        @DisplayName("Create and retrieve byte array Datum")
        void testByteArrayDatum() {
            byte[] bytes = {1, 2, 3, 4, 5};
            DatumBase datum = new Datum(bytes);
            assertArrayEquals(bytes, datum.asBinaryArray());
            assertEquals(Types.VARBINARY, datum.getSQLType());
        }

        @Test
        @DisplayName("Wrong type access throws ClassCastException")
        void testWrongTypeAccess() {
            DatumBase intDatum = new Datum(42);
            assertThrows(ClassCastException.class, intDatum::asString);
            assertThrows(ClassCastException.class, intDatum::asBoolean);
        }

        @Test
        @DisplayName("Null value throws IllegalArgumentException")
        void testNullValues() {
            assertThrows(IllegalArgumentException.class, () -> new Datum((Integer) null));
            assertThrows(IllegalArgumentException.class, () -> new Datum((String) null));
            assertThrows(IllegalArgumentException.class, () -> new Datum((Boolean) null));
            assertThrows(IllegalArgumentException.class, () -> new Datum((Double) null));
            assertThrows(IllegalArgumentException.class, () -> new Datum((byte[]) null));
        }

        @Test
        @DisplayName("Datum equality")
        void testDatumEquality() {
            DatumBase d1 = new Datum(42);
            DatumBase d2 = new Datum(42);
            DatumBase d3 = new Datum(43);

            assertEquals(d1, d2);
            assertNotEquals(d1, d3);

            // Different types should not be equal
            DatumBase d4 = new Datum(42.0);
            assertNotEquals(d1, d4);
        }

        @Test
        @DisplayName("Datum comparison")
        void testDatumComparison() {
            DatumBase d1 = new Datum(10);
            DatumBase d2 = new Datum(20);
            DatumBase d3 = new Datum(10);

            assertTrue(d1.compareTo(d2) < 0);
            assertTrue(d2.compareTo(d1) > 0);
            assertEquals(0, d1.compareTo(d3));
        }

        @Test
        @DisplayName("Comparing different types throws ClassCastException")
        void testCrossTyepComparison() {
            DatumBase intDatum = new Datum(42);
            DatumBase strDatum = new Datum("42");
            DatumBase doubleDatum = new Datum(42.0);

            assertThrows(ClassCastException.class, () -> intDatum.compareTo(strDatum));
            assertThrows(ClassCastException.class, () -> intDatum.compareTo(doubleDatum));
        }
    }

    // ========== SCHEMA TESTS ==========

    @Nested
    @DisplayName("Schema Tests")
    class SchemaTests {

        @Test
        @DisplayName("Create empty schema")
        void testEmptySchema() {
            SchemaBase schema = new Schema();
            assertTrue(schema.fields().isEmpty());
        }

        @Test
        @DisplayName("Add integer field")
        void testAddIntField() {
            SchemaBase schema = new Schema();
            schema.addIntField("age");

            assertTrue(schema.hasField("age"));
            assertEquals(Types.INTEGER, schema.type("age"));
            assertEquals(Integer.BYTES, schema.length("age"));
        }

        @Test
        @DisplayName("Add string field")
        void testAddStringField() {
            SchemaBase schema = new Schema();
            schema.addStringField("name", 20);

            assertTrue(schema.hasField("name"));
            assertEquals(Types.VARCHAR, schema.type("name"));
            assertEquals(20, schema.length("name"));
        }

        @Test
        @DisplayName("Add boolean field")
        void testAddBooleanField() {
            SchemaBase schema = new Schema();
            schema.addBooleanField("active");

            assertTrue(schema.hasField("active"));
            assertEquals(Types.BOOLEAN, schema.type("active"));
            assertEquals(1, schema.length("active"));
        }

        @Test
        @DisplayName("Add double field")
        void testAddDoubleField() {
            SchemaBase schema = new Schema();
            schema.addDoubleField("salary");

            assertTrue(schema.hasField("salary"));
            assertEquals(Types.DOUBLE, schema.type("salary"));
            assertEquals(Double.BYTES, schema.length("salary"));
        }

        @Test
        @DisplayName("Invalid field name throws exception")
        void testInvalidFieldName() {
            SchemaBase schema = new Schema();
            assertThrows(IllegalArgumentException.class, () -> schema.addIntField(null));
            assertThrows(IllegalArgumentException.class, () -> schema.addIntField(""));
            assertThrows(IllegalArgumentException.class, () -> schema.addIntField("  "));
        }

        @Test
        @DisplayName("Invalid string length throws exception")
        void testInvalidStringLength() {
            SchemaBase schema = new Schema();
            assertThrows(IllegalArgumentException.class, () -> schema.addStringField("name", 0));
            assertThrows(IllegalArgumentException.class, () -> schema.addStringField("name", -1));
        }

        @Test
        @DisplayName("Fields are returned in insertion order")
        void testFieldOrder() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 10);
            schema.addBooleanField("active");

            assertEquals(3, schema.fields().size());
            assertEquals("id", schema.fields().get(0));
            assertEquals("name", schema.fields().get(1));
            assertEquals("active", schema.fields().get(2));
        }

        @Test
        @DisplayName("Access non-existent field throws exception")
        void testNonExistentField() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");

            assertFalse(schema.hasField("name"));
            assertThrows(IllegalArgumentException.class, () -> schema.type("name"));
            assertThrows(IllegalArgumentException.class, () -> schema.length("name"));
        }

        @Test
        @DisplayName("Add field from another schema")
        void testAddFromSchema() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 15);

            SchemaBase schema2 = new Schema();
            schema2.add("id", schema1);

            assertTrue(schema2.hasField("id"));
            assertEquals(Types.INTEGER, schema2.type("id"));
        }

        @Test
        @DisplayName("Add all fields from another schema")
        void testAddAllFromSchema() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 15);
            schema1.addBooleanField("active");

            SchemaBase schema2 = new Schema();
            schema2.addDoubleField("salary");
            schema2.addAll(schema1);

            assertEquals(4, schema2.fields().size());
            assertTrue(schema2.hasField("salary"));
            assertTrue(schema2.hasField("id"));
            assertTrue(schema2.hasField("name"));
            assertTrue(schema2.hasField("active"));
        }

        @Test
        @DisplayName("Schema equals - identical schemas are equal")
        void testSchemaEquals() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 20);

            SchemaBase schema2 = new Schema();
            schema2.addIntField("id");
            schema2.addStringField("name", 20);

            assertEquals(schema1, schema2);
            assertEquals(schema1.hashCode(), schema2.hashCode());
        }

        @Test
        @DisplayName("Schema equals - different field order makes them unequal")
        void testSchemaEqualsDifferentOrder() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 20);

            SchemaBase schema2 = new Schema();
            schema2.addStringField("name", 20);
            schema2.addIntField("id");

            assertNotEquals(schema1, schema2);
        }

        @Test
        @DisplayName("Schema equals - different field types makes them unequal")
        void testSchemaEqualsDifferentTypes() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("value");

            SchemaBase schema2 = new Schema();
            schema2.addDoubleField("value");

            assertNotEquals(schema1, schema2);
        }

        @Test
        @DisplayName("Schema equals - different string lengths makes them unequal")
        void testSchemaEqualsDifferentLengths() {
            SchemaBase schema1 = new Schema();
            schema1.addStringField("name", 20);

            SchemaBase schema2 = new Schema();
            schema2.addStringField("name", 30);

            assertNotEquals(schema1, schema2);
        }

        @Test
        @DisplayName("Schema equals - same schema instance equals itself")
        void testSchemaEqualsSelf() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");

            assertEquals(schema, schema);
        }

        @Test
        @DisplayName("Schema hashCode - equal schemas have same hashCode")
        void testSchemaHashCode() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 20);
            schema1.addBooleanField("active");

            SchemaBase schema2 = new Schema();
            schema2.addIntField("id");
            schema2.addStringField("name", 20);
            schema2.addBooleanField("active");

            assertEquals(schema1.hashCode(), schema2.hashCode());
        }
    }

    // ========== LAYOUT TESTS ==========

    @Nested
    @DisplayName("Layout Tests")
    class LayoutTests {

        @Test
        @DisplayName("Create layout from schema")
        void testLayoutCreation() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 10);

            LayoutBase layout = new Layout(schema);

            assertNotNull(layout.schema());
            assertEquals(2, layout.schema().fields().size());
        }

        @Test
        @DisplayName("Layout calculates correct offsets")
        void testLayoutOffsets() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");          // offset = 1 (after in-use flag)
            schema.addStringField("name", 10); // offset = 5 (1 + 4)

            LayoutBase layout = new Layout(schema);

            assertEquals(1, layout.offset("id"));
            assertEquals(5, layout.offset("name"));
        }

        @Test
        @DisplayName("Layout calculates correct slot size")
        void testSlotSize() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");          // 4 bytes
            schema.addStringField("name", 10); // 4 + 10 = 14 bytes

            LayoutBase layout = new Layout(schema);

            // 1 (in-use flag) + 4 (int) + 14 (string with length) = 19
            assertEquals(19, layout.slotSize());
        }

        @Test
        @DisplayName("Access offset for non-existent field throws exception")
        void testInvalidFieldOffset() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");

            LayoutBase layout = new Layout(schema);

            assertThrows(IllegalArgumentException.class, () -> layout.offset("name"));
        }

        @Test
        @DisplayName("Create layout with predefined offsets")
        void testLayoutWithOffsets() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");

            java.util.Map<String, Integer> offsets = new java.util.HashMap<>();
            offsets.put("id", 1);

            LayoutBase layout = new Layout(schema, offsets, 5);

            assertEquals(1, layout.offset("id"));
            assertEquals(5, layout.slotSize());
        }

        @Test
        @DisplayName("Null schema throws exception")
        void testNullSchema() {
            assertThrows(IllegalArgumentException.class, () -> new Layout(null));
        }

        @Test
        @DisplayName("Layout equals - identical layouts are equal")
        void testLayoutEquals() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 20);

            SchemaBase schema2 = new Schema();
            schema2.addIntField("id");
            schema2.addStringField("name", 20);

            LayoutBase layout1 = new Layout(schema1);
            LayoutBase layout2 = new Layout(schema2);

            assertEquals(layout1, layout2);
            assertEquals(layout1.hashCode(), layout2.hashCode());
        }

        @Test
        @DisplayName("Layout equals - same layout instance equals itself")
        void testLayoutEqualsSelf() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");

            LayoutBase layout = new Layout(schema);

            assertEquals(layout, layout);
        }

        @Test
        @DisplayName("Layout equals - different schemas make layouts unequal")
        void testLayoutEqualsDifferentSchemas() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");

            SchemaBase schema2 = new Schema();
            schema2.addIntField("id");
            schema2.addStringField("name", 20);

            LayoutBase layout1 = new Layout(schema1);
            LayoutBase layout2 = new Layout(schema2);

            assertNotEquals(layout1, layout2);
        }

        @Test
        @DisplayName("Layout equals - layouts with same logical structure are equal")
        void testLayoutEqualsLogicallyEqual() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("A");
            schema1.addStringField("B", 12);

            SchemaBase schema2 = new Schema();
            schema2.addIntField("A");
            schema2.addStringField("B", 12);

            LayoutBase layout1 = new Layout(schema1);
            LayoutBase layout2 = new Layout(schema2);

            assertEquals(layout1, layout2);
            assertEquals(layout1.hashCode(), layout2.hashCode());
        }

        @Test
        @DisplayName("Layout hashCode - consistent across multiple calls")
        void testLayoutHashCodeConsistency() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 20);

            LayoutBase layout = new Layout(schema);

            int hash1 = layout.hashCode();
            int hash2 = layout.hashCode();

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("Complex layout with all field types")
        void testComplexLayout() {
            SchemaBase schema = new Schema();
            schema.addIntField("id");
            schema.addStringField("name", 30);
            schema.addBooleanField("active");
            schema.addDoubleField("salary");
            schema.addStringField("email", 50);

            LayoutBase layout = new Layout(schema);

            // Verify offsets are calculated correctly
            assertEquals(1, layout.offset("id"));
            assertEquals(5, layout.offset("name"));
            assertEquals(39, layout.offset("active"));
            assertEquals(40, layout.offset("salary"));
            assertEquals(48, layout.offset("email"));

            // Verify slot size
            // 1 (flag) + 4 (int) + 34 (str30) + 1 (bool) + 8 (double) + 54 (str50) = 102
            assertEquals(102, layout.slotSize());
        }
    }

    // ========== RECORDPAGE TESTS ==========

    @Nested
    @DisplayName("RecordPage Tests")
    class RecordPageTests {

        @Test
        @DisplayName("Format initializes all slots to empty")
        void testFormat() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_format");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);

                rp.format();

                // All slots should be empty
                assertEquals(-1, rp.nextAfter(RecordPageBase.BEFORE_FIRST_SLOT));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert and retrieve integer")
        void testInsertAndRetrieveInt() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("age");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_int");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setInt(slot, "age", 42);

                assertEquals(42, rp.getInt(slot, "age"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert and retrieve string")
        void testInsertAndRetrieveString() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addStringField("name", 20);
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_string");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setString(slot, "name", "Alice");

                assertEquals("Alice", rp.getString(slot, "name"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert and retrieve boolean")
        void testInsertAndRetrieveBoolean() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addBooleanField("active");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_bool");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setBoolean(slot, "active", true);

                assertTrue(rp.getBoolean(slot, "active"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert and retrieve double")
        void testInsertAndRetrieveDouble() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addDoubleField("salary");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_double");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setDouble(slot, "salary", 50000.50);

                assertEquals(50000.50, rp.getDouble(slot, "salary"), 0.01);

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Delete slot marks it as empty")
        void testDelete() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_delete");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setInt(slot, "id", 100);

                rp.delete(slot);

                // Slot should now be empty
                assertThrows(IllegalStateException.class, () -> rp.getInt(slot, "id"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("nextAfter finds next in-use slot")
        void testNextAfter() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_next");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot1 = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                int slot2 = rp.insertAfter(slot1);
                int slot3 = rp.insertAfter(slot2);

                rp.setInt(slot1, "id", 1);
                rp.setInt(slot2, "id", 2);
                rp.setInt(slot3, "id", 3);

                assertEquals(slot1, rp.nextAfter(RecordPageBase.BEFORE_FIRST_SLOT));
                assertEquals(slot2, rp.nextAfter(slot1));
                assertEquals(slot3, rp.nextAfter(slot2));
                assertEquals(-1, rp.nextAfter(slot3));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Access empty slot throws IllegalStateException")
        void testAccessEmptySlot() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_empty");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                assertThrows(IllegalStateException.class, () -> rp.getInt(0, "id"));
                assertThrows(IllegalStateException.class, () -> rp.setInt(0, "id", 100));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Wrong field type throws IllegalArgumentException")
        void testWrongFieldType() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_type");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);

                assertThrows(IllegalArgumentException.class, () -> rp.getString(slot, "id"));
                assertThrows(IllegalArgumentException.class, () -> rp.setBoolean(slot, "id", true));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("String exceeding logical length throws exception")
        void testStringTooLong() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addStringField("name", 5);
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_long_string");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);

                assertThrows(IllegalArgumentException.class,
                        () -> rp.setString(slot, "name", "ThisStringIsTooLong"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Delete on already empty slot throws IllegalStateException")
        void testDeleteEmptySlot() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_delete_empty");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                // Try to delete slot 0 which is empty (never inserted)
                assertThrows(IllegalStateException.class, () -> rp.delete(0));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Delete then re-insert same slot")
        void testDeleteAndReinsert() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_reinsert");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                // Insert, delete, then reinsert
                int slot1 = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setInt(slot1, "id", 100);
                rp.delete(slot1);

                int slot2 = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                assertEquals(slot1, slot2); // Should reuse the same slot
                rp.setInt(slot2, "id", 200);

                assertEquals(200, rp.getInt(slot2, "id"));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Multiple field types in same record")
        void testMultipleFieldTypes() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("name", 20);
                schema.addBooleanField("active");
                schema.addDoubleField("score");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_multi_field");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                rp.setInt(slot, "id", 42);
                rp.setString(slot, "name", "Alice");
                rp.setBoolean(slot, "active", true);
                rp.setDouble(slot, "score", 95.5);

                assertEquals(42, rp.getInt(slot, "id"));
                assertEquals("Alice", rp.getString(slot, "name"));
                assertTrue(rp.getBoolean(slot, "active"));
                assertEquals(95.5, rp.getDouble(slot, "score"), 0.01);

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Fill entire block with records")
        void testFillBlock() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_fill");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                // Keep inserting until block is full
                int count = 0;
                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                while (slot >= 0) {
                    rp.setInt(slot, "id", count);
                    count++;
                    slot = rp.insertAfter(slot);
                }

                assertTrue(count > 0); // Should have inserted at least some records

                // Verify all records
                slot = rp.nextAfter(RecordPageBase.BEFORE_FIRST_SLOT);
                int verifyCount = 0;
                while (slot >= 0) {
                    assertEquals(verifyCount, rp.getInt(slot, "id"));
                    verifyCount++;
                    slot = rp.nextAfter(slot);
                }

                assertEquals(count, verifyCount);

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Negative slot number throws exception")
        void testNegativeSlot() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_negative");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                assertThrows(IllegalArgumentException.class, () -> rp.getInt(-5, "id"));
                assertThrows(IllegalArgumentException.class, () -> rp.setInt(-5, "id", 100));
                assertThrows(IllegalArgumentException.class, () -> rp.delete(-5));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Non-existent field name throws exception")
        void testNonExistentFieldName() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                BlockIdBase blk = tx.append("test_bad_field");
                tx.pin(blk);
                RecordPageBase rp = new RecordPage(tx, blk, layout);
                rp.format();

                int slot = rp.insertAfter(RecordPageBase.BEFORE_FIRST_SLOT);

                assertThrows(IllegalArgumentException.class, () -> rp.getInt(slot, "nonexistent"));
                assertThrows(IllegalArgumentException.class, () -> rp.setInt(slot, "nonexistent", 100));

                tx.unpin(blk);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }
    }

    // ========== TABLESCAN TESTS ==========

    @Nested
    @DisplayName("TableScan Tests")
    class TableScanTests {

        @Test
        @DisplayName("Create TableScan initializes empty table")
        void testTableScanCreation() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_table", layout);

                assertNotNull(ts.getTableFileName());
                assertFalse(ts.next()); // Empty table

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert and iterate records")
        void testInsertAndIterate() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("name", 20);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_iterate", layout);

                // Insert 5 records
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("name", "record" + i);
                }

                // Iterate and verify
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    int id = ts.getInt("id");
                    String name = ts.getString("name");
                    assertEquals("record" + id, name);
                    count++;
                }

                assertEquals(5, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Delete records")
        void testDeleteRecords() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_delete_scan", layout);

                // Insert 10 records
                for (int i = 0; i < 10; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Delete records with id < 5
                ts.beforeFirst();
                while (ts.next()) {
                    if (ts.getInt("id") < 5) {
                        ts.delete();
                    }
                }

                // Count remaining records
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    assertTrue(ts.getInt("id") >= 5);
                    count++;
                }

                assertEquals(5, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("getRid and moveToRid")
        void testRidOperations() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_rid", layout);

                // Insert records and save RID of record with id=5
                RID targetRid = null;
                for (int i = 0; i < 10; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    if (i == 5) {
                        targetRid = ts.getRid();
                    }
                }

                // Move to saved RID
                ts.moveToRid(targetRid);
                assertEquals(5, ts.getInt("id"));

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Update record values")
        void testUpdateRecords() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("status", 10);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_update", layout);

                // Insert records
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("status", "old");
                }

                // Update all records
                ts.beforeFirst();
                while (ts.next()) {
                    ts.setString("status", "new");
                }

                // Verify updates
                ts.beforeFirst();
                while (ts.next()) {
                    assertEquals("new", ts.getString("status"));
                }

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Multiple blocks are handled correctly")
        void testMultipleBlocks() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("data", 10);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_multiblock", layout);

                // Insert enough records to span multiple blocks
                int nRecords = 50;
                for (int i = 0; i < nRecords; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("data", "rec" + i);
                }

                // Verify all records
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    assertEquals(count, ts.getInt("id"));
                    count++;
                }

                assertEquals(nRecords, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("getVal and setVal with Datum")
        void testDatumOperations() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("name", 15);
                schema.addBooleanField("active");
                schema.addDoubleField("score");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_datum", layout);

                ts.insert();
                ts.setVal("id", new Datum(42));
                ts.setVal("name", new Datum("Alice"));
                ts.setVal("active", new Datum(true));
                ts.setVal("score", new Datum(95.5));

                ts.beforeFirst();
                assertTrue(ts.next());

                assertEquals(42, ts.getVal("id").asInt());
                assertEquals("Alice", ts.getVal("name").asString());
                assertTrue(ts.getVal("active").asBoolean());
                assertEquals(95.5, ts.getVal("score").asDouble(), 0.01);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("hasField and getType")
        void testFieldQueries() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("name", 20);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_queries", layout);

                assertTrue(ts.hasField("id"));
                assertTrue(ts.hasField("name"));
                assertFalse(ts.hasField("nonexistent"));

                assertEquals(Types.INTEGER, ts.getType("id"));
                assertEquals(Types.VARCHAR, ts.getType("name"));

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("beforeFirst resets iteration")
        void testBeforeFirstReset() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_reset", layout);

                // Insert records
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // First iteration
                ts.beforeFirst();
                int count1 = 0;
                while (ts.next()) {
                    count1++;
                }

                // Second iteration after reset
                ts.beforeFirst();
                int count2 = 0;
                while (ts.next()) {
                    count2++;
                }

                assertEquals(count1, count2);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Delete during iteration")
        void testDeleteDuringIteration() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_delete_iter", layout);

                // Insert 10 records
                for (int i = 0; i < 10; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Delete every other record
                ts.beforeFirst();
                while (ts.next()) {
                    if (ts.getInt("id") % 2 == 0) {
                        ts.delete();
                    }
                }

                // Count remaining
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    assertTrue(ts.getInt("id") % 2 != 0);
                    count++;
                }

                assertEquals(5, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Insert after deleting all records")
        void testInsertAfterDeleteAll() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_delete_all", layout);

                // Insert 5 records
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Delete all
                ts.beforeFirst();
                while (ts.next()) {
                    ts.delete();
                }

                // Verify empty
                ts.beforeFirst();
                assertFalse(ts.next());

                // Insert new records
                for (int i = 100; i < 103; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Verify new records
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    assertTrue(ts.getInt("id") >= 100);
                    count++;
                }
                assertEquals(3, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("RID persists across table scan operations")
        void testRidPersistence() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("data", 20);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_rid_persist", layout);

                // Insert and save multiple RIDs
                RID[] rids = new RID[5];
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("data", "record" + i);
                    rids[i] = ts.getRid();
                }

                // Access records in random order using RIDs
                ts.moveToRid(rids[3]);
                assertEquals(3, ts.getInt("id"));
                assertEquals("record3", ts.getString("data"));

                ts.moveToRid(rids[0]);
                assertEquals(0, ts.getInt("id"));

                ts.moveToRid(rids[4]);
                assertEquals(4, ts.getInt("id"));

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Empty string handling")
        void testEmptyString() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addStringField("data", 20);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_empty_str", layout);

                ts.insert();
                ts.setString("data", "");

                ts.beforeFirst();
                assertTrue(ts.next());
                assertEquals("", ts.getString("data"));

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Boundary values for all types")
        void testBoundaryValues() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("minInt");
                schema.addIntField("maxInt");
                schema.addDoubleField("minDouble");
                schema.addDoubleField("maxDouble");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "test_boundary", layout);

                ts.insert();
                ts.setInt("minInt", Integer.MIN_VALUE);
                ts.setInt("maxInt", Integer.MAX_VALUE);
                ts.setDouble("minDouble", Double.MIN_VALUE);
                ts.setDouble("maxDouble", Double.MAX_VALUE);

                ts.beforeFirst();
                assertTrue(ts.next());
                assertEquals(Integer.MIN_VALUE, ts.getInt("minInt"));
                assertEquals(Integer.MAX_VALUE, ts.getInt("maxInt"));
                assertEquals(Double.MIN_VALUE, ts.getDouble("minDouble"), 0.0);
                assertEquals(Double.MAX_VALUE, ts.getDouble("maxDouble"), 0.0);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }
    }

    // ========== INTEGRATION TESTS ==========

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Complete workflow: insert, read, update, delete")
        void testCompleteWorkflow() {
            TxBase tx = txMgr.newTx();
            try {
                // Create schema with multiple field types
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("name", 30);
                schema.addBooleanField("active");
                schema.addDoubleField("balance");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "workflow_test", layout);

                // INSERT: Create 20 records
                for (int i = 0; i < 20; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("name", "User" + i);
                    ts.setBoolean("active", i % 2 == 0);
                    ts.setDouble("balance", i * 100.0);
                }

                // READ: Verify all records
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    count++;
                }
                assertEquals(20, count);

                // UPDATE: Deactivate users with balance > 1000
                ts.beforeFirst();
                int updated = 0;
                while (ts.next()) {
                    if (ts.getDouble("balance") > 1000.0) {
                        ts.setBoolean("active", false);
                        updated++;
                    }
                }
                assertTrue(updated > 0);

                // DELETE: Remove inactive users
                ts.beforeFirst();
                int deleted = 0;
                while (ts.next()) {
                    if (!ts.getBoolean("active")) {
                        ts.delete();
                        deleted++;
                    }
                }
                assertTrue(deleted > 0);

                // VERIFY: Count remaining records
                ts.beforeFirst();
                int remaining = 0;
                while (ts.next()) {
                    assertTrue(ts.getBoolean("active"));
                    assertTrue(ts.getDouble("balance") <= 1000.0);
                    remaining++;
                }
                assertEquals(20 - deleted, remaining);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Schema evolution: add fields to existing schema")
        void testSchemaEvolution() {
            SchemaBase schema1 = new Schema();
            schema1.addIntField("id");
            schema1.addStringField("name", 20);

            SchemaBase schema2 = new Schema();
            schema2.addAll(schema1);
            schema2.addBooleanField("verified");
            schema2.addDoubleField("rating");

            assertEquals(4, schema2.fields().size());
            assertTrue(schema2.hasField("id"));
            assertTrue(schema2.hasField("name"));
            assertTrue(schema2.hasField("verified"));
            assertTrue(schema2.hasField("rating"));
        }

        @Test
        @DisplayName("Transaction rollback preserves data integrity")
        void testTransactionRollback() {
            TxBase tx1 = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx1, "rollback_test", layout);

                // Insert initial records
                for (int i = 0; i < 5; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                ts.close();
                tx1.commit();
            } catch (Exception e) {
                tx1.rollback();
                throw e;
            }

            // Start new transaction and add more records
            TxBase tx2 = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx2, "rollback_test", layout);

                // Add 5 more records
                for (int i = 5; i < 10; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                ts.close();
                tx2.rollback(); // Rollback these additions
            } catch (Exception e) {
                tx2.rollback();
            }

            // Verify only original 5 records exist
            TxBase tx3 = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx3, "rollback_test", layout);

                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    assertTrue(ts.getInt("id") < 5);
                    count++;
                }
                assertEquals(5, count);

                ts.close();
                tx3.commit();
            } catch (Exception e) {
                tx3.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Large dataset handling")
        void testLargeDataset() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                schema.addStringField("description", 50);
                schema.addDoubleField("value");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "large_dataset", layout);

                // Insert 100 records
                int nRecords = 100;
                for (int i = 0; i < nRecords; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                    ts.setString("description", "Record number " + i);
                    ts.setDouble("value", i * 1.5);
                }

                // Verify all records
                ts.beforeFirst();
                int count = 0;
                int sum = 0;
                while (ts.next()) {
                    sum += ts.getInt("id");
                    count++;
                }

                assertEquals(nRecords, count);
                assertEquals((nRecords * (nRecords - 1)) / 2, sum);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Record reuse after deletion")
        void testRecordReuse() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "reuse_test", layout);

                // Fill first block
                for (int i = 0; i < 15; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Delete some records
                ts.beforeFirst();
                while (ts.next()) {
                    if (ts.getInt("id") % 3 == 0) {
                        ts.delete();
                    }
                }

                // Insert new records (should reuse deleted slots)
                for (int i = 100; i < 105; i++) {
                    ts.insert();
                    ts.setInt("id", i);
                }

                // Verify we have the expected records
                ts.beforeFirst();
                int count = 0;
                while (ts.next()) {
                    count++;
                }

                // Started with 15, deleted 5 (0,3,6,9,12), added 5 = 15 total
                assertEquals(15, count);

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Empty table operations")
        void testEmptyTable() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addIntField("id");
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "empty_test", layout);

                // Verify empty table
                ts.beforeFirst();
                assertFalse(ts.next());

                // beforeFirst multiple times should work
                ts.beforeFirst();
                ts.beforeFirst();
                assertFalse(ts.next());

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

        @Test
        @DisplayName("Maximum length strings")
        void testMaxLengthStrings() {
            TxBase tx = txMgr.newTx();
            try {
                SchemaBase schema = new Schema();
                schema.addStringField("data", 100);
                LayoutBase layout = new Layout(schema);

                TableScanBase ts = new TableScan(tx, "maxstring_test", layout);

                // Create a string at exactly the max length
                String maxString = "x".repeat(100);

                ts.insert();
                ts.setString("data", maxString);

                ts.beforeFirst();
                assertTrue(ts.next());
                assertEquals(maxString, ts.getString("data"));

                ts.close();
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }
    }
}