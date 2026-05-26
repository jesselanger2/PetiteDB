package edu.yu.dbimpl.buffer;

import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.LogMgr;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite specifically for the Buffer class.
 * Tests cover Buffer-specific functionality including:
 * - Contents management
 * - Block association
 * - Modification tracking
 * - Pin state management
 * - Edge cases
 */
class BufferTest {

    @TempDir
    File tempDir;

    private FileMgr fm;
    private LogMgr lm;
    private static final int BLOCK_SIZE = 400;
    private static final String LOG_FILE = "testlog";
    private final String dbDirectoryName = "buffertest_db";

    // Helper to configure the DB for a fresh start or a restart
    private void configureDB(boolean isNew) {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, String.valueOf(isNew));
        DBConfiguration.INSTANCE.get().setConfiguration(props);
    }

    @BeforeEach
    void setUp() throws IOException {
        // This ensures every test starts with a clean slate
        configureDB(true);
        File dbDir = new File(dbDirectoryName);
        // Clean up any old directories before the test
        if (dbDir.exists()) {
            deleteDirectory(dbDir.toPath());
        }
        fm = new FileMgr(tempDir, BLOCK_SIZE);
        lm = new LogMgr(fm, LOG_FILE);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up after each test to prevent interference
        File dbDir = new File(dbDirectoryName);
        if (dbDir.exists()) {
            deleteDirectory(dbDir.toPath());
        }
    }

    // Helper utility to clean up directories
    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); // delete files as we encounter them
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir); // delete directory after its contents
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("Constructor: Creates valid buffer")
    void testConstructor() {
        Buffer buffer = new Buffer(fm, lm);
        assertNotNull(buffer);
        assertNotNull(buffer.contents());
        assertNull(buffer.block());
        assertFalse(buffer.isPinned());
    }

    // ==================== Contents Tests ====================

    @Test
    @DisplayName("Contents: Returns non-null page")
    void testContentsNonNull() {
        Buffer buffer = new Buffer(fm, lm);
        PageBase page = buffer.contents();
        assertNotNull(page);
    }

    @Test
    @DisplayName("Contents: Page is writable")
    void testContentsWritable() {
        Buffer buffer = new Buffer(fm, lm);
        PageBase page = buffer.contents();

        page.setString(0, "Test String");
        page.setInt(100, 42);
        page.setDouble(150, 3.14159);
        page.setBoolean(200, true);

        assertEquals("Test String", page.getString(0));
        assertEquals(42, page.getInt(100));
        assertEquals(3.14159, page.getDouble(150), 0.00001);
        assertTrue(page.getBoolean(200));
    }

    @Test
    @DisplayName("Contents: Same page returned on multiple calls")
    void testContentsSameInstance() {
        Buffer buffer = new Buffer(fm, lm);
        PageBase page1 = buffer.contents();
        PageBase page2 = buffer.contents();

        assertSame(page1, page2);
    }

    @Test
    @DisplayName("Contents: Page size matches block size")
    void testContentsSize() {
        Buffer buffer = new Buffer(fm, lm);
        PageBase page = buffer.contents();

        // Should be able to write near the end of the page
        assertDoesNotThrow(() -> page.setInt(BLOCK_SIZE - Integer.BYTES, 123));
    }

    // ==================== Block Association Tests ====================

    @Test
    @DisplayName("Block: Initially null")
    void testBlockInitiallyNull() {
        Buffer buffer = new Buffer(fm, lm);
        assertNull(buffer.block());
    }

    @Test
    @DisplayName("Block: Returns assigned block after assignment")
    void testBlockAfterAssignment() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 5);

        BufferBase buffer = bm.pin(blk);
        assertEquals(blk, buffer.block());
        assertNotNull(buffer.block());
        assertEquals("testfile", buffer.block().fileName());
        assertEquals(5, buffer.block().number());
    }

    @Test
    @DisplayName("Block: Changes when reassigned")
    void testBlockReassignment() {
        BufferMgr bm = new BufferMgr(fm, lm, 1, 1000);

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buffer = bm.pin(blk1);
        assertEquals(blk1, buffer.block());
        bm.unpin(buffer);

        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buffer2 = bm.pin(blk2);
        assertEquals(blk2, buffer2.block());
    }

    // ==================== setModified Tests ====================

    @Test
    @DisplayName("setModified: Valid parameters")
    void testSetModifiedValid() {
        Buffer buffer = new Buffer(fm, lm);
        // Use -1 for LSN when not creating actual log records
        assertDoesNotThrow(() -> buffer.setModified(1, -1));
    }

    @Test
    @DisplayName("setModified: Transaction number zero is valid")
    void testSetModifiedTxnumZero() {
        Buffer buffer = new Buffer(fm, lm);
        assertDoesNotThrow(() -> buffer.setModified(0, -1));
    }

    @Test
    @DisplayName("setModified: Negative transaction number throws exception")
    void testSetModifiedNegativeTxnum() {
        Buffer buffer = new Buffer(fm, lm);
        assertThrows(IllegalArgumentException.class, () -> buffer.setModified(-1, -1));
        assertThrows(IllegalArgumentException.class, () -> buffer.setModified(-999, -1));
    }

    @Test
    @DisplayName("setModified: Negative LSN is valid")
    void testSetModifiedNegativeLSN() {
        Buffer buffer = new Buffer(fm, lm);
        assertDoesNotThrow(() -> buffer.setModified(1, -1));
        assertDoesNotThrow(() -> buffer.setModified(1, -999));
    }

    @Test
    @DisplayName("setModified: LSN zero is valid")
    void testSetModifiedLSNZero() {
        Buffer buffer = new Buffer(fm, lm);
        // Create an actual log record first
        int lsn = lm.append(new byte[]{1, 2, 3});
        assertDoesNotThrow(() -> buffer.setModified(1, lsn));
    }

    @Test
    @DisplayName("setModified: Large transaction numbers")
    void testSetModifiedLargeTxnum() {
        Buffer buffer = new Buffer(fm, lm);
        assertDoesNotThrow(() -> buffer.setModified(Integer.MAX_VALUE, -1));
    }

    @Test
    @DisplayName("setModified: Large LSN values")
    void testSetModifiedLargeLSN() {
        Buffer buffer = new Buffer(fm, lm);
        // Create actual log records
        for (int i = 0; i < 10; i++) {
            lm.append(new byte[]{(byte) i});
        }
        assertDoesNotThrow(() -> buffer.setModified(1, 9));
    }

    @Test
    @DisplayName("setModified: Multiple calls on same buffer")
    void testSetModifiedMultipleCalls() {
        Buffer buffer = new Buffer(fm, lm);

        // Use -1 for all LSNs
        buffer.setModified(1, -1);
        buffer.setModified(1, -1);
        buffer.setModified(1, -1);
        buffer.setModified(2, -1);
        buffer.setModified(3, -1);

        // All calls should succeed without exception
    }

    @Test
    @DisplayName("setModified: Different transactions on same buffer")
    void testSetModifiedDifferentTransactions() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        buffer.setModified(1, -1);
        buffer.setModified(2, -1);
        buffer.setModified(3, -1);

        // Last transaction should be tracked
        bm.unpin(buffer);
    }

    // ==================== isPinned Tests ====================

    @Test
    @DisplayName("isPinned: Initially false")
    void testIsPinnedInitially() {
        Buffer buffer = new Buffer(fm, lm);
        assertFalse(buffer.isPinned());
    }

    @Test
    @DisplayName("isPinned: True after pin")
    void testIsPinnedAfterPin() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        assertTrue(buffer.isPinned());
    }

    @Test
    @DisplayName("isPinned: False after unpin")
    void testIsPinnedAfterUnpin() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        bm.unpin(buffer);
        assertFalse(buffer.isPinned());
    }

    @Test
    @DisplayName("isPinned: True after multiple pins")
    void testIsPinnedMultiplePins() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer1 = bm.pin(blk);
        BufferBase buffer2 = bm.pin(blk);
        BufferBase buffer3 = bm.pin(blk);

        assertTrue(buffer1.isPinned());

        bm.unpin(buffer1);
        assertTrue(buffer1.isPinned());

        bm.unpin(buffer2);
        assertTrue(buffer1.isPinned());

        bm.unpin(buffer3);
        assertFalse(buffer1.isPinned());
    }

    @Test
    @DisplayName("isPinned: Transitions correctly")
    void testIsPinnedTransitions() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        assertTrue(buffer.isPinned());

        bm.unpin(buffer);
        assertFalse(buffer.isPinned());

        BufferBase buffer2 = bm.pin(blk);
        assertTrue(buffer2.isPinned());
        assertSame(buffer, buffer2);
    }

    // ==================== Data Persistence Tests ====================

    @Test
    @DisplayName("Data: Persists within same pin session")
    void testDataPersistsSamePin() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        buffer.contents().setString(0, "Persistent");
        buffer.contents().setInt(100, 42);

        assertEquals("Persistent", buffer.contents().getString(0));
        assertEquals(42, buffer.contents().getInt(100));

        bm.unpin(buffer);
    }

    @Test
    @DisplayName("Data: Persists across unpin/repin without flush")
    void testDataPersistsAcrossUnpin() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer1 = bm.pin(blk);
        buffer1.contents().setString(0, "Data");
        buffer1.contents().setInt(100, 123);
        buffer1.setModified(1, -1);
        bm.unpin(buffer1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals("Data", buffer2.contents().getString(0));
        assertEquals(123, buffer2.contents().getInt(100));
    }

    @Test
    @DisplayName("Data: Persists after flush")
    void testDataPersistsAfterFlush() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer1 = bm.pin(blk);
        buffer1.contents().setString(0, "Flushed Data");
        buffer1.contents().setInt(100, 999);
        buffer1.setModified(1, -1);
        bm.unpin(buffer1);

        bm.flushAll(1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals("Flushed Data", buffer2.contents().getString(0));
        assertEquals(999, buffer2.contents().getInt(100));
    }

    @Test
    @DisplayName("Data: Persists after buffer eviction")
    void testDataPersistsAfterEviction() {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 1000);

        // Write to block 0
        BlockId blk0 = new BlockId("testfile", 0);
        BufferBase buffer0 = bm.pin(blk0);
        buffer0.contents().setString(0, "Block0");
        buffer0.setModified(1, -1);
        bm.unpin(buffer0);

        // Fill buffer pool to force eviction
        BlockId blk1 = new BlockId("testfile", 1);
        BufferBase buffer1 = bm.pin(blk1);
        buffer1.contents().setString(0, "Block1");
        bm.unpin(buffer1);

        BlockId blk2 = new BlockId("testfile", 2);
        BufferBase buffer2 = bm.pin(blk2);
        buffer2.contents().setString(0, "Block2");
        bm.unpin(buffer2);

        // Repin block 0 - should read from disk
        BufferBase verifyBuffer = bm.pin(blk0);
        assertEquals("Block0", verifyBuffer.contents().getString(0));
    }

    // ==================== Integration Tests ====================

    @Test
    @DisplayName("Integration: Multiple buffers with different data")
    void testMultipleBuffersDifferentData() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);

        for (int i = 0; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            buffer.contents().setString(0, "Buffer" + i);
            buffer.contents().setInt(100, i * 100);
            buffer.setModified(1, -1);
            bm.unpin(buffer);
        }

        // Verify all data
        for (int i = 0; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            assertEquals("Buffer" + i, buffer.contents().getString(0));
            assertEquals(i * 100, buffer.contents().getInt(100));
            bm.unpin(buffer);
        }
    }

    @Test
    @DisplayName("Integration: Complex data types")
    void testComplexDataTypes() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);

        // Write various data types
        buffer.contents().setString(0, "Complex Test");
        buffer.contents().setInt(50, Integer.MIN_VALUE);
        buffer.contents().setInt(60, Integer.MAX_VALUE);
        buffer.contents().setDouble(70, Double.MIN_VALUE);
        buffer.contents().setDouble(80, Double.MAX_VALUE);
        buffer.contents().setBoolean(90, true);
        buffer.contents().setBoolean(91, false);
        buffer.contents().setBytes(100, new byte[]{1, 2, 3, 4, 5});

        buffer.setModified(1, -1);
        bm.unpin(buffer);

        bm.flushAll(1);

        // Verify
        BufferBase buffer2 = bm.pin(blk);
        assertEquals("Complex Test", buffer2.contents().getString(0));
        assertEquals(Integer.MIN_VALUE, buffer2.contents().getInt(50));
        assertEquals(Integer.MAX_VALUE, buffer2.contents().getInt(60));
        assertEquals(Double.MIN_VALUE, buffer2.contents().getDouble(70), 0.0);
        assertEquals(Double.MAX_VALUE, buffer2.contents().getDouble(80), 0.0);
        assertTrue(buffer2.contents().getBoolean(90));
        assertFalse(buffer2.contents().getBoolean(91));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, buffer2.contents().getBytes(100));
    }

    @Test
    @DisplayName("Integration: Sequential writes across page boundary")
    void testSequentialWrites() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);

        // Write data sequentially across the buffer
        int offset = 0;
        for (int i = 0; i < 10; i++) {
            buffer.contents().setString(offset, "Entry" + i);
            offset += 20; // Space for string and length
        }

        buffer.setModified(1, -1);
        bm.unpin(buffer);
        bm.flushAll(1);

        // Verify
        BufferBase buffer2 = bm.pin(blk);
        offset = 0;
        for (int i = 0; i < 10; i++) {
            assertEquals("Entry" + i, buffer2.contents().getString(offset));
            offset += 20;
        }
    }

    // ==================== Edge Case Tests ====================

    @Test
    @DisplayName("Edge: Empty string in buffer")
    void testEmptyString() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        buffer.contents().setString(0, "");
        buffer.setModified(1, -1);
        bm.unpin(buffer);

        bm.flushAll(1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals("", buffer2.contents().getString(0));
    }

    @Test
    @DisplayName("Edge: Very long string")
    void testLongString() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        String longString = "LongStr".repeat(50);

        BufferBase buffer = bm.pin(blk);
        buffer.contents().setString(0, longString);
        buffer.setModified(1, -1);
        bm.unpin(buffer);

        bm.flushAll(1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals(longString, buffer2.contents().getString(0));
    }

    @Test
    @DisplayName("Edge: Zero values")
    void testZeroValues() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        buffer.contents().setInt(0, 0);
        buffer.contents().setDouble(10, 0.0);
        buffer.contents().setBoolean(20, false);
        buffer.setModified(1, -1);
        bm.unpin(buffer);

        bm.flushAll(1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals(0, buffer2.contents().getInt(0));
        assertEquals(0.0, buffer2.contents().getDouble(10), 0.0);
        assertFalse(buffer2.contents().getBoolean(20));
    }

    @Test
    @DisplayName("Edge: Negative values")
    void testNegativeValues() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        buffer.contents().setInt(0, -12345);
        buffer.contents().setDouble(10, -3.14159);
        buffer.setModified(1, -1);
        bm.unpin(buffer);

        bm.flushAll(1);

        BufferBase buffer2 = bm.pin(blk);
        assertEquals(-12345, buffer2.contents().getInt(0));
        assertEquals(-3.14159, buffer2.contents().getDouble(10), 0.00001);
    }

    @Test
    @DisplayName("Edge: Overwrite existing data")
    void testOverwriteData() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // Write initial data
        BufferBase buffer1 = bm.pin(blk);
        buffer1.contents().setString(0, "Original");
        buffer1.setModified(1, -1);
        bm.unpin(buffer1);
        bm.flushAll(1);

        // Overwrite
        BufferBase buffer2 = bm.pin(blk);
        buffer2.contents().setString(0, "Overwritten");
        buffer2.setModified(2, -1);
        bm.unpin(buffer2);
        bm.flushAll(2);

        // Verify overwrite
        BufferBase buffer3 = bm.pin(blk);
        assertEquals("Overwritten", buffer3.contents().getString(0));
    }

    @Test
    @DisplayName("Edge: Multiple modifications before flush")
    void testMultipleModificationsBeforeFlush() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);

        // Multiple writes (use -1 for LSN to indicate no log record)
        buffer.contents().setString(0, "First");
        buffer.setModified(1, -1);

        buffer.contents().setString(0, "Second");
        buffer.setModified(1, -1);

        buffer.contents().setString(0, "Third");
        buffer.setModified(1, -1);

        bm.unpin(buffer);
        bm.flushAll(1);

        // Should have final value
        BufferBase buffer2 = bm.pin(blk);
        assertEquals("Third", buffer2.contents().getString(0));
    }

    // ==================== State Consistency Tests ====================

    @Test
    @DisplayName("State: Buffer state consistent after operations")
    void testStateConsistency() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // Initial state
        BufferBase buffer = bm.pin(blk);
        assertNotNull(buffer.block());
        assertTrue(buffer.isPinned());
        assertNotNull(buffer.contents());

        // After modification
        buffer.setModified(1, 100);
        assertTrue(buffer.isPinned());

        // After unpin
        bm.unpin(buffer);
        assertFalse(buffer.isPinned());
        assertNotNull(buffer.block()); // Still associated
        assertNotNull(buffer.contents());

        // After repin
        BufferBase buffer2 = bm.pin(blk);
        assertTrue(buffer2.isPinned());
        assertEquals(buffer.block(), buffer2.block());
    }

    @Test
    @DisplayName("State: Contents reference stays valid")
    void testContentsReferenceValidity() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buffer = bm.pin(blk);
        PageBase page1 = buffer.contents();
        page1.setString(0, "Test");

        PageBase page2 = buffer.contents();
        assertSame(page1, page2);
        assertEquals("Test", page2.getString(0));

        bm.unpin(buffer);

        BufferBase buffer2 = bm.pin(blk);
        PageBase page3 = buffer2.contents();
        assertEquals("Test", page3.getString(0));
    }

    // ==================== Special Scenario Tests ====================

    @Test
    @DisplayName("Scenario: Read-only access doesn't mark dirty")
    void testReadOnlyAccess() {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 1000);

        // Write initial data
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buffer1 = bm.pin(blk);
        buffer1.contents().setString(0, "Initial");
        buffer1.setModified(1, -1);
        bm.unpin(buffer1);
        bm.flushAll(1);

        // Read-only access
        BufferBase buffer2 = bm.pin(blk);
        String data = buffer2.contents().getString(0);
        assertEquals("Initial", data);
        // Don't call setModified
        bm.unpin(buffer2);

        // Force eviction
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buffer3 = bm.pin(blk2);
        bm.unpin(buffer3);

        BlockId blk3 = new BlockId("testfile", 2);
        BufferBase buffer4 = bm.pin(blk3);
        bm.unpin(buffer4);

        // Verify original still correct
        BufferBase buffer5 = bm.pin(blk);
        assertEquals("Initial", buffer5.contents().getString(0));
    }

    @Test
    @DisplayName("Scenario: Transaction rollback simulation")
    void testTransactionRollbackSimulation() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // Write initial committed data
        BufferBase buffer1 = bm.pin(blk);
        buffer1.contents().setString(0, "Committed");
        buffer1.setModified(1, -1);
        bm.unpin(buffer1);
        bm.flushAll(1);

        // Simulate transaction 2 making changes but not flushing
        BufferBase buffer2 = bm.pin(blk);
        buffer2.contents().setString(0, "Uncommitted");
        buffer2.setModified(2, -1);
        bm.unpin(buffer2);
        // Don't flush transaction 2

        // Force eviction (simulating rollback)
        BufferMgr bm2 = new BufferMgr(fm, lm, 1, 1000);
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buffer3 = bm2.pin(blk2);
        bm2.unpin(buffer3);

        BufferBase buffer4 = bm2.pin(blk);
        // Should still see committed data
        assertEquals("Committed", buffer4.contents().getString(0));
    }

    @Test
    @DisplayName("Scenario: Concurrent transaction isolation")
    void testConcurrentTransactionIsolation() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);

        // Transaction 1 modifies blocks 0-2
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            buffer.contents().setString(0, "TX1_" + i);
            buffer.setModified(1, -1);
            bm.unpin(buffer);
        }

        // Transaction 2 modifies blocks 3-4
        for (int i = 3; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            buffer.contents().setString(0, "TX2_" + i);
            buffer.setModified(2, -1);
            bm.unpin(buffer);
        }

        // Flush only transaction 1
        bm.flushAll(1);

        // Verify transaction 1 data persisted
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            assertEquals("TX1_" + i, buffer.contents().getString(0));
            bm.unpin(buffer);
        }

        // Transaction 2 data still in buffer
        for (int i = 3; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            assertEquals("TX2_" + i, buffer.contents().getString(0));
            bm.unpin(buffer);
        }
    }

    @Test
    @DisplayName("Scenario: Large sequential scan")
    void testLargeSequentialScan() {
        BufferMgr bm = new BufferMgr(fm, lm, 10, 1000);
        int numBlocks = 100;

        // Write all blocks
        for (int i = 0; i < numBlocks; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            buffer.contents().setInt(0, i);
            buffer.setModified(1, -1);
            bm.unpin(buffer);
        }

        bm.flushAll(1);

        // Sequential scan
        for (int i = 0; i < numBlocks; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buffer = bm.pin(blk);
            assertEquals(i, buffer.contents().getInt(0));
            bm.unpin(buffer);
        }
    }
}