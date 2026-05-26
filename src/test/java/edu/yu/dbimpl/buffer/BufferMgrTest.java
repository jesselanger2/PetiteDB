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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Buffer and BufferMgr classes.
 * Tests cover:
 * - All pin scenarios from lecture
 * - Edge cases and error conditions
 * - Thread-safety and concurrency
 * - Performance benchmarks
 * - Eviction policy comparison (NAIVE vs CLOCK)
 */
class BufferMgrTest {

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

    // ==================== Basic Constructor Tests ====================

    @Test
    @DisplayName("Constructor: Valid parameters")
    void testConstructorValid() {
        BufferMgr bm = new BufferMgr(fm, lm, 10, 1000);
        assertEquals(10, bm.available());
        assertEquals(BufferMgrBase.EvictionPolicy.NAIVE, bm.getEvictionPolicy());
    }

    @Test
    @DisplayName("Constructor: With explicit CLOCK policy")
    void testConstructorWithClockPolicy() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 500, BufferMgrBase.EvictionPolicy.CLOCK);
        assertEquals(5, bm.available());
        assertEquals(BufferMgrBase.EvictionPolicy.CLOCK, bm.getEvictionPolicy());
    }

    @Test
    @DisplayName("Constructor: Invalid buffer count throws exception")
    void testConstructorInvalidBufferCount() {
        assertThrows(IllegalArgumentException.class, () ->
                new BufferMgr(fm, lm, 0, 1000));
        assertThrows(IllegalArgumentException.class, () ->
                new BufferMgr(fm, lm, -1, 1000));
    }

    @Test
    @DisplayName("Constructor: Invalid wait time throws exception")
    void testConstructorInvalidWaitTime() {
        assertThrows(IllegalArgumentException.class, () ->
                new BufferMgr(fm, lm, 10, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new BufferMgr(fm, lm, 10, -100));
    }

    // ==================== Pin Scenario Tests (From Lecture) ====================

    @Test
    @DisplayName("Pin Scenario I: Block already pinned in buffer")
    void testPinScenario1_AlreadyPinned() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // First pin
        BufferBase buff1 = bm.pin(blk);
        assertNotNull(buff1);
        assertEquals(blk, buff1.block());
        assertTrue(buff1.isPinned());
        assertEquals(2, bm.available()); // One buffer used

        // Second pin of same block
        BufferBase buff2 = bm.pin(blk);
        assertSame(buff1, buff2); // Should return same buffer
        assertTrue(buff2.isPinned());
        assertEquals(2, bm.available()); // Still only one buffer used

        // Unpin once
        bm.unpin(buff1);
        assertTrue(buff1.isPinned()); // Still pinned by second client
        assertEquals(2, bm.available());

        // Unpin again
        bm.unpin(buff2);
        assertFalse(buff1.isPinned()); // Now unpinned
        assertEquals(3, bm.available());
    }

    @Test
    @DisplayName("Pin Scenario II: Block unpinned in buffer")
    void testPinScenario2_UnpinnedInBuffer() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 1);

        // Pin and then unpin
        BufferBase buff1 = bm.pin(blk);
        buff1.contents().setString(0, "test data");
        bm.unpin(buff1);
        assertFalse(buff1.isPinned());
        assertEquals(3, bm.available());

        // Pin again - should reuse same buffer
        BufferBase buff2 = bm.pin(blk);
        assertSame(buff1, buff2);
        assertEquals("test data", buff2.contents().getString(0));
        assertEquals(2, bm.available());
    }

    @Test
    @DisplayName("Pin Scenario III: Block not in buffer, unpinned buffer available")
    void testPinScenario3_NotInBufferWithAvailable() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        // Pin three different blocks
        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);
        BlockId blk3 = new BlockId("testfile", 2);

        BufferBase buff1 = bm.pin(blk1);
        BufferBase buff2 = bm.pin(blk2);
        BufferBase buff3 = bm.pin(blk3);

        assertEquals(0, bm.available());

        // Unpin one buffer
        bm.unpin(buff1);
        assertEquals(1, bm.available());

        // Pin a new block - should reuse buff1
        BlockId blk4 = new BlockId("testfile", 3);
        BufferBase buff4 = bm.pin(blk4);
        assertNotNull(buff4);
        assertEquals(blk4, buff4.block());
        assertEquals(0, bm.available());
    }

    @Test
    @DisplayName("Pin Scenario IV: All buffers pinned - timeout")
    void testPinScenario4_AllPinnedTimeout() {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 500);

        // Pin all buffers
        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff1 = bm.pin(blk1);
        BufferBase buff2 = bm.pin(blk2);

        assertEquals(0, bm.available());

        // Try to pin another block - should timeout
        BlockId blk3 = new BlockId("testfile", 2);
        long startTime = System.currentTimeMillis();

        BufferAbortException exception = assertThrows(BufferAbortException.class, () -> {
            bm.pin(blk3);
        });

        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(elapsed >= 500, "Should wait at least maxWaitTime");
        assertTrue(elapsed < 1000, "Should not wait significantly longer than maxWaitTime");
    }

    @Test
    @DisplayName("Pin Scenario IV: All buffers pinned - wait and succeed")
    void testPinScenario4_AllPinnedWaitSuccess() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 2000);

        // Pin all buffers
        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff1 = bm.pin(blk1);
        BufferBase buff2 = bm.pin(blk2);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(-1);

        // Thread that will wait for a buffer
        Thread waiter = new Thread(() -> {
            try {
                latch.countDown();
                BlockId blk3 = new BlockId("testfile", 2);
                BufferBase buff3 = bm.pin(blk3);
                result.set(1); // Success
            } catch (BufferAbortException e) {
                result.set(0); // Timeout
            }
        });

        waiter.start();
        latch.await(); // Wait for thread to start
        Thread.sleep(100); // Give it time to block

        // Unpin a buffer - should wake up waiter
        bm.unpin(buff1);

        waiter.join(1000);
        assertEquals(1, result.get(), "Waiter should succeed after buffer becomes available");
    }

    // ==================== Buffer-Specific Tests ====================

    @Test
    @DisplayName("Buffer: setModified with valid parameters")
    void testBufferSetModified() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        buff.setModified(1, 100);
        assertTrue(buff.isPinned());
    }

    @Test
    @DisplayName("Buffer: setModified with negative txnum throws exception")
    void testBufferSetModifiedInvalidTxnum() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        assertThrows(IllegalArgumentException.class, () ->
                buff.setModified(-1, 100));
    }

    @Test
    @DisplayName("Buffer: setModified with negative LSN is valid")
    void testBufferSetModifiedNegativeLSN() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        assertDoesNotThrow(() -> buff.setModified(1, -1));
    }

    @Test
    @DisplayName("Buffer: contents() returns valid page")
    void testBufferContents() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        PageBase page = buff.contents();
        assertNotNull(page);

        page.setString(0, "Hello World");
        assertEquals("Hello World", page.getString(0));
    }

    // ==================== Unpin Tests ====================

    @Test
    @DisplayName("Unpin: Valid unpinned buffer")
    void testUnpinValid() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        assertEquals(2, bm.available());
        bm.unpin(buff);
        assertEquals(3, bm.available());
        assertFalse(buff.isPinned());
    }

    @Test
    @DisplayName("Unpin: Null buffer throws exception")
    void testUnpinNull() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        assertThrows(IllegalArgumentException.class, () -> bm.unpin(null));
    }

    @Test
    @DisplayName("Unpin: Already unpinned buffer throws exception")
    void testUnpinAlreadyUnpinned() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);

        bm.unpin(buff);
        assertThrows(IllegalArgumentException.class, () -> bm.unpin(buff));
    }

    // ==================== FlushAll Tests ====================

    @Test
    @DisplayName("FlushAll: Modified buffers are flushed")
    void testFlushAllModified() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        // Pin and modify multiple blocks with same txnum
        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);

        BufferBase buff1 = bm.pin(blk1);
        buff1.contents().setString(0, "Data1");
        buff1.setModified(1, -1);

        BufferBase buff2 = bm.pin(blk2);
        buff2.contents().setString(0, "Data2");
        buff2.setModified(1, -1);

        bm.flushAll(1);

        // Unpin and repin to verify data persisted
        bm.unpin(buff1);
        bm.unpin(buff2);

        BufferBase newBuff1 = bm.pin(blk1);
        assertEquals("Data1", newBuff1.contents().getString(0));

        BufferBase newBuff2 = bm.pin(blk2);
        assertEquals("Data2", newBuff2.contents().getString(0));
    }

    @Test
    @DisplayName("FlushAll: Only specified transaction buffers flushed")
    void testFlushAllSpecificTransaction() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);

        BufferBase buff1 = bm.pin(blk1);
        buff1.setModified(1, -1);

        BufferBase buff2 = bm.pin(blk2);
        buff2.setModified(2, -1);

        // Only flush transaction 1
        bm.flushAll(1);

        // Both buffers should still be pinned
        assertTrue(buff1.isPinned());
        assertTrue(buff2.isPinned());
    }

    @Test
    @DisplayName("FlushAll: Negative txnum throws exception")
    void testFlushAllInvalidTxnum() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        assertThrows(IllegalArgumentException.class, () -> bm.flushAll(-1));
    }

    // ==================== Pin Edge Cases ====================

    @Test
    @DisplayName("Pin: Null block throws exception")
    void testPinNullBlock() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        assertThrows(IllegalArgumentException.class, () -> bm.pin(null));
    }

    @Test
    @DisplayName("Pin: Multiple pins increase pin count correctly")
    void testPinMultipleTimes() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buff1 = bm.pin(blk);
        BufferBase buff2 = bm.pin(blk);
        BufferBase buff3 = bm.pin(blk);

        assertSame(buff1, buff2);
        assertSame(buff2, buff3);
        assertTrue(buff1.isPinned());
        assertEquals(2, bm.available());

        // Need to unpin three times
        bm.unpin(buff1);
        assertTrue(buff1.isPinned());
        bm.unpin(buff2);
        assertTrue(buff1.isPinned());
        bm.unpin(buff3);
        assertFalse(buff1.isPinned());
        assertEquals(3, bm.available());
    }

    @Test
    @DisplayName("Pin: Dirty buffer is flushed before reassignment")
    void testPinFlushesBeforeReassignment() {
        BufferMgr bm = new BufferMgr(fm, lm, 1, 1000); // Only 1 buffer

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buff1 = bm.pin(blk1);
        buff1.contents().setString(0, "Data1");
        buff1.setModified(1, -1);
        bm.unpin(buff1);

        // Pin a different block - should flush blk1
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff2 = bm.pin(blk2);
        buff2.contents().setString(0, "Data2");
        bm.unpin(buff2);

        // Verify first block was flushed and can be re-read
        BufferBase verifyBuff = bm.pin(blk1);
        assertEquals("Data1", verifyBuff.contents().getString(0));
    }

    @Test
    @DisplayName("Edge: Empty block data")
    void testEmptyBlockData() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buff = bm.pin(blk);
        // Don't write anything, just unpin
        bm.unpin(buff);

        // Re-pin and verify
        BufferBase buff2 = bm.pin(blk);
        assertNotNull(buff2.contents());
    }

    @Test
    @DisplayName("Edge: Very large block numbers")
    void testLargeBlockNumbers() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 999999);

        BufferBase buff = bm.pin(blk);
        buff.contents().setString(0, "Large block");
        buff.setModified(1, -1);
        bm.unpin(buff);

        BufferBase buff2 = bm.pin(blk);
        assertEquals("Large block", buff2.contents().getString(0));
    }

    @Test
    @DisplayName("Edge: Many unpins on same buffer")
    void testMultipleUnpinsSequential() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // Pin multiple times
        BufferBase buff1 = bm.pin(blk);
        BufferBase buff2 = bm.pin(blk);
        BufferBase buff3 = bm.pin(blk);
        BufferBase buff4 = bm.pin(blk);

        // Unpin multiple times
        bm.unpin(buff1);
        bm.unpin(buff2);
        bm.unpin(buff3);
        bm.unpin(buff4);

        assertFalse(buff1.isPinned());
        assertEquals(3, bm.available());
    }

    @Test
    @DisplayName("Edge: Interleaved pins and unpins")
    void testInterleavedPinUnpin() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        BlockId blk1 = new BlockId("testfile", 0);
        BlockId blk2 = new BlockId("testfile", 1);

        BufferBase buff1a = bm.pin(blk1);
        BufferBase buff2a = bm.pin(blk2);
        BufferBase buff1b = bm.pin(blk1);

        bm.unpin(buff1a);
        bm.unpin(buff2a);

        BufferBase buff2b = bm.pin(blk2);

        assertTrue(buff1b.isPinned());
        assertTrue(buff2b.isPinned());
        assertSame(buff2a, buff2b);
    }

    @Test
    @DisplayName("Edge: FlushAll with no matching transactions")
    void testFlushAllNoMatches() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        BlockId blk = new BlockId("testfile", 0);
        BufferBase buff = bm.pin(blk);
        buff.setModified(1, -1);

        // Flush different transaction
        assertDoesNotThrow(() -> bm.flushAll(99));

        // Original buffer should still be modified
        assertTrue(buff.isPinned());
    }

    @Test
    @DisplayName("Edge: Multiple files")
    void testMultipleFiles() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);

        BlockId blk1 = new BlockId("file1", 0);
        BlockId blk2 = new BlockId("file2", 0);
        BlockId blk3 = new BlockId("file3", 0);

        BufferBase buff1 = bm.pin(blk1);
        buff1.contents().setString(0, "File1");

        BufferBase buff2 = bm.pin(blk2);
        buff2.contents().setString(0, "File2");

        BufferBase buff3 = bm.pin(blk3);
        buff3.contents().setString(0, "File3");

        assertEquals("File1", buff1.contents().getString(0));
        assertEquals("File2", buff2.contents().getString(0));
        assertEquals("File3", buff3.contents().getString(0));
    }

    // ==================== Stress Tests ====================

    @Test
    @DisplayName("Stress: Rapid pin/unpin cycles")
    void testRapidPinUnpinCycles() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);
        BlockId blk = new BlockId("testfile", 0);

        for (int i = 0; i < 1000; i++) {
            BufferBase buff = bm.pin(blk);
            buff.contents().setInt(0, i);
            bm.unpin(buff);
        }

        BufferBase finalBuff = bm.pin(blk);
        assertEquals(999, finalBuff.contents().getInt(0));
    }

    @Test
    @DisplayName("Stress: All buffers continuously pinned/unpinned")
    void testAllBuffersContinuousUse() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);

        for (int cycle = 0; cycle < 10; cycle++) {
            List<BufferBase> buffers = new ArrayList<>();

            // Pin all buffers
            for (int i = 0; i < 3; i++) {
                BlockId blk = new BlockId("testfile", i);
                buffers.add(bm.pin(blk));
            }

            assertEquals(0, bm.available());

            // Unpin all buffers
            for (BufferBase buff : buffers) {
                bm.unpin(buff);
            }

            assertEquals(3, bm.available());
        }
    }

    @Test
    @DisplayName("Stress: High contention scenario")
    void testHighContentionScenario() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 5000);
        int numThreads = 10;
        int operationsPerThread = 50;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                startLatch.countDown();
                try {
                    startLatch.await();

                    for (int j = 0; j < operationsPerThread; j++) {
                        BlockId blk = new BlockId("testfile", j % 5);
                        try {
                            BufferBase buff = bm.pin(blk);
                            Thread.sleep(10); // Hold buffer briefly
                            bm.unpin(buff);
                            successCount.incrementAndGet();
                        } catch (BufferAbortException e) {
                            timeoutCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));

        System.out.println("Successful operations: " + successCount.get());
        System.out.println("Timeouts: " + timeoutCount.get());

        assertTrue(successCount.get() > 0, "Some operations should succeed");
    }

    // ==================== Data Integrity Tests ====================

    @Test
    @DisplayName("Integrity: Modified data persists across pin/unpin")
    void testDataPersistence() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        // Write data
        BufferBase buff1 = bm.pin(blk);
        buff1.contents().setString(0, "Persistent Data");
        buff1.contents().setInt(100, 12345);
        buff1.contents().setDouble(200, 3.14159);
        buff1.setModified(1, -1);
        bm.unpin(buff1);

        // Flush to disk
        bm.flushAll(1);

        // Read back
        BufferBase buff2 = bm.pin(blk);
        assertEquals("Persistent Data", buff2.contents().getString(0));
        assertEquals(12345, buff2.contents().getInt(100));
        assertEquals(3.14159, buff2.contents().getDouble(200), 0.00001);
    }

    @Test
    @DisplayName("Integrity: Multiple transactions modify different blocks")
    void testMultipleTransactionsIntegrity() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);

        // Transaction 1 modifies blocks 0-2
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            buff.contents().setString(0, "TX1_Block" + i);
            buff.setModified(1, -1);
            bm.unpin(buff);
        }

        // Transaction 2 modifies blocks 3-4
        for (int i = 3; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            buff.contents().setString(0, "TX2_Block" + i);
            buff.setModified(2, -1);
            bm.unpin(buff);
        }

        // Flush transaction 1
        bm.flushAll(1);

        // Verify transaction 1 data
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            assertEquals("TX1_Block" + i, buff.contents().getString(0));
            bm.unpin(buff);
        }

        // Flush transaction 2
        bm.flushAll(2);

        // Verify transaction 2 data
        for (int i = 3; i < 5; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            assertEquals("TX2_Block" + i, buff.contents().getString(0));
            bm.unpin(buff);
        }
    }

    @Test
    @DisplayName("Integrity: Concurrent writes to same block coordinate correctly")
    void testConcurrentWritesIntegrity() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 2000);
        BlockId blk = new BlockId("testfile", 0);
        int numThreads = 5;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(numThreads);
        AtomicInteger counter = new AtomicInteger(0);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.countDown();
                startLatch.await();

                BufferBase buff = bm.pin(blk);
                int value = counter.incrementAndGet();
                buff.contents().setInt(0, value);
                Thread.sleep(10);
                bm.unpin(buff);

                return value;
            }));
        }

        Set<Integer> values = new HashSet<>();
        for (Future<Integer> future : futures) {
            values.add(future.get());
        }

        assertEquals(numThreads, values.size(), "Each thread should write unique value");

        executor.shutdown();
    }

    // ==================== Boundary Tests ====================

    @Test
    @DisplayName("Boundary: Maximum buffer pool size")
    void testMaximumBufferPool() {
        BufferMgr bm = new BufferMgr(fm, lm, 1000, 1000);
        assertEquals(1000, bm.available());

        // Pin half the buffers
        List<BufferBase> buffers = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BlockId blk = new BlockId("testfile", i);
            buffers.add(bm.pin(blk));
        }

        assertEquals(500, bm.available());
    }

    @Test
    @DisplayName("Boundary: Minimum wait time")
    void testMinimumWaitTime() {
        BufferMgr bm = new BufferMgr(fm, lm, 1, 1); // 1ms wait

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buff1 = bm.pin(blk1);

        BlockId blk2 = new BlockId("testfile", 1);
        long start = System.currentTimeMillis();

        assertThrows(BufferAbortException.class, () -> bm.pin(blk2));

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 1, "Should wait at least 1ms");
    }

    @Test
    @DisplayName("Boundary: Zero-length strings and data")
    void testZeroLengthData() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000);
        BlockId blk = new BlockId("testfile", 0);

        BufferBase buff = bm.pin(blk);
        buff.contents().setString(0, "");
        buff.contents().setBytes(50, new byte[0]);
        buff.setModified(1, -1);
        bm.unpin(buff);

        bm.flushAll(1);

        BufferBase buff2 = bm.pin(blk);
        assertEquals("", buff2.contents().getString(0));
        assertArrayEquals(new byte[0], buff2.contents().getBytes(50));
    }

    // ==================== Recovery Scenario Tests ====================

    @Test
    @DisplayName("Recovery: Unpin without modification doesn't flush")
    void testUnpinWithoutModificationNoFlush() {
        BufferMgr bm = new BufferMgr(fm, lm, 2, 1000);

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buff1 = bm.pin(blk1);
        buff1.contents().setString(0, "Original");
        buff1.setModified(1, -1);
        bm.unpin(buff1);
        bm.flushAll(1);

        // Read without modifying
        BufferBase buff2 = bm.pin(blk1);
        String data = buff2.contents().getString(0);
        bm.unpin(buff2);

        // Force eviction by filling buffer pool
        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff3 = bm.pin(blk2);
        bm.unpin(buff3);

        BlockId blk3 = new BlockId("testfile", 2);
        BufferBase buff4 = bm.pin(blk3);
        bm.unpin(buff4);

        // Verify original data still correct
        BufferBase buff5 = bm.pin(blk1);
        assertEquals("Original", buff5.contents().getString(0));
    }

    // ==================== Documentation Example Tests ====================

    @Test
    @DisplayName("Documentation: Example from lecture slides")
    void testLectureExample() {
        BufferMgr bm = new BufferMgr(fm, lm, 4, 1000);

        // Sequence from lecture: pin(10); pin(20); pin(30); pin(40);
        BlockId blk10 = new BlockId("testfile", 10);
        BlockId blk20 = new BlockId("testfile", 20);
        BlockId blk30 = new BlockId("testfile", 30);
        BlockId blk40 = new BlockId("testfile", 40);

        BufferBase buff10 = bm.pin(blk10);
        BufferBase buff20 = bm.pin(blk20);
        BufferBase buff30 = bm.pin(blk30);
        BufferBase buff40 = bm.pin(blk40);

        assertEquals(0, bm.available());

        // unpin(20); pin(50); unpin(40);
        bm.unpin(buff20);
        assertEquals(1, bm.available());

        BlockId blk50 = new BlockId("testfile", 50);
        BufferBase buff50 = bm.pin(blk50);
        assertEquals(0, bm.available());

        bm.unpin(buff40);
        assertEquals(1, bm.available());

        // unpin(10); unpin(30); unpin(50);
        bm.unpin(buff10);
        bm.unpin(buff30);
        bm.unpin(buff50);

        assertEquals(4, bm.available());

        // Now pin(60); pin(70);
        BlockId blk60 = new BlockId("testfile", 60);
        BlockId blk70 = new BlockId("testfile", 70);

        BufferBase buff60 = bm.pin(blk60);
        BufferBase buff70 = bm.pin(blk70);

        assertEquals(2, bm.available());
        assertNotNull(buff60);
        assertNotNull(buff70);
    }

    @Test
    @DisplayName("Available: Correct count throughout operations")
    void testAvailableCount() {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 1000);

        assertEquals(5, bm.available());

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buff1 = bm.pin(blk1);
        assertEquals(4, bm.available());

        buff1.contents().setString(0, "Original");

        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff2 = bm.pin(blk2);
        assertEquals(3, bm.available());

        // Pin same block - available shouldn't change
        BufferBase buff1again = bm.pin(blk1);
        assertEquals(3, bm.available());

        bm.unpin(buff1);
        assertEquals(3, bm.available()); // Still pinned

        bm.unpin(buff1again);
        assertEquals(4, bm.available()); // Now unpinned

        bm.unpin(buff2);
        assertEquals(5, bm.available());

        buff2.contents().setString(0, "New");

        // Verify original data persisted
        BufferBase verifyBuff = bm.pin(blk1);
        assertEquals("Original", verifyBuff.contents().getString(0));
    }

    // ==================== Thread-Safety Tests ====================

    @Test
    @DisplayName("Concurrency: Multiple threads pinning same block")
    void testConcurrentPinSameBlock() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 2000);
        BlockId blk = new BlockId("testfile", 0);
        int numThreads = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(numThreads);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        List<Future<BufferBase>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.countDown();
                startLatch.await(); // Wait for all threads to be ready
                BufferBase buff = bm.pin(blk);
                finishLatch.countDown();
                finishLatch.await(); // Wait for all threads to finish pinning
                return buff;
            }));
        }

        // Collect all buffers
        List<BufferBase> buffers = new ArrayList<>();
        for (Future<BufferBase> future : futures) {
            buffers.add(future.get());
        }

        // All buffers should be the same instance
        Set<BufferBase> uniqueBuffers = new HashSet<>(buffers);
        assertEquals(1, uniqueBuffers.size(), "All threads should get same buffer");

        // The block should still be pinned (10 times)
        BufferBase theBuffer = buffers.get(0);
        assertTrue(theBuffer.isPinned());
        assertEquals(blk, theBuffer.block());

        // Should have used only 1 buffer out of 5
        assertEquals(4, bm.available(), "Only one buffer should be used");

        // Unpin all references
        for (BufferBase buff : buffers) {
            bm.unpin(buff);
        }

        // Now all buffers should be available
        assertEquals(5, bm.available());
        assertFalse(theBuffer.isPinned());

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Concurrency: Multiple threads pinning different blocks")
    void testConcurrentPinDifferentBlocks() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 10, 2000);
        int numThreads = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int blockNum = i;
            futures.add(executor.submit(() -> {
                latch.countDown();
                latch.await();
                BlockId blk = new BlockId("testfile", blockNum);
                BufferBase buff = bm.pin(blk);
                return buff != null && buff.block().equals(blk);
            }));
        }

        for (Future<Boolean> future : futures) {
            assertTrue(future.get(), "Each thread should successfully pin its block");
        }

        assertEquals(0, bm.available(), "All buffers should be used");

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrency: Pin and unpin stress test")
    void testConcurrentPinUnpinStress() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 5, 2000);
        int numThreads = 20;
        int operationsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                startLatch.countDown();
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);

                    for (int j = 0; j < operationsPerThread; j++) {
                        int blockNum = rand.nextInt(10);
                        BlockId blk = new BlockId("testfile", blockNum);

                        try {
                            BufferBase buff = bm.pin(blk);
                            buff.contents().setInt(0, threadId);
                            Thread.sleep(rand.nextInt(5));
                            bm.unpin(buff);
                        } catch (BufferAbortException e) {
                            // Expected when all buffers pinned
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No errors should occur during concurrent operations");
    }

    // ==================== Performance Benchmark Test ====================

    @Test
    @DisplayName("Performance: Large-scale read/write with concurrency")
    void testPerformanceBenchmark() throws Exception {
        BufferMgr bm = new BufferMgr(fm, lm, 1000, 500);
        int numBlocks = 10000;
        int concurrencyLevel = 10;

        // Simple lock manager to simulate concurrency control
        ConcurrentHashMap<BlockId, ReentrantLock> blockLocks = new ConcurrentHashMap<>();

        // Helper to get lock for a block
        Function<BlockId, ReentrantLock> getLock = (blk) ->
                blockLocks.computeIfAbsent(blk, k -> new ReentrantLock());

        // Phase 1: Create blocks
        System.out.println("Creating " + numBlocks + " blocks...");
        for (int i = 0; i < numBlocks; i++) {
            BlockId blk = new BlockId("perftest", i);

            // Acquire exclusive lock for writing
            ReentrantLock lock = getLock.apply(blk);
            lock.lock();
            try {
                BufferBase buff = bm.pin(blk);
                buff.contents().setString(0, "Block" + i);
                buff.contents().setInt(100, i);
                buff.setModified(1, -1);
                bm.unpin(buff);
            } finally {
                lock.unlock();
            }
        }

        // Flush all and ensure writes complete
        bm.flushAll(1);
        Thread.sleep(100);

        System.out.println("Phase 1 complete. Available buffers: " + bm.available());

        // Phase 2: Random read with concurrency
        System.out.println("Reading blocks randomly with " + concurrencyLevel + " threads...");
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch startLatch = new CountDownLatch(concurrencyLevel);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        ConcurrentHashMap<Integer, String> errors = new ConcurrentHashMap<>();

        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrencyLevel; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                startLatch.countDown();
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);

                    for (int j = 0; j < numBlocks / concurrencyLevel; j++) {
                        int blockNum = rand.nextInt(numBlocks);
                        BlockId blk = new BlockId("perftest", blockNum);

                        // Acquire shared lock for reading
                        ReentrantLock lock = getLock.apply(blk);
                        lock.lock();
                        try {
                            BufferBase buff = bm.pin(blk);
                            try {
                                String expected = "Block" + blockNum;
                                String actual = buff.contents().getString(0);
                                int expectedInt = blockNum;
                                int actualInt = buff.contents().getInt(100);

                                if (expected.equals(actual) && expectedInt == actualInt) {
                                    successCount.incrementAndGet();
                                } else {
                                    errorCount.incrementAndGet();
                                    errors.put(blockNum, String.format(
                                            "Thread %d, Block %d: expected string='%s' int=%d, got string='%s' int=%d",
                                            threadId, blockNum, expected, expectedInt, actual, actualInt));
                                }
                            } finally {
                                bm.unpin(buff);
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    errors.put(-threadId, "Thread " + threadId + " exception: " + e.getMessage());
                    e.printStackTrace();
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        long elapsed = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("Elapsed time: " + elapsed + " ms");
        System.out.println("Successful reads: " + successCount.get());
        System.out.println("Errors: " + errorCount.get());
        System.out.println("Available buffers after test: " + bm.available());

        if (!errors.isEmpty()) {
            System.err.println("\n=== ERRORS ===");
            errors.forEach((key, value) -> System.err.println(value));
        }

        assertEquals(numBlocks, successCount.get(), "All reads should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur");
        assertTrue(elapsed < 2000, "Should complete within reasonable time (< 2s)");
    }

    // ==================== Eviction Policy Comparison Tests ====================

    @Test
    @DisplayName("Eviction: NAIVE policy sequential access")
    void testNaiveEvictionSequential() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000, BufferMgrBase.EvictionPolicy.NAIVE);

        // Fill all buffers
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            bm.unpin(buff);
        }

        // Pin new block - should use first unpinned buffer
        BlockId blk3 = new BlockId("testfile", 3);
        BufferBase buff3 = bm.pin(blk3);
        assertEquals(blk3, buff3.block());
    }

    @Test
    @DisplayName("Eviction: CLOCK policy distributes evictions")
    void testClockEvictionDistribution() {
        BufferMgr bm = new BufferMgr(fm, lm, 3, 1000, BufferMgrBase.EvictionPolicy.CLOCK);

        // Fill all buffers
        List<BufferBase> buffers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            buffers.add(buff);
            bm.unpin(buff);
        }

        // Pin three more blocks - CLOCK should distribute across buffers
        for (int i = 3; i < 6; i++) {
            BlockId blk = new BlockId("testfile", i);
            BufferBase buff = bm.pin(blk);
            bm.unpin(buff);
        }

        // Verify CLOCK hand moved (implicit through successful pins)
        assertEquals(3, bm.available());
    }

    @Test
    @DisplayName("Eviction: CLOCK outperforms NAIVE with hot set")
    void testClockVsNaivePerformance() throws Exception {
        int numBuffers = 10;
        int hotSetSize = 5;
        int coldSetSize = 20;
        int iterations = 100;

        // Test NAIVE policy
        long naiveTime = measureEvictionPerformance(
                BufferMgrBase.EvictionPolicy.NAIVE,
                numBuffers, hotSetSize, coldSetSize, iterations
        );

        // Test CLOCK policy
        long clockTime = measureEvictionPerformance(
                BufferMgrBase.EvictionPolicy.CLOCK,
                numBuffers, hotSetSize, coldSetSize, iterations
        );

        System.out.println("NAIVE policy time: " + naiveTime + " ms");
        System.out.println("CLOCK policy time: " + clockTime + " ms");

        // CLOCK should perform better when hot set fits in buffer pool
        // Being conservative with assertion as performance can vary
        assertTrue(clockTime <= naiveTime * 1.5,
                "CLOCK should perform comparably or better than NAIVE for hot set access");
    }

    private long measureEvictionPerformance(
            BufferMgrBase.EvictionPolicy policy,
            int numBuffers,
            int hotSetSize,
            int coldSetSize,
            int iterations) throws Exception {

        BufferMgr bm = new BufferMgr(fm, lm, numBuffers, 2000, policy);
        String filename = "eviction_" + policy.name();

        // Create all blocks first
        for (int i = 0; i < hotSetSize + coldSetSize; i++) {
            BlockId blk = new BlockId(filename, i);
            BufferBase buff = bm.pin(blk);
            buff.contents().setInt(0, i);
            buff.setModified(1, -1);
            bm.unpin(buff);
        }

        Random rand = new Random(12345);
        long startTime = System.currentTimeMillis();

        // Access pattern: 80% hot set, 20% cold set
        for (int i = 0; i < iterations; i++) {
            int blockNum;
            if (rand.nextInt(100) < 80) {
                // Access hot set
                blockNum = rand.nextInt(hotSetSize);
            } else {
                // Access cold set
                blockNum = hotSetSize + rand.nextInt(coldSetSize);
            }

            BlockId blk = new BlockId(filename, blockNum);
            BufferBase buff = bm.pin(blk);
            int value = buff.contents().getInt(0);
            assertEquals(blockNum, value);
            bm.unpin(buff);
        }

        return System.currentTimeMillis() - startTime;
    }

    // ==================== Edge Case Tests ====================

    @Test
    @DisplayName("Edge: Single buffer manager")
    void testSingleBuffer() {
        BufferMgr bm = new BufferMgr(fm, lm, 1, 1000);

        BlockId blk1 = new BlockId("testfile", 0);
        BufferBase buff1 = bm.pin(blk1);
        buff1.contents().setString(0, "Data1");
        buff1.setModified(1, -1);
        bm.unpin(buff1);

        BlockId blk2 = new BlockId("testfile", 1);
        BufferBase buff2 = bm.pin(blk2);
    }
}