package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferBase;
import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LogRecord classes - serialization, deserialization, and undo functionality.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LogRecordTest {

    private static File dbDirectory;
    private FileMgrBase fm;
    private BufferMgrBase bm;
    private BlockIdBase testBlock;

    @BeforeAll
    static void setUpClass() throws Exception {
        dbDirectory = Files.createTempDirectory("logrecord_test").toFile();
    }

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "test.log");
        bm = new BufferMgr(fm, lm, 8, 10000);
        testBlock = new BlockId("testfile", 0);
        fm.append("testfile");
    }

    @AfterEach
    void tearDown() {
        // Clean up
    }

    @AfterAll
    static void tearDownClass() {
        if (dbDirectory != null && dbDirectory.exists()) {
            deleteDirectory(dbDirectory);
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
    @DisplayName("Test CheckpointLogRecord serialization and deserialization")
    void testCheckpointLogRecord() {
        // Create a checkpoint record
        CheckpointLogRecord original = new CheckpointLogRecord();

        // Verify fields
        assertEquals(LogRecord.CHECKPOINT, original.op());
        assertEquals(-1, original.txNumber());
        assertNull(original.getBlock());

        // Serialize
        byte[] bytes = original.toBytes();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        // Deserialize
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertNotNull(deserialized);
        assertInstanceOf(CheckpointLogRecord.class, deserialized);
        assertEquals(LogRecord.CHECKPOINT, deserialized.op());
        assertEquals(-1, deserialized.txNumber());

        // Test toString
        assertNotNull(original.toString());
        assertTrue(original.toString().contains("CHECKPOINT"));
    }

    @Test
    @Order(2)
    @DisplayName("Test StartLogRecord serialization and deserialization")
    void testStartLogRecord() {
        int txnum = 42;

        // Create a start record
        StartLogRecord original = new StartLogRecord(txnum);

        // Verify fields
        assertEquals(LogRecord.START, original.op());
        assertEquals(txnum, original.txNumber());
        assertNull(original.getBlock());

        // Serialize
        byte[] bytes = original.toBytes();
        assertNotNull(bytes);

        // Deserialize
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertNotNull(deserialized);
        assertInstanceOf(StartLogRecord.class, deserialized);
        assertEquals(LogRecord.START, deserialized.op());
        assertEquals(txnum, deserialized.txNumber());

        // Test toString
        assertNotNull(original.toString());
        assertTrue(original.toString().contains("START"));
        assertTrue(original.toString().contains("42"));
    }

    @Test
    @Order(3)
    @DisplayName("Test CommitLogRecord serialization and deserialization")
    void testCommitLogRecord() {
        int txnum = 100;

        // Create a commit record
        CommitLogRecord original = new CommitLogRecord(txnum);

        // Verify fields
        assertEquals(LogRecord.COMMIT, original.op());
        assertEquals(txnum, original.txNumber());
        assertNull(original.getBlock());

        // Serialize
        byte[] bytes = original.toBytes();
        assertNotNull(bytes);

        // Deserialize
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertNotNull(deserialized);
        assertInstanceOf(CommitLogRecord.class, deserialized);
        assertEquals(LogRecord.COMMIT, deserialized.op());
        assertEquals(txnum, deserialized.txNumber());

        // Test toString
        assertNotNull(original.toString());
        assertTrue(original.toString().contains("COMMIT"));
    }

    @Test
    @Order(4)
    @DisplayName("Test RollbackLogRecord serialization and deserialization")
    void testRollbackLogRecord() {
        int txnum = 99;

        // Create a rollback record
        RollbackLogRecord original = new RollbackLogRecord(txnum);

        // Verify fields
        assertEquals(LogRecord.ROLLBACK, original.op());
        assertEquals(txnum, original.txNumber());
        assertNull(original.getBlock());

        // Serialize
        byte[] bytes = original.toBytes();
        assertNotNull(bytes);

        // Deserialize
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertNotNull(deserialized);
        assertInstanceOf(RollbackLogRecord.class, deserialized);
        assertEquals(LogRecord.ROLLBACK, deserialized.op());
        assertEquals(txnum, deserialized.txNumber());
    }

    @Test
    @Order(5)
    @DisplayName("Test SetIntLogRecord serialization and deserialization")
    void testSetIntLogRecord() {
        int txnum = 1;
        int offset = 80;
        int oldValue = 42;

        // Create a SetInt record
        SetIntLogRecord original = new SetIntLogRecord(txnum, testBlock, offset, oldValue);

        // Verify fields
        assertEquals(LogRecord.SETINT, original.op());
        assertEquals(txnum, original.txNumber());
        assertEquals(testBlock, original.getBlock());

        // Serialize
        byte[] bytes = original.toBytes();
        assertNotNull(bytes);

        // Deserialize
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertNotNull(deserialized);
        assertInstanceOf(SetIntLogRecord.class, deserialized);
        assertEquals(LogRecord.SETINT, deserialized.op());
        assertEquals(txnum, deserialized.txNumber());
        assertEquals(testBlock.fileName(), deserialized.getBlock().fileName());
        assertEquals(testBlock.number(), deserialized.getBlock().number());
    }

    @Test
    @Order(6)
    @DisplayName("Test SetIntLogRecord undo functionality")
    void testSetIntLogRecordUndo() {
        int txnum = 1;
        int offset = 100;
        int oldValue = 999;
        int newValue = 111;

        // Pin buffer and set new value
        BufferBase buff = bm.pin(testBlock);
        buff.contents().setInt(offset, newValue);

        // Verify new value is set
        assertEquals(newValue, buff.contents().getInt(offset));

        // Create undo record with old value
        SetIntLogRecord record = new SetIntLogRecord(txnum, testBlock, offset, oldValue);

        // Perform undo
        record.undo(buff);

        // Verify old value is restored
        assertEquals(oldValue, buff.contents().getInt(offset));

        bm.unpin(buff);
    }

    @Test
    @Order(7)
    @DisplayName("Test SetBooleanLogRecord serialization and undo")
    void testSetBooleanLogRecord() {
        int txnum = 2;
        int offset = 50;
        boolean oldValue = true;
        boolean newValue = false;

        // Create record
        SetBooleanLogRecord original = new SetBooleanLogRecord(txnum, testBlock, offset, oldValue);

        // Verify fields
        assertEquals(LogRecord.SETBOOLEAN, original.op());
        assertEquals(txnum, original.txNumber());

        // Test serialization
        byte[] bytes = original.toBytes();
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertInstanceOf(SetBooleanLogRecord.class, deserialized);
        assertEquals(LogRecord.SETBOOLEAN, deserialized.op());

        // Test undo
        BufferBase buff = bm.pin(testBlock);
        buff.contents().setBoolean(offset, newValue);
        assertEquals(newValue, buff.contents().getBoolean(offset));

        original.undo(buff);
        assertEquals(oldValue, buff.contents().getBoolean(offset));

        bm.unpin(buff);
    }

    @Test
    @Order(8)
    @DisplayName("Test SetDoubleLogRecord serialization and undo")
    void testSetDoubleLogRecord() {
        int txnum = 3;
        int offset = 120;
        double oldValue = 3.14159;
        double newValue = 2.71828;

        // Create record
        SetDoubleLogRecord original = new SetDoubleLogRecord(txnum, testBlock, offset, oldValue);

        // Verify fields
        assertEquals(LogRecord.SETDOUBLE, original.op());
        assertEquals(txnum, original.txNumber());

        // Test serialization
        byte[] bytes = original.toBytes();
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertInstanceOf(SetDoubleLogRecord.class, deserialized);

        // Test undo
        BufferBase buff = bm.pin(testBlock);
        buff.contents().setDouble(offset, newValue);
        assertEquals(newValue, buff.contents().getDouble(offset), 0.0001);

        original.undo(buff);
        assertEquals(oldValue, buff.contents().getDouble(offset), 0.0001);

        bm.unpin(buff);
    }

    @Test
    @Order(9)
    @DisplayName("Test SetStringLogRecord serialization and undo")
    void testSetStringLogRecord() {
        int txnum = 4;
        int offset = 200;
        String oldValue = "original";
        String newValue = "modified";

        // Create record
        SetStringLogRecord original = new SetStringLogRecord(txnum, testBlock, offset, oldValue);

        // Verify fields
        assertEquals(LogRecord.SETSTRING, original.op());
        assertEquals(txnum, original.txNumber());

        // Test serialization
        byte[] bytes = original.toBytes();
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertInstanceOf(SetStringLogRecord.class, deserialized);

        // Test undo
        BufferBase buff = bm.pin(testBlock);
        buff.contents().setString(offset, newValue);
        assertEquals(newValue, buff.contents().getString(offset));

        original.undo(buff);
        assertEquals(oldValue, buff.contents().getString(offset));

        bm.unpin(buff);
    }

    @Test
    @Order(10)
    @DisplayName("Test SetBytesLogRecord serialization and undo")
    void testSetBytesLogRecord() {
        int txnum = 5;
        int offset = 250;
        byte[] oldValue = new byte[]{1, 2, 3, 4, 5};
        byte[] newValue = new byte[]{6, 7, 8, 9, 10};

        // Create record
        SetBytesLogRecord original = new SetBytesLogRecord(txnum, testBlock, offset, oldValue);

        // Verify fields
        assertEquals(LogRecord.SETBYTES, original.op());
        assertEquals(txnum, original.txNumber());

        // Test serialization
        byte[] bytes = original.toBytes();
        LogRecord deserialized = LogRecord.createLogRecord(bytes);
        assertInstanceOf(SetBytesLogRecord.class, deserialized);

        // Test undo
        BufferBase buff = bm.pin(testBlock);
        buff.contents().setBytes(offset, newValue);
        assertArrayEquals(newValue, buff.contents().getBytes(offset));

        original.undo(buff);
        assertArrayEquals(oldValue, buff.contents().getBytes(offset));

        bm.unpin(buff);
    }

    @Test
    @Order(11)
    @DisplayName("Test invalid buffer validation in SetIntLogRecord")
    void testInvalidBufferValidation() {
        int txnum = 1;
        int offset = 80;
        int oldValue = 42;

        SetIntLogRecord record = new SetIntLogRecord(txnum, testBlock, offset, oldValue);

        // Test with null buffer
        assertThrows(IllegalArgumentException.class, () -> {
            record.undo(null);
        });

        // Test with buffer for wrong block
        BlockIdBase wrongBlock = new BlockId("otherfile", 1);
        fm.append("otherfile");
        BufferBase wrongBuff = bm.pin(wrongBlock);

        assertThrows(IllegalArgumentException.class, () -> {
            record.undo(wrongBuff);
        });

        bm.unpin(wrongBuff);
    }

    @Test
    @Order(12)
    @DisplayName("Test unknown log record type")
    void testUnknownLogRecordType() {
        // Create a byte array with an invalid operation type
        byte[] invalidBytes = new byte[4];
        invalidBytes[0] = 0;
        invalidBytes[1] = 0;
        invalidBytes[2] = 0;
        invalidBytes[3] = 99; // Invalid operation type

        assertThrows(IllegalArgumentException.class, () -> {
            LogRecord.createLogRecord(invalidBytes);
        });
    }

    @Test
    @Order(13)
    @DisplayName("Test all record types toString methods")
    void testToStringMethods() {
        assertNotNull(new CheckpointLogRecord().toString());
        assertNotNull(new StartLogRecord(1).toString());
        assertNotNull(new CommitLogRecord(2).toString());
        assertNotNull(new RollbackLogRecord(3).toString());
        assertNotNull(new SetIntLogRecord(4, testBlock, 80, 42).toString());
        assertNotNull(new SetBooleanLogRecord(5, testBlock, 50, true).toString());
        assertNotNull(new SetDoubleLogRecord(6, testBlock, 100, 3.14).toString());
        assertNotNull(new SetStringLogRecord(7, testBlock, 200, "test").toString());
        assertNotNull(new SetBytesLogRecord(8, testBlock, 300, new byte[]{1, 2}).toString());
    }
}