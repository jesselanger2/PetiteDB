package edu.yu.dbimpl.tx;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.BlockId;
import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AdvancedTxMgrTest {
    private static File dbDirectory;
    private TxMgrBase txMgr;

    @BeforeEach
    void setUp() throws IOException {
        dbDirectory = Files.createTempDirectory("txmgr_adv").toFile();
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgrBase fm = new FileMgr(dbDirectory, 400);
        LogMgrBase lm = new LogMgr(fm, "txmgr.log");
        BufferMgrBase bm = new BufferMgr(fm, lm, 50, 2000);
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
    @DisplayName("Transaction State Validation")
    void testIllegalStateTransitions() {
        TxBase tx = txMgr.newTx();
        BlockIdBase blk = tx.append("state_test");
        tx.pin(blk);
        tx.setInt(blk, 0, 1, true);

        tx.commit();

        // Should not be able to do anything after commit
        assertThrows(IllegalStateException.class, () -> tx.setInt(blk, 0, 2, true));
        assertThrows(IllegalStateException.class, () -> tx.getInt(blk, 0));
        assertThrows(IllegalStateException.class, () -> tx.commit());
        assertThrows(IllegalStateException.class, () -> tx.rollback());

        TxBase tx2 = txMgr.newTx();
        tx2.pin(blk);
        tx2.rollback();
        assertThrows(IllegalStateException.class, () -> tx2.pin(blk));
    }

    @Test
    @DisplayName("High Concurrency Stress Test")
    void testConcurrencyStress() throws InterruptedException {
        // Run 50 threads updating different blocks in the same file
        int numThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        TxBase setup = txMgr.newTx();
        setup.append("stress_file"); // Block 0
        setup.commit();

        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    TxBase tx = txMgr.newTx();
                    // All threads hit the same file, but we will append new blocks
                    // or use block 0. To test contention, let's all read block 0
                    // and then some write to their own new block.
                    BlockIdBase b0 = new BlockId("stress_file", 0);
                    tx.pin(b0);
                    tx.getInt(b0, 0); // S-Lock on block 0

                    // Append their own block
                    BlockIdBase myBlock = tx.append("stress_file");
                    tx.pin(myBlock);
                    tx.setInt(myBlock, 0, id, true);

                    tx.commit();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, errors.get(), "Concurrent transactions threw unexpected exceptions");

        // Verify size
        TxBase verify = txMgr.newTx();
        // Initial block + 50 appended blocks = 51 blocks total
        int size = verify.size("stress_file");
        verify.commit();
        assertEquals(numThreads + 1, size);
    }
}