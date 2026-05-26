package edu.yu.dbimpl.tx.concurrency;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.BlockId;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdvancedConcurrencyTest {

    private static File dbDirectory;
    private TxMgrBase txMgr;

    @BeforeEach
    void setUp() throws IOException {
        dbDirectory = Files.createTempDirectory("adv_concurrency_test").toFile();
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "test.log");
        BufferMgrBase bm = new BufferMgr(fm, lm, 8, 2000); // 2 sec wait for buffers
        // Set wait time to 2000ms for locks to test timeouts
        txMgr = new TxMgr(fm, lm, bm, 2000);
    }

    @AfterEach
    void tearDown() throws IOException {
        txMgr.resetAllLockState();
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
    @DisplayName("The Upgrade Deadlock: Two readers try to upgrade to Write simultaneously")
    void testLockUpgradeDeadlock() throws InterruptedException {
        /*
         * Scenario:
         * T1 gets S-Lock.
         * T2 gets S-Lock (allowed).
         * T1 tries to setInt (Needs X-Lock). It waits for T2 to release S-Lock.
         * T2 tries to setInt (Needs X-Lock). It waits for T1 to release S-Lock.
         * Result: Deadlock. One must abort.
         */

        final BlockIdBase blk = new BlockId("deadlock_file", 1);

        // Setup block
        TxBase setup = txMgr.newTx();
        setup.append("deadlock_file");
        setup.commit();

        CountDownLatch t1Ready = new CountDownLatch(1);
        CountDownLatch t2Ready = new CountDownLatch(1);
        AtomicBoolean t1Failed = new AtomicBoolean(false);
        AtomicBoolean t2Failed = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            TxBase tx = txMgr.newTx();
            try {
                tx.pin(blk);
                tx.getInt(blk, 0); // Acquire S-Lock
                t1Ready.countDown();

                // Wait for T2 to get its S-Lock
                t2Ready.await();
                Thread.sleep(100);

                // Try to upgrade to X-Lock
                tx.setInt(blk, 0, 100, true);
                tx.commit();
            } catch (LockAbortException e) {
                t1Failed.set(true);
                tx.rollback();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Thread t2 = new Thread(() -> {
            TxBase tx = txMgr.newTx();
            try {
                t1Ready.await(); // Wait for T1 to get S-Lock
                tx.pin(blk);
                tx.getInt(blk, 0); // Acquire S-Lock
                t2Ready.countDown();

                Thread.sleep(100);

                // Try to upgrade to X-Lock
                tx.setInt(blk, 0, 200, true);
                tx.commit();
            } catch (LockAbortException e) {
                t2Failed.set(true);
                tx.rollback();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // In a proper deadlock detection system, exactly one should fail (abort), the other succeeds.
        // Or both fail if timeout logic is simple. But at least one MUST fail.
        assertTrue(t1Failed.get() || t2Failed.get(), "At least one transaction must abort due to upgrade deadlock");
    }

    @Test
    @DisplayName("Circular Deadlock: A waits for B, B waits for A")
    void testCircularDeadlock() throws InterruptedException {
        final BlockIdBase blk1 = new BlockId("file", 1);
        final BlockIdBase blk2 = new BlockId("file", 2);

        // Setup
        TxBase setup = txMgr.newTx();
        setup.append("file");
        setup.append("file");
        setup.commit();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            TxBase tx = txMgr.newTx();
            try {
                tx.pin(blk1);
                tx.setInt(blk1, 0, 1, false); // X-Lock blk1
                latch.countDown();
                latch.await(); // Wait for T2 to grab blk2
                Thread.sleep(100);

                tx.pin(blk2);
                tx.getInt(blk2, 0); // Try S-Lock blk2 (Held by T2)
                tx.commit();
            } catch (LockAbortException e) {
                deadlockDetected.set(true);
                tx.rollback();
            } catch (Exception e) { e.printStackTrace(); }
        });

        Thread t2 = new Thread(() -> {
            TxBase tx = txMgr.newTx();
            try {
                tx.pin(blk2);
                tx.setInt(blk2, 0, 1, false); // X-Lock blk2
                latch.countDown();
                latch.await(); // Wait for T1 to grab blk1
                Thread.sleep(100);

                tx.pin(blk1);
                tx.getInt(blk1, 0); // Try S-Lock blk1 (Held by T1)
                tx.commit();
            } catch (LockAbortException e) {
                deadlockDetected.set(true);
                tx.rollback();
            } catch (Exception e) { e.printStackTrace(); }
        });

        t1.start(); t2.start();
        t1.join(); t2.join();

        assertTrue(deadlockDetected.get(), "Deadlock should be detected via LockAbortException");
    }
}