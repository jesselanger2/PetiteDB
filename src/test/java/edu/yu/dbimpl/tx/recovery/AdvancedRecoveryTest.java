package edu.yu.dbimpl.tx.recovery;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdvancedRecoveryTest {

    private File dbDirectory;
    private static final String LOG_FILE = "recovery_log";
    private static final int BLOCK_SIZE = 400;

    @BeforeEach
    void setUp() throws IOException {
        dbDirectory = Files.createTempDirectory("recovery_adv").toFile();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteDirectory(dbDirectory.toPath());
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    @DisplayName("Crash Simulation: Committed Txs persist, Uncommitted Txs vanish")
    void testCrashRecovery() throws Exception {
        // --- STEP 1: INITIALIZE SYSTEM ---
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm = new FileMgr(dbDirectory, BLOCK_SIZE);
        LogMgrBase lm = new LogMgr(fm, LOG_FILE);
        BufferMgrBase bm = new BufferMgr(fm, lm, 8, 2000);
        TxMgrBase txMgr = new TxMgr(fm, lm, bm, 2000);

        BlockIdBase blk = new BlockId("datafile", 0);

        // Setup initial data
        TxBase tInit = txMgr.newTx();
        tInit.append("datafile");
        tInit.pin(blk);
        tInit.setInt(blk, 0, 100, false);
        tInit.commit();

        // --- STEP 2: RUN TRANSACTIONS ---
        // T1: Updates 100 -> 200 and COMMITS
        TxBase t1 = txMgr.newTx();
        t1.pin(blk);
        t1.setInt(blk, 0, 200, true);
        t1.commit();

        // T2: Updates 200 -> 300 but DOES NOT COMMIT (Simulating crash during active state)
        TxBase t2 = txMgr.newTx();
        t2.pin(blk);
        t2.setInt(blk, 0, 300, true);

        // Force the dirty buffer of T2 to disk to ensure the invalid 300 is actually in the file
        // This makes the test strictly check if Undo logic works
        bm.flushAll(t2.txnum());

        // --- STEP 3: SIMULATE CRASH & RESTART ---
        // We simulate a crash by creating NEW manager instances on the SAME directory
        // The JVM didn't crash, but the objects are lost.

        // Since LockTable is static, the locks from 't2' are still held in memory.
        // We must manually clear them to simulate the JVM restarting.
        txMgr.resetAllLockState();

        // Set configuration to RESTART (not startup)
        props.setProperty(DBConfiguration.DB_STARTUP, "false");
        DBConfiguration.INSTANCE.get().setConfiguration(props);
        FileMgrBase fmRec = new FileMgr(dbDirectory, BLOCK_SIZE); // Re-opens files
        LogMgrBase lmRec = new LogMgr(fmRec, LOG_FILE);
        BufferMgrBase bmRec = new BufferMgr(fmRec, lmRec, 8, 2000);
        // This constructor triggers performRecovery()
        TxMgrBase txMgrRec = new TxMgr(fmRec, lmRec, bmRec, 2000);

        // --- STEP 4: VERIFY STATE ---
        // Read directly from disk to verify recovery worked
        Page p = new Page(fmRec.blockSize());
        fmRec.read(blk, p);
        int val = p.getInt(0);

        // Expectation:
        // 100 (init) -> 200 (T1 Commit) -> 300 (T2 Uncommitted Flush).
        // Recovery should Undo T2, bringing it back to 200.
        assertEquals(200, val, "Uncommitted transaction T2 should be undone, Committed T1 should persist");
    }

    @Test
    @DisplayName("Rollback of Variable Length Data (Strings)")
    void testRollbackString() {
        // Setup
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);
        FileMgrBase fm = new FileMgr(dbDirectory, BLOCK_SIZE);
        LogMgrBase lm = new LogMgr(fm, LOG_FILE);
        BufferMgrBase bm = new BufferMgr(fm, lm, 8, 2000);
        TxMgrBase txMgr = new TxMgr(fm, lm, bm, 2000);

        TxBase t1 = txMgr.newTx();
        BlockIdBase blk = t1.append("strfile");
        t1.pin(blk);
        t1.setString(blk, 0, "InitialValue", true);
        t1.commit();

        TxBase t2 = txMgr.newTx();
        t2.pin(blk);
        assertEquals("InitialValue", t2.getString(blk, 0));

        // Change to longer string
        t2.setString(blk, 0, "ModifiedValueLonger", true);
        assertEquals("ModifiedValueLonger", t2.getString(blk, 0));

        // Rollback
        t2.rollback();

        // Verify rollback worked - read directly from disk
        Page p = new Page(fm.blockSize());
        fm.read(blk, p);
        assertEquals("InitialValue", p.getString(0), "String rollback failed");
    }
}