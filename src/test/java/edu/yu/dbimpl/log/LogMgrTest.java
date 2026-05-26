package edu.yu.dbimpl.log;

import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgr;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Comprehensive LogMgr Tests")
class LogMgrTest {

    private FileMgr fm;
    private LogMgr logMgr;
    private final int blockSize = 400;
    private final String logFileName = "test_log_file";
    private final String dbDirectoryName = "logtest_db";

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
        fm = new FileMgr(dbDir, blockSize);
        logMgr = new LogMgr(fm, logFileName);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up after each test to prevent interference
        File dbDir = new File(dbDirectoryName);
        if (dbDir.exists()) {
            deleteDirectory(dbDir.toPath());
        }
    }

    @Test
    @DisplayName("Test Append Single Record")
    void testAppendSingleRecord() {
        byte[] rec = "record_1".getBytes(StandardCharsets.UTF_8);
        int lsn = logMgr.append(rec);
        assertEquals(0, lsn, "First LSN should be 0");
    }

    @Test
    @DisplayName("Test Append Multiple Records and Check LSNs")
    void testAppendMultipleRecords() {
        assertEquals(0, logMgr.append("rec_0".getBytes()));
        assertEquals(1, logMgr.append("rec_1".getBytes()));
        assertEquals(2, logMgr.append("rec_2".getBytes()));
    }

    @Test
    @DisplayName("Test Flush and Basic Iteration")
    void testFlushAndIteration() {
        byte[] rec0 = "record_0".getBytes();
        byte[] rec1 = "record_1".getBytes();

        logMgr.append(rec0);
        int lsn1 = logMgr.append(rec1);
        logMgr.flush(lsn1);

        Iterator<byte[]> it = logMgr.iterator();
        assertTrue(it.hasNext(), "Iterator should have records after flush");
        assertArrayEquals(rec1, it.next(), "Iterator should return records in reverse LSN order");
        assertTrue(it.hasNext());
        assertArrayEquals(rec0, it.next(), "Iterator should return records in reverse LSN order");
        assertFalse(it.hasNext(), "Iterator should be empty after reading all records");
    }

    @Test
    @DisplayName("Test Flush Optimization (No-Op Flush)")
    void testFlushOptimization() {
        int lsn0 = logMgr.append("rec_0".getBytes());
        logMgr.flush(lsn0); // lastFlushedLSN is now 0

        // This second flush should be a no-op and not cause a disk write.
        // I can't directly test the "no-op", but verify correctness.
        logMgr.flush(lsn0);

        Iterator<byte[]> it = logMgr.iterator();
        assertTrue(it.hasNext());
        assertArrayEquals("rec_0".getBytes(), it.next());
        assertFalse(it.hasNext());
    }

    @Test
    @DisplayName("Test Iteration Over Empty Log")
    void testEmptyLogIteration() {
        // Don't append anything
        Iterator<byte[]> it = logMgr.iterator();
        assertFalse(it.hasNext(), "Iterator on a new, empty log should be empty");
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    @DisplayName("Test Record Spanning Multiple Blocks")
    void testMultiBlockSpanning() {
        List<byte[]> records = new ArrayList<>();
        int recordsAppended = 0;

        // Append records until we know we've crossed at least one block boundary
        // A small record is ~15 bytes. 400/15 ~= 26 records per block. Let's add 50.
        for (int i = 0; i < 50; i++) {
            byte[] rec = ("record_spanning_" + i).getBytes();
            records.add(rec);
            logMgr.append(rec);
            recordsAppended++;
        }

        logMgr.flush(recordsAppended - 1); // Flush everything

        // Now, iterate backward and verify all records are present in the correct order
        Iterator<byte[]> it = logMgr.iterator();
        Collections.reverse(records); // To match the reverse iteration order

        for (byte[] expectedRec : records) {
            assertTrue(it.hasNext(), "Iterator should have more records");
            assertArrayEquals(expectedRec, it.next());
        }
        assertFalse(it.hasNext(), "Iterator should be empty after all records are read");
    }

    @Test
    @DisplayName("A single large record that almost fills the block should be handled correctly")
    void testLargeRecordAlmostFillingBlock() {
        // Create a record that leaves just enough space for the length prefix and boundary pointer
        int recordSize = blockSize - Integer.BYTES - Integer.BYTES - 1;
        byte[] largeRecord = new byte[recordSize];
        Arrays.fill(largeRecord, (byte) 'A');

        int lsn0 = logMgr.append(largeRecord);
        assertEquals(0, lsn0);

        // The next record, no matter how small, should cause a new block to be created
        byte[] smallRecord = "next".getBytes();
        int lsn1 = logMgr.append(smallRecord);
        assertEquals(1, lsn1);

        logMgr.flush(lsn1);

        // Verify both records can be read back
        Iterator<byte[]> it = logMgr.iterator();
        assertArrayEquals(smallRecord, it.next(), "Small record should be first in reverse iteration");
        assertArrayEquals(largeRecord, it.next(), "Large record should be second");
        assertFalse(it.hasNext());
    }

    @Nested
    @DisplayName("Persistence Tests (Shutdown and Restart)")
    class PersistenceTests {

        @Test
        @DisplayName("Restart with Existing Log Initializes LSN Correctly")
        void testRestartInitializesLSN() {
            logMgr.append("rec_0".getBytes());
            logMgr.append("rec_1".getBytes());
            int lastLsn = logMgr.append("rec_2".getBytes());
            assertEquals(2, lastLsn);

            // Simulate shutdown by flushing and creating a new LogMgr
            logMgr.flush(lastLsn);

            configureDB(false); // Configure for restart
            LogMgr newLogMgr = new LogMgr(fm, logFileName);

            // The new LogMgr should pick up where the old one left off
            int nextLsn = newLogMgr.append("rec_3".getBytes());
            assertEquals(3, nextLsn, "LSN should continue from 3 after restart");
        }

        @Test
        @DisplayName("Restart and Iterate Over Previously Flushed Log")
        void testRestartAndIterate() {
            byte[] rec0 = "persistent_rec_0".getBytes();
            byte[] rec1 = "persistent_rec_1".getBytes();
            logMgr.append(rec0);
            int lsn1 = logMgr.append(rec1);
            logMgr.flush(lsn1);

            // Simulate restart
            configureDB(false);
            LogMgr newLogMgr = new LogMgr(fm, logFileName);

            Iterator<byte[]> it = newLogMgr.iterator();
            assertTrue(it.hasNext());
            assertArrayEquals(rec1, it.next(), "Should read rec1 first");
            assertTrue(it.hasNext());
            assertArrayEquals(rec0, it.next(), "Should read rec0 second");
            assertFalse(it.hasNext());
        }
    }

    @Nested
    @DisplayName("Concurrency Test")
    class ConcurrencyTest {

        @Test
        @DisplayName("High contention concurrent appends should not lose data or duplicate LSNs")
        void testConcurrentAppendsWithoutDataLoss() throws InterruptedException {
            final int N_THREADS = 10;
            final int RECORDS_PER_THREAD = 1000;
            final int TOTAL_RECORDS = N_THREADS * RECORDS_PER_THREAD;
            ExecutorService executor = Executors.newFixedThreadPool(N_THREADS);

            // Use a thread-safe collection to store all returned LSNs
            final Set<Integer> collectedLSNs = ConcurrentHashMap.newKeySet();

            // Each thread will append records and add the returned LSN to the set
            for (int i = 0; i < N_THREADS; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    for (int j = 0; j < RECORDS_PER_THREAD; j++) {
                        byte[] rec = ("thread-" + threadId + "-record-" + j).getBytes(StandardCharsets.UTF_8);
                        int lsn = logMgr.append(rec);
                        collectedLSNs.add(lsn);
                    }
                });
            }

            // Wait for all threads to complete
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Threads did not complete in time");

            // 1. Verify LSN Uniqueness and Count
            assertEquals(TOTAL_RECORDS, collectedLSNs.size(),
                    "The number of unique LSNs collected should equal the total number of records appended.");

            // 2. Verify Data Integrity by reading everything back from the log
            // First, ensure all buffered records are written to disk
            logMgr.flush(TOTAL_RECORDS - 1);

            int recordsRead = 0;
            Iterator<byte[]> it = logMgr.iterator();
            while (it.hasNext()) {
                it.next();
                recordsRead++;
            }

            assertEquals(TOTAL_RECORDS, recordsRead,
                    "The number of records read back from the log should equal the total number appended.");
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
}
