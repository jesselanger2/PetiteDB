package edu.yu.dbimpl.tx;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.concurrency.LockAbortException;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TxMgr - end-to-end transaction functionality.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TxMgrTest {

    private static File dbDirectory;
    private TxMgrBase txMgr;

    @BeforeAll
    static void setUpClass() throws Exception {
        dbDirectory = Files.createTempDirectory("txmgr_test").toFile();
    }

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "txmgr_test.log");
        BufferMgrBase bm = new BufferMgr(fm, lm, 10, 10000);
        txMgr = new TxMgr(fm, lm, bm, 10000);
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
    @DisplayName("Test TxMgr creates unique transaction numbers")
    void testUniqueTxNumbers() {
        TxBase tx1 = txMgr.newTx();
        TxBase tx2 = txMgr.newTx();
        TxBase tx3 = txMgr.newTx();

        assertNotEquals(tx1.txnum(), tx2.txnum());
        assertNotEquals(tx2.txnum(), tx3.txnum());
        assertNotEquals(tx1.txnum(), tx3.txnum());

        tx1.commit();
        tx2.commit();
        tx3.commit();
    }

    @Test
    @Order(2)
    @DisplayName("Test TxMgr transaction numbers are monotonic")
    void testMonotonicTxNumbers() {
        TxBase tx1 = txMgr.newTx();
        int txnum1 = tx1.txnum();
        tx1.commit();

        TxBase tx2 = txMgr.newTx();
        int txnum2 = tx2.txnum();
        tx2.commit();

        assertTrue(txnum2 > txnum1, "Transaction numbers should be monotonically increasing");
    }

    @Test
    @Order(3)
    @DisplayName("Test complete ACID transaction lifecycle")
    void testCompleteTransactionLifecycle() {
        // Atomicity and Durability test
        TxBase tx1 = txMgr.newTx();
        assertEquals(TxBase.Status.ACTIVE, tx1.getStatus());

        BlockIdBase blk = tx1.append("lifecycle");
        tx1.pin(blk);

        // Multiple operations in one transaction
        tx1.setInt(blk, 0, 100, true);
        tx1.setBoolean(blk, 20, true, true);
        tx1.setDouble(blk, 40, 3.14159, true);
        tx1.setString(blk, 100, "ACID test", true);

        tx1.commit();
        assertEquals(TxBase.Status.COMMITTED, tx1.getStatus());

        // Verify all operations persisted atomically
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        assertEquals(100, tx2.getInt(blk, 0));
        assertTrue(tx2.getBoolean(blk, 20));
        assertEquals(3.14159, tx2.getDouble(blk, 40), 0.00001);
        assertEquals("ACID test", tx2.getString(blk, 100));
        tx2.commit();
    }

    @Test
    @Order(4)
    @DisplayName("Test transaction isolation with concurrent updates")
    void testIsolation() throws Exception {
        // Setup initial value
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("isolation");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 0, false);
        setupTx.commit();

        int numTransactions = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numTransactions);
        AtomicInteger successCount = new AtomicInteger(0);

        // Start multiple transactions that increment the counter
        for (int i = 0; i < numTransactions; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    TxBase tx = txMgr.newTx();
                    tx.pin(blk);
                    int current = tx.getInt(blk, 0);
                    Thread.sleep((long)(Math.random() * 10)); // Random delay
                    tx.setInt(blk, 0, current + 1, true);
                    tx.commit();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Some may timeout
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS));

        // Verify isolation - final count should equal successful transactions
        TxBase verifyTx = txMgr.newTx();
        verifyTx.pin(blk);
        int finalCount = verifyTx.getInt(blk, 0);
        verifyTx.commit();

        assertEquals(successCount.get(), finalCount,
                "Isolation: final count should match successful transactions");
    }

    @Test
    @Order(5)
    @DisplayName("Test consistency - rollback maintains database consistency")
    void testConsistency() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk1 = tx1.append("account1");
        BlockIdBase blk2 = tx1.append("account2");

        tx1.pin(blk1);
        tx1.pin(blk2);
        tx1.setInt(blk1, 0, 1000, false); // Account 1: $1000
        tx1.setInt(blk2, 0, 500, false);  // Account 2: $500
        tx1.commit();

        // Transfer $300 from account1 to account2, but rollback
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk1);
        tx2.pin(blk2);

        int acc1 = tx2.getInt(blk1, 0);
        int acc2 = tx2.getInt(blk2, 0);

        tx2.setInt(blk1, 0, acc1 - 300, true); // Deduct from account1
        tx2.setInt(blk2, 0, acc2 + 300, true); // Add to account2

        // Verify in-transaction state
        assertEquals(700, tx2.getInt(blk1, 0));
        assertEquals(800, tx2.getInt(blk2, 0));

        tx2.rollback(); // Undo the transfer

        // Verify consistency maintained
        TxBase tx3 = txMgr.newTx();
        tx3.pin(blk1);
        tx3.pin(blk2);
        assertEquals(1000, tx3.getInt(blk1, 0), "Account1 should be unchanged");
        assertEquals(500, tx3.getInt(blk2, 0), "Account2 should be unchanged");
        tx3.commit();
    }

    @Test
    @Order(6)
    @DisplayName("Test durability - committed data survives restart")
    void testDurability() throws Exception {
        String testFile = "durability_test";

        // First session: write data
        {
            Properties props = new Properties();
            props.setProperty(DBConfiguration.DB_STARTUP, "true");
            DBConfiguration.INSTANCE.get().setConfiguration(props);

            FileMgrBase fm1 = new FileMgr(dbDirectory, 400);
            LogMgrBase lm1 = new LogMgr(fm1, "durability.log");
            BufferMgrBase bm1 = new BufferMgr(fm1, lm1, 8, 10000);
            TxMgrBase txMgr1 = new TxMgr(fm1, lm1, bm1, 10000);

            TxBase tx = txMgr1.newTx();
            BlockIdBase blk = tx.append(testFile);
            tx.pin(blk);
            tx.setInt(blk, 0, 12345, true);
            tx.setString(blk, 50, "Durable Data", true);
            tx.commit();
        }

        // Simulate restart: new session
        {
            Properties props = new Properties();
            props.setProperty(DBConfiguration.DB_STARTUP, "false");
            DBConfiguration.INSTANCE.get().setConfiguration(props);
            FileMgrBase fm2 = new FileMgr(dbDirectory, 400);
            LogMgrBase lm2 = new LogMgr(fm2, "durability.log");
            BufferMgrBase bm2 = new BufferMgr(fm2, lm2, 8, 10000);
            TxMgrBase txMgr2 = new TxMgr(fm2, lm2, bm2, 10000);

            TxBase tx = txMgr2.newTx();
            BlockIdBase blk = new BlockId(testFile, 0);
            tx.pin(blk);
            assertEquals(12345, tx.getInt(blk, 0), "Data should survive restart");
            assertEquals("Durable Data", tx.getString(blk, 50), "Data should survive restart");
            tx.commit();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test mixed workload - reads and writes")
    void testMixedWorkload() throws Exception {
        TxBase setupTx = txMgr.newTx();
        BlockIdBase blk = setupTx.append("mixedworkload");
        setupTx.pin(blk);
        setupTx.setInt(blk, 0, 0, false);
        setupTx.commit();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(20);
        AtomicInteger readerCount = new AtomicInteger(0);
        AtomicInteger writerCount = new AtomicInteger(0);

        // 15 readers, 5 writers
        for (int i = 0; i < 20; i++) {
            final boolean isWriter = (i % 4 == 0); // Every 4th is a writer

            new Thread(() -> {
                try {
                    startLatch.await();
                    boolean success = false;
                    while (!success) {
                        TxBase tx = txMgr.newTx();
                        try {
                            tx.pin(blk);

                            if (isWriter) {
                                // Writer: Read-Modify-Write (Vulnerable to Upgrade Deadlock)
                                int current = tx.getInt(blk, 0); // S-Lock
                                // Small sleep to provoke concurrency issues
                                Thread.sleep((long)(Math.random() * 10));
                                tx.setInt(blk, 0, current + 1, true); // Upgrade to X-Lock
                                tx.commit();
                                writerCount.incrementAndGet();
                            } else {
                                // Reader: Just Read
                                tx.getInt(blk, 0); // S-Lock
                                tx.commit();
                                readerCount.incrementAndGet();
                            }
                            success = true;
                        } catch (LockAbortException e) {
                            // Deadlock detected: Rollback and retry
                            tx.rollback();
                            try {
                                Thread.sleep((long)(Math.random() * 100));
                            } catch (InterruptedException ignored) {}
                        } catch (Exception e) {
                            tx.rollback();
                            e.printStackTrace();
                            break; // Exit on non-lock errors
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        // Increase timeout to allow for retries
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "All transactions should complete");

        assertTrue(readerCount.get() > 0, "Some readers should succeed");
        assertTrue(writerCount.get() > 0, "Some writers should succeed");
    }

    @Test
    @Order(8)
    @DisplayName("Test transaction with all data types")
    void testAllDataTypes() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("alltypes");
        tx.pin(blk);

        // Write all types
        tx.setInt(blk, 0, 42, true);
        tx.setBoolean(blk, 20, true, true);
        tx.setDouble(blk, 40, 2.71828, true);
        tx.setString(blk, 100, "Hello World", true);
        tx.setBytes(blk, 200, new byte[]{1, 2, 3, 4, 5}, true);

        // Read back all types
        assertEquals(42, tx.getInt(blk, 0));
        assertTrue(tx.getBoolean(blk, 20));
        assertEquals(2.71828, tx.getDouble(blk, 40), 0.00001);
        assertEquals("Hello World", tx.getString(blk, 100));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, tx.getBytes(blk, 200));

        tx.commit();

        // Verify persistence
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        assertEquals(42, tx2.getInt(blk, 0));
        assertTrue(tx2.getBoolean(blk, 20));
        assertEquals(2.71828, tx2.getDouble(blk, 40), 0.00001);
        assertEquals("Hello World", tx2.getString(blk, 100));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, tx2.getBytes(blk, 200));
        tx2.commit();
    }

    @Test
    @Order(9)
    @DisplayName("Test multiple blocks per transaction")
    void testMultipleBlocks() {
        TxBase tx = txMgr.newTx();

        BlockIdBase blk1 = tx.append("multi1");
        BlockIdBase blk2 = tx.append("multi2");
        BlockIdBase blk3 = tx.append("multi3");

        tx.pin(blk1);
        tx.pin(blk2);
        tx.pin(blk3);

        tx.setInt(blk1, 0, 100, true);
        tx.setInt(blk2, 0, 200, true);
        tx.setInt(blk3, 0, 300, true);

        assertEquals(100, tx.getInt(blk1, 0));
        assertEquals(200, tx.getInt(blk2, 0));
        assertEquals(300, tx.getInt(blk3, 0));

        tx.commit();

        // Verify all blocks
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk1);
        tx2.pin(blk2);
        tx2.pin(blk3);
        assertEquals(100, tx2.getInt(blk1, 0));
        assertEquals(200, tx2.getInt(blk2, 0));
        assertEquals(300, tx2.getInt(blk3, 0));
        tx2.commit();
    }

    @Test
    @Order(10)
    @DisplayName("Test transaction operations after commit fail")
    void testOperationsAfterCommit() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("aftercommit");
        tx.pin(blk);
        tx.setInt(blk, 0, 100, true);
        tx.commit();

        // All operations should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> tx.pin(blk));
        assertThrows(IllegalStateException.class, () -> tx.getInt(blk, 0));
        assertThrows(IllegalStateException.class, () -> tx.setInt(blk, 0, 200, true));
        assertThrows(IllegalStateException.class, tx::commit);
        assertThrows(IllegalStateException.class, tx::rollback);
    }

    @Test
    @Order(11)
    @DisplayName("Test transaction operations after rollback fail")
    void testOperationsAfterRollback() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("afterrollback");
        tx.pin(blk);
        tx.setInt(blk, 0, 100, true);
        tx.rollback();

        // All operations should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> tx.pin(blk));
        assertThrows(IllegalStateException.class, () -> tx.getInt(blk, 0));
        assertThrows(IllegalStateException.class, () -> tx.setInt(blk, 0, 200, true));
        assertThrows(IllegalStateException.class, tx::commit);
        assertThrows(IllegalStateException.class, tx::rollback);
    }

    @Test
    @Order(12)
    @DisplayName("Test transaction blockSize and availableBuffs")
    void testUtilityMethods() {
        TxBase tx = txMgr.newTx();

        assertTrue(tx.blockSize() > 0, "Block size should be positive");
        assertEquals(400, tx.blockSize(), "Block size should match FileMgr setting");

        int availBefore = tx.availableBuffs();
        assertTrue(availBefore > 0, "Should have available buffers");

        BlockIdBase blk = tx.append("utiltest");
        tx.pin(blk);

        int availAfter = tx.availableBuffs();
        assertTrue(availAfter < availBefore, "Available buffers should decrease after pin");

        tx.commit();
    }

    @Test
    @Order(13)
    @DisplayName("Test file size and append operations")
    void testFileSizeAndAppend() {
        TxBase tx = txMgr.newTx();

        String filename = "sizetest";
        assertEquals(0, tx.size(filename));

        BlockIdBase blk1 = tx.append(filename);
        assertEquals(1, tx.size(filename));

        BlockIdBase blk2 = tx.append(filename);
        assertEquals(2, tx.size(filename));

        BlockIdBase blk3 = tx.append(filename);
        assertEquals(3, tx.size(filename));

        tx.commit();

        // Verify size persists
        TxBase tx2 = txMgr.newTx();
        assertEquals(3, tx2.size(filename));
        tx2.commit();
    }

    @Test
    @Order(14)
    @DisplayName("Test TxMgr resetAllLockState")
    void testResetAllLockState() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("resettest");
        tx.pin(blk);
        tx.setInt(blk, 0, 100, true);
        // Don't commit - leave lock held

        // Reset should clear all locks
        txMgr.resetAllLockState();

        // New transaction should be able to access immediately
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setInt(blk, 0, 200, true);
        tx2.commit();
    }

    @Test
    @Order(15)
    @DisplayName("Test high transaction throughput")
    void testHighThroughput() {
        int numTransactions = 100;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numTransactions; i++) {
            TxBase tx = txMgr.newTx();
            BlockIdBase blk = tx.append("throughput_" + i);
            tx.pin(blk);
            tx.setInt(blk, 0, i, true);
            tx.commit();
        }

        long duration = System.currentTimeMillis() - startTime;
        double throughput = (numTransactions * 1000.0) / duration;

        System.out.println(numTransactions + " transactions completed in " +
                duration + "ms (" + throughput + " tx/sec)");

        assertTrue(duration < 30000, "Should complete 100 transactions in under 30 seconds");
    }
}