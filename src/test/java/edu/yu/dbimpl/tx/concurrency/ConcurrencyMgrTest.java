package edu.yu.dbimpl.tx.concurrency;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.*;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.sql.SQLOutput;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ConcurrencyMgr - multi-threading and concurrent access.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConcurrencyMgrTest {

    private static File dbDirectory;
    private TxMgrBase txMgr;

    @BeforeAll
    static void setUpClass() throws Exception {
        dbDirectory = Files.createTempDirectory("concurrency_test").toFile();
    }

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "concurrency_test.log");
        BufferMgrBase bm = new BufferMgr(fm, lm, 10, 5000);
        txMgr = new TxMgr(fm, lm, bm, 5000);
    }

    @AfterEach
    void tearDown() {
        txMgr.resetAllLockState();
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
    @DisplayName("Test shared locks - multiple readers allowed")
    void testMultipleReaders() throws Exception {
        // Setup: create block with initial value
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("readfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 100, false);
        setupTx.commit();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);
        AtomicInteger readCount = new AtomicInteger(0);

        // Start 3 reader transactions
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all to be ready
                    TxBase tx = txMgr.newTx();
                    tx.pin(blk);
                    int value = tx.getInt(blk, 0);
                    assertEquals(100, value);
                    readCount.incrementAndGet();
                    Thread.sleep(100); // Hold lock briefly
                    tx.commit();
                    doneLatch.countDown();
                } catch (Exception e) {
                    fail("Reader failed: " + e.getMessage());
                }
            }).start();
        }

        startLatch.countDown(); // Release all readers
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All readers should complete");
        assertEquals(3, readCount.get(), "All 3 readers should have read successfully");
    }

    @Test
    @Order(2)
    @DisplayName("Test exclusive lock - writer blocks readers")
    void testWriterBlocksReaders() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("writefile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 50, false);
        setupTx.commit();

        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch readerBlocked = new CountDownLatch(1);
        AtomicInteger readerValue = new AtomicInteger(-1);

        // Start writer transaction
        Thread writer = new Thread(() -> {
            try {
                TxBase tx = txMgr.newTx();
                tx.pin(blk);
                tx.setInt(blk, 0, 200, true);
                writerStarted.countDown();
                Thread.sleep(500); // Hold lock for a bit
                tx.commit();
            } catch (Exception e) {
                fail("Writer failed: " + e.getMessage());
            }
        });

        // Start reader transaction (should block)
        Thread reader = new Thread(() -> {
            try {
                writerStarted.await();
                Thread.sleep(100); // Ensure writer has lock
                readerBlocked.countDown();
                TxBase tx = txMgr.newTx();
                tx.pin(blk);
                int value = tx.getInt(blk, 0);
                readerValue.set(value);
                tx.commit();
            } catch (Exception e) {
                // Expected to wait
            }
        });

        writer.start();
        reader.start();

        assertTrue(readerBlocked.await(2, TimeUnit.SECONDS), "Reader should be blocked");
        writer.join(3000);
        reader.join(3000);

        assertEquals(200, readerValue.get(), "Reader should see writer's value after writer commits");
    }

    @Test
    @Order(3)
    @DisplayName("Test exclusive lock - only one writer allowed")
    void testOnlyOneWriter() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("singlewriterfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 10, false);
        setupTx.commit();

        CountDownLatch writer1Started = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        // Writer 1 - gets lock first
        Thread writer1 = new Thread(() -> {
            try {
                TxBase tx = txMgr.newTx();
                tx.pin(blk);
                tx.setInt(blk, 0, 100, true);
                writer1Started.countDown();
                Thread.sleep(1000); // Hold lock
                tx.commit();
                successCount.incrementAndGet();
            } catch (Exception e) {
                fail("Writer 1 failed: " + e.getMessage());
            }
        });

        // Writer 2 - should timeout or wait
        Thread writer2 = new Thread(() -> {
            try {
                writer1Started.await();
                Thread.sleep(100); // Ensure writer1 has lock
                TxBase tx = txMgr.newTx();
                tx.pin(blk);
                tx.setInt(blk, 0, 200, true); // Should block/timeout
                tx.commit();
                successCount.incrementAndGet();
            } catch (LockAbortException e) {
                timeoutCount.incrementAndGet();
            } catch (Exception e) {
                // May timeout
                timeoutCount.incrementAndGet();
            }
        });

        writer1.start();
        writer2.start();

        writer1.join(5000);
        writer2.join(5000);

        assertTrue(successCount.get() >= 1, "At least writer 1 should succeed");
    }

    @Test
    @Order(4)
    @DisplayName("Test lock upgrade - S-lock to X-lock")
    void testLockUpgrade() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("upgradefile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 25, false);
        setupTx.commit();

        TxBase tx = txMgr.newTx();
        tx.pin(blk);

        // First read (S-lock)
        int value = tx.getInt(blk, 0);
        assertEquals(25, value);

        // Then write (upgrade to X-lock)
        tx.setInt(blk, 0, 75, true);
        assertEquals(75, tx.getInt(blk, 0));

        tx.commit();

        // Verify
        TxBase verifyTx = txMgr.newTx();
        verifyTx.pin(blk);
        assertEquals(75, verifyTx.getInt(blk, 0));
        verifyTx.commit();
    }

    @Test
    @Order(5)
    @DisplayName("Test deadlock timeout")
    void testDeadlockTimeout() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk1 = setupTx.append("deadlock1");
        BlockIdBase blk2 = setupTx.append("deadlock2");
        setupTx.pin(blk1);
        setupTx.pin(blk2);
        setupTx.setInt(blk1, 0, 1, false);
        setupTx.setInt(blk2, 0, 2, false);
        setupTx.commit();

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        // Transaction 1: lock blk1, then try blk2
        Thread t1 = new Thread(() -> {
            try {
                TxBase tx = txMgr.newTx();
                tx.pin(blk1);
                tx.setInt(blk1, 0, 10, true);
                bothStarted.countDown();
                bothStarted.await(); // Wait for t2 to also start
                Thread.sleep(100);
                tx.pin(blk2); // Try to acquire blk2
                tx.setInt(blk2, 0, 20, true);
                tx.commit();
            } catch (LockAbortException e) {
                timeoutCount.incrementAndGet();
            } catch (Exception e) {
                // Expected
            }
        });

        // Transaction 2: lock blk2, then try blk1
        Thread t2 = new Thread(() -> {
            try {
                TxBase tx = txMgr.newTx();
                tx.pin(blk2);
                tx.setInt(blk2, 0, 30, true);
                bothStarted.countDown();
                bothStarted.await(); // Wait for t1 to also start
                Thread.sleep(100);
                tx.pin(blk1); // Try to acquire blk1
                tx.setInt(blk1, 0, 40, true);
                tx.commit();
            } catch (LockAbortException e) {
                timeoutCount.incrementAndGet();
            } catch (Exception e) {
                // Expected
            }
        });

        t1.start();
        t2.start();

        t1.join(10000);
        t2.join(10000);

        assertTrue(timeoutCount.get() >= 1, "At least one transaction should timeout in deadlock");
    }

    @Test
    @Order(6)
    @DisplayName("Concurrent Increments with Retry Logic")
    void testConcurrentIncrements() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("counterfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 0, false);
        setupTx.commit();

        int numThreads = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    boolean success = false;
                    while (!success) {
                        TxBase tx = txMgr.newTx();
                        try {
                            tx.pin(blk);
                            // Acquire S-Lock
                            int current = tx.getInt(blk, 0);

                            // Small sleep to stagger threads arriving at the upgrade point
                            Thread.sleep((long)(Math.random() * 50));

                            // Attempt X-Lock Upgrade
                            tx.setInt(blk, 0, current + 1, true);

                            tx.commit();
                            success = true;
                        } catch (LockAbortException e) {
                            // Deadlock: Must rollback to release S-Lock so others can proceed
                            tx.rollback();
                            // Backoff to prevent immediate livelock
                            try {
                                Thread.sleep((long)(Math.random() * 500) + 100);
                            } catch (InterruptedException ignored) {}
                        } catch (Exception e) {
                            tx.rollback();
                            e.printStackTrace();
                            break;
                        }
                    }
                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        startLatch.countDown();
        // Generous timeout (120s) to allow for retries
        assertTrue(doneLatch.await(120, TimeUnit.SECONDS), "All increments should complete");

        TxBase verifyTx = txMgr.newTx();
        verifyTx.pin(blk);
        assertEquals(numThreads, verifyTx.getInt(blk, 0));
        verifyTx.commit();
    }

    @Test
    @Order(7)
    @DisplayName("Test lock compatibility matrix")
    void testLockCompatibilityMatrix() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("matrixfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 0, false);
        setupTx.commit();

        // Test 1: S + S = OK
        TxBase tx1 = txMgr.newTx();
        TxBase tx2 = txMgr.newTx();
        tx1.pin(blk);
        int v1 = tx1.getInt(blk, 0); // S-lock
        tx2.pin(blk);
        int v2 = tx2.getInt(blk, 0); // S-lock (should succeed)
        assertEquals(v1, v2);
        tx1.commit();
        tx2.commit();

        // Test 2: X blocks S
        TxBase tx3 = txMgr.newTx();
        tx3.pin(blk);
        tx3.setInt(blk, 0, 100, true); // X-lock

        AtomicInteger blockedReaderValue = new AtomicInteger(-1);
        Thread blockedReader = new Thread(() -> {
            try {
                TxBase tx4 = txMgr.newTx();
                tx4.pin(blk);
                int value = tx4.getInt(blk, 0); // Should block
                blockedReaderValue.set(value);
                tx4.commit();
            } catch (Exception e) {
                // May timeout
            }
        });

        blockedReader.start();
        Thread.sleep(500); // Let reader attempt
        tx3.commit(); // Release lock
        blockedReader.join(3000);

        assertEquals(100, blockedReaderValue.get(), "Reader should see writer's value");
    }

    @Test
    @Order(8)
    @DisplayName("Test file-level locking for size() and append()")
    void testFileLevelLocking() throws Exception {
        String filename = "filelocktest";

        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk1 = tx1.append(filename);
        tx1.commit();

        AtomicInteger size1 = new AtomicInteger(-1);
        AtomicInteger size2 = new AtomicInteger(-1);
        CountDownLatch appendStarted = new CountDownLatch(1);

        // Transaction appending
        Thread appender = new Thread(() -> {
            try {
                TxBase tx = txMgr.newTx();
                appendStarted.countDown();
                tx.append(filename); // X-lock on file
                Thread.sleep(500);
                tx.commit();
            } catch (Exception e) {
                fail("Appender failed: " + e.getMessage());
            }
        });

        // Transaction checking size (should block)
        Thread sizeChecker = new Thread(() -> {
            try {
                appendStarted.await();
                Thread.sleep(100);
                TxBase tx = txMgr.newTx();
                size1.set(tx.size(filename)); // S-lock on file
                tx.commit();
            } catch (Exception e) {
                // May timeout
            }
        });

        appender.start();
        sizeChecker.start();

        appender.join(5000);
        sizeChecker.join(5000);

        // Verify size after both complete
        TxBase verifyTx = txMgr.newTx();
        int finalSize = verifyTx.size(filename);
        verifyTx.commit();

        assertTrue(finalSize >= 2, "File should have at least 2 blocks");
    }

    @Test
    @Order(9)
    @DisplayName("Test transaction isolation")
    void testTransactionIsolation() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("isolationfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 100, false);
        setupTx.commit();

        TxBase tx1 = txMgr.newTx();
        tx1.pin(blk);
        tx1.setInt(blk, 0, 200, true);

        // tx2 should not see tx1's uncommitted changes
        AtomicInteger tx2Value = new AtomicInteger(-1);
        CountDownLatch tx2Done = new CountDownLatch(1);

        Thread t2 = new Thread(() -> {
            try {
                Thread.sleep(200); // Let tx1 modify
                TxBase tx2 = txMgr.newTx();
                tx2.pin(blk);
                tx2Value.set(tx2.getInt(blk, 0));
                tx2.commit();
                tx2Done.countDown();
            } catch (Exception e) {
                // Expected to block
            }
        });

        t2.start();
        Thread.sleep(500);
        tx1.commit(); // Now tx2 can proceed

        assertTrue(tx2Done.await(5, TimeUnit.SECONDS));
        assertEquals(200, tx2Value.get(), "tx2 should see tx1's committed value");
    }

    @Test
    @Order(10)
    @DisplayName("Test lock release on commit")
    void testLockReleaseOnCommit() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("releasefile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 50, false);
        setupTx.commit();

        TxBase tx1 = txMgr.newTx();
        tx1.pin(blk);
        tx1.setInt(blk, 0, 150, true);
        tx1.commit(); // Should release lock

        // tx2 should immediately acquire lock
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        assertEquals(150, tx2.getInt(blk, 0));
        tx2.commit();
    }

    @Test
    @Order(11)
    @DisplayName("Test 10,000 sequential transactions updating multiple types")
    void testTenThousandSequentialTransactions() throws Exception {
        // Setup block with initial values
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("massfile");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 0, false);
        setupTx.setDouble(blk, 4, 0.0, false);
        setupTx.setString(blk, 12, "start", false);
        setupTx.setBoolean(blk, 200, false, false);
        setupTx.commit();

        final int iterations = 10_000;
        final int maxStringLen = 200; // keep the stored string bounded to avoid exceeding block size
        System.out.println("Starting " + iterations + " sequential transactions test...");

        long startNs = System.nanoTime();
        for (int i = 1; i <= iterations; i++) {
            TxBase tx = txMgr.newTx();
            tx.pin(blk);

            int curInt = tx.getInt(blk, 0);
            double curDouble = tx.getDouble(blk, 4);
            String curString = tx.getString(blk, 12);
            boolean curBool = tx.getBoolean(blk, 200);

            if (curString == null) curString = "start";

            // Update values; keep string length bounded by trimming from front when necessary
            String newString;
            if (curString.length() < maxStringLen) {
                newString = curString + "|";
            } else {
                // drop first char to keep the total length within maxStringLen
                newString = curString.substring(1) + "|";
            }

            tx.setInt(blk, 0, curInt + 1, true);
            tx.setDouble(blk, 4, curDouble + 1.0, true);
            tx.setString(blk, 12, newString, true);
            tx.setBoolean(blk, 200, !curBool, true);

            tx.commit();

            // Detailed logging every 1000 iterations and the first/last few
            if (i <= 5 || i % 1000 == 0 || i > iterations - 5) {
                System.out.printf(
                        "Iteration %d: int=%d, double=%.2f, stringLen=%d, bool=%b%n",
                        i, curInt + 1, curDouble + 1.0, newString.length(), !curBool
                );
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        System.out.println("Completed " + iterations + " transactions in " + elapsedMs + " ms");

        // Verify final values
        TxBase verifyTx = txMgr.newTx();
        verifyTx.pin(blk);

        int finalInt = verifyTx.getInt(blk, 0);
        double finalDouble = verifyTx.getDouble(blk, 4);
        String finalString = verifyTx.getString(blk, 12);
        boolean finalBool = verifyTx.getBoolean(blk, 200);

        System.out.println("Final values -> int: " + finalInt + ", double: " + finalDouble +
                ", stringLen: " + (finalString != null ? finalString.length() : 0) +
                ", bool: " + finalBool);

        verifyTx.commit();

        // Expected results
        assertEquals(iterations, finalInt, "Final int should equal number of iterations");
        assertEquals(iterations, finalDouble, 1e-9, "Final double should equal number of iterations");
        assertNotNull(finalString);
        // The stored string is kept bounded; expected length is at most maxStringLen
        assertEquals(maxStringLen, finalString.length(), "Final string length mismatch");
        // Initial boolean was false, toggled iterations times -> false if even, true if odd
        assertFalse(finalBool, "Final boolean mismatch");

        // Timing expectation: at most 8,100 ms (allow a tolerance)
        long expectedMs = 8100L;
        long toleranceMs = 1000L;
        assertTrue(elapsedMs <= expectedMs + toleranceMs,
                "Elapsed time " + elapsedMs + " ms exceeds expected " + expectedMs + " ms (+ tolerance " + toleranceMs + " ms)");
        System.out.println("Test passed within expected time frame:\n" +
                "Elapsed time: " + elapsedMs + " ms, Expected max time: " + (expectedMs + toleranceMs) + " ms");
    }

}