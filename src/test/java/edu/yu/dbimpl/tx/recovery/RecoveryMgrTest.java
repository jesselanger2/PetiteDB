package edu.yu.dbimpl.tx.recovery;

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
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for RecoveryMgr - rollback and recovery scenarios.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RecoveryMgrTest {

    private static File dbDirectory;
    private BufferMgrBase bm;
    private TxMgrBase txMgr;
    private FileMgrBase fm;

    @BeforeAll
    static void setUpClass() throws Exception {
        dbDirectory = Files.createTempDirectory("recovery_test").toFile();
    }

    @BeforeEach
    void setUp() {
        // Configure as not a startup (we'll test startup separately)
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "recovery_test.log");
        bm = new BufferMgr(fm, lm, 8, 10000);
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
    @DisplayName("Test simple transaction commit")
    void testSimpleCommit() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("commitfile");

        tx.pin(blk);
        tx.setInt(blk, 0, 100, true);
        tx.setString(blk, 20, "test", true);
        tx.commit();

        // Verify values persisted - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals(100, p.getInt(0));
        assertEquals("test", p.getString(20));
    }

    @Test
    @Order(2)
    @DisplayName("Test transaction rollback - single value")
    void testSimpleRollback() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("rollbackfile");

        // Set initial value
        tx1.pin(blk);
        tx1.setInt(blk, 0, 999, false); // Don't log initial setup
        tx1.commit();

        // Start new transaction and modify
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        assertEquals(999, tx2.getInt(blk, 0));

        tx2.setInt(blk, 0, 111, true); // Log this change
        assertEquals(111, tx2.getInt(blk, 0));

        // Rollback
        tx2.rollback();

        // Verify value is restored - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals(999, p.getInt(0));
    }

    @Test
    @Order(3)
    @DisplayName("Test transaction rollback - multiple values")
    void testRollbackMultipleValues() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("multifile");

        // Set initial values
        tx1.pin(blk);
        tx1.setInt(blk, 0, 100, false);
        tx1.setBoolean(blk, 20, true, false);
        tx1.setDouble(blk, 40, 3.14, false);
        tx1.setString(blk, 100, "original", false);
        tx1.commit();

        // Modify and rollback
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setInt(blk, 0, 200, true);
        tx2.setBoolean(blk, 20, false, true);
        tx2.setDouble(blk, 40, 2.71, true);
        tx2.setString(blk, 100, "modified", true);

        // Verify modifications
        assertEquals(200, tx2.getInt(blk, 0));
        assertFalse(tx2.getBoolean(blk, 20));
        assertEquals(2.71, tx2.getDouble(blk, 40), 0.001);
        assertEquals("modified", tx2.getString(blk, 100));

        tx2.rollback();

        // Verify all values restored - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals(100, p.getInt(0));
        assertTrue(p.getBoolean(20));
        assertEquals(3.14, p.getDouble(40), 0.001);
        assertEquals("original", p.getString(100));
    }

    @Test
    @Order(4)
    @DisplayName("Test rollback with multiple modifications to same location")
    void testRollbackMultipleModificationsSameLocation() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("samefile");

        tx1.pin(blk);
        tx1.setInt(blk, 0, 10, false);
        tx1.commit();

        // Multiple modifications in same transaction
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setInt(blk, 0, 20, true);
        tx2.setInt(blk, 0, 30, true);
        tx2.setInt(blk, 0, 40, true);
        assertEquals(40, tx2.getInt(blk, 0));

        tx2.rollback();

        // Should restore to original value (10), not intermediate values
        // Read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals(10, p.getInt(0));
    }

    @Test
    @Order(5)
    @DisplayName("Test recovery on startup - uncommitted transaction")
    void testRecoveryUncommittedTransaction() throws Exception {
        // Create initial state
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("recoverfile");
        tx1.pin(blk);
        tx1.setInt(blk, 0, 500, false);
        tx1.commit();

        // Start transaction but don't commit (simulate crash)
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setInt(blk, 0, 777, true);
        assertEquals(777, tx2.getInt(blk, 0));

        // DON'T commit - simulate crash
        // Force the dirty buffer to disk to simulate partial write
        bm.flushAll(tx2.txnum());

        // Clear locks to simulate crash
        txMgr.resetAllLockState();

        // Simulate restart, but first set DB_STARTUP to false so FileMgr doesn't init
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);
        FileMgrBase fm2 = new FileMgr(dbDirectory, 400);

        // Initialize new managers to trigger recovery
        LogMgrBase lm2 = new LogMgr(fm2, "recovery_test.log");
        BufferMgrBase bm2 = new BufferMgr(fm2, lm2, 8, 10000);
        TxMgrBase txMgr2 = new TxMgr(fm2, lm2, bm2, 10000);

        // Verify recovery restored original value - read directly from disk
        Page p = new Page(fm2.blockSize());
        fm2.read(blk, p);
        assertEquals(500, p.getInt(0), "Recovery should restore original value");
    }

    @Test
    @Order(6)
    @DisplayName("Test recovery on startup - committed transaction not undone")
    void testRecoveryCommittedTransaction() throws Exception {
        // Clean setup
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm1 = new FileMgr(dbDirectory, 400);
        LogMgrBase lm1 = new LogMgr(fm1, "commit_test.log");
        BufferMgrBase bm1 = new BufferMgr(fm1, lm1, 8, 10000);
        TxMgrBase txMgr1 = new TxMgr(fm1, lm1, bm1, 10000);

        // Create and commit transaction
        TxBase tx1 = txMgr1.newTx();
        BlockIdBase blk = tx1.append("commitrecoverfile");
        tx1.pin(blk);
        tx1.setInt(blk, 0, 888, true);
        tx1.commit();

        // Simulate restart, but first set DB_STARTUP to false so FileMgr doesn't init
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);
        FileMgrBase fm2 = new FileMgr(dbDirectory, 400);

        // Initialize new managers to trigger recovery
        LogMgrBase lm2 = new LogMgr(fm2, "commit_test.log");
        BufferMgrBase bm2 = new BufferMgr(fm2, lm2, 8, 10000);
        TxMgrBase txMgr2 = new TxMgr(fm2, lm2, bm2, 10000);

        // Committed value should still be there - read directly from disk
        Page p = new Page(fm2.blockSize());
        fm2.read(blk, p);
        assertEquals(888, p.getInt(0), "Committed value should persist");
    }

    @Test
    @Order(7)
    @DisplayName("Test recovery with mix of committed and uncommitted transactions")
    void testRecoveryMixedTransactions() throws Exception {
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm1 = new FileMgr(dbDirectory, 400);
        LogMgrBase lm1 = new LogMgr(fm1, "mixed_test.log");
        BufferMgrBase bm1 = new BufferMgr(fm1, lm1, 8, 10000);
        TxMgrBase txMgr1 = new TxMgr(fm1, lm1, bm1, 10000);

        // Transaction 1: commit
        TxBase tx1 = txMgr1.newTx();
        BlockIdBase blk1 = tx1.append("mixedfile1");
        tx1.pin(blk1);
        tx1.setInt(blk1, 0, 111, true);
        tx1.commit();

        // Transaction 2: don't commit
        TxBase tx2 = txMgr1.newTx();
        BlockIdBase blk2 = tx2.append("mixedfile2");
        tx2.pin(blk2);
        tx2.setInt(blk2, 0, 222, true);
        bm1.flushAll(tx2.txnum());
        // DON'T commit

        // Transaction 3: commit
        TxBase tx3 = txMgr1.newTx();
        BlockIdBase blk3 = tx3.append("mixedfile3");
        tx3.pin(blk3);
        tx3.setInt(blk3, 0, 333, true);
        tx3.commit();

        // Clear locks
        txMgr1.resetAllLockState();

        // Simulate restart, but first set DB_STARTUP to false so FileMgr doesn't init
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);
        FileMgrBase fm2 = new FileMgr(dbDirectory, 400);

        // Initialize new managers to trigger recovery
        LogMgrBase lm2 = new LogMgr(fm2, "mixed_test.log");
        BufferMgrBase bm2 = new BufferMgr(fm2, lm2, 8, 10000);
        TxMgrBase txMgr2 = new TxMgr(fm2, lm2, bm2, 10000);

        // Check results - read directly from disk
        Page p1 = new Page(fm2.blockSize());
        fm2.read(blk1, p1);
        assertEquals(111, p1.getInt(0), "Committed tx1 should persist");

        Page p2 = new Page(fm2.blockSize());
        fm2.read(blk2, p2);
        // blk2 was never committed, so it should have initial value (0)
        assertEquals(0, p2.getInt(0), "Uncommitted tx2 should be undone");

        Page p3 = new Page(fm2.blockSize());
        fm2.read(blk3, p3);
        assertEquals(333, p3.getInt(0), "Committed tx3 should persist");
    }

    @Test
    @Order(8)
    @DisplayName("Test rollback doesn't affect committed data")
    void testRollbackIsolation() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("isolationfile");
        tx1.pin(blk);
        tx1.setInt(blk, 0, 100, false);
        tx1.commit();

        // Start transaction, modify, and rollback
        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setInt(blk, 0, 200, true);
        tx2.rollback();

        // Verify committed data unchanged - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals(100, p.getInt(0), "tx2's rollback shouldn't affect committed data");
    }

    @Test
    @Order(9)
    @DisplayName("Test rollback with byte arrays")
    void testRollbackByteArrays() {
        TxBase tx1 = txMgr.newTx();
        BlockIdBase blk = tx1.append("bytefile");

        byte[] original = new byte[]{1, 2, 3, 4, 5};
        byte[] modified = new byte[]{6, 7, 8, 9, 10};

        tx1.pin(blk);
        tx1.setBytes(blk, 50, original, false);
        tx1.commit();

        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.setBytes(blk, 50, modified, true);
        assertArrayEquals(modified, tx2.getBytes(blk, 50));

        tx2.rollback();

        // Verify original restored - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertArrayEquals(original, p.getBytes(50));
    }

    @Test
    @Order(10)
    @DisplayName("Test transaction status changes")
    void testTransactionStatus() {
        TxBase tx = txMgr.newTx();
        assertEquals(TxBase.Status.ACTIVE, tx.getStatus());

        BlockIdBase blk = tx.append("statusfile");
        tx.pin(blk);
        tx.setInt(blk, 0, 42, true);

        assertEquals(TxBase.Status.ACTIVE, tx.getStatus());

        tx.commit();
        assertEquals(TxBase.Status.COMMITTED, tx.getStatus());

        // Test rollback status
        TxBase tx2 = txMgr.newTx();
        assertEquals(TxBase.Status.ACTIVE, tx2.getStatus());
        tx2.pin(blk);
        tx2.setInt(blk, 0, 99, true);
        tx2.rollback();
        assertEquals(TxBase.Status.ROLLED_BACK, tx2.getStatus());
    }
}