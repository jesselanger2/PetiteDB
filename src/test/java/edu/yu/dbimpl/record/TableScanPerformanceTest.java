package edu.yu.dbimpl.record;

import edu.yu.dbimpl.buffer.BufferMgr;
import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgr;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import java.util.stream.Stream;

public class TableScanPerformanceTest {

    private static final String TEST_DIR_NAME = "TableScanPerfTest";
    private static final String LOG_FILE = "perf_test.log";
    private static final String TABLE_NAME = "PerformanceTable";

    // Test Configuration Constraints
    private static final int BLOCK_SIZE = 400;
    private static final int BUFFER_SIZE = 8;
    private static final int TX_TIMEOUT_MS = 500;
    private static final int NUM_RECORDS = 100_000;
    private static final long EXPECTED_DURATION_MS = 3_500;

    private File dbDirectory;
    private TxMgrBase txMgr;

    @BeforeEach
    void setUp() throws IOException {
        // 1. Setup Directory
        dbDirectory = new File(TEST_DIR_NAME);
        cleanDirectory(dbDirectory.toPath()); // Ensure clean slate

        // 2. Configure DB Startup
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        // 3. Initialize Managers with specified constraints
        FileMgrBase fm = new FileMgr(dbDirectory, BLOCK_SIZE);
        LogMgrBase lm = new LogMgr(fm, LOG_FILE);
        BufferMgrBase bm = new BufferMgr(fm, lm, BUFFER_SIZE, TX_TIMEOUT_MS);
        txMgr = new TxMgr(fm, lm, bm, TX_TIMEOUT_MS);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (txMgr != null) {
            txMgr.resetAllLockState();
        }
        cleanDirectory(dbDirectory.toPath());
    }

    @Test
    @DisplayName("Insert Performance Test: 100k records, single tx")
    void testInsertPerformance() {
        System.out.println("Starting Insert Performance Test...");

        // 1. Setup Transaction and Schema
        TxBase tx = txMgr.newTx();

        Schema schema = new Schema();
        schema.addIntField("id");
        schema.addStringField("name", 15);
        schema.addBooleanField("active");
        schema.addDoubleField("salary");

        Layout layout = new Layout(schema);
        TableScan ts = new TableScan(tx, TABLE_NAME, layout);

        // 2. Run Workload and Time it
        long startTime = System.currentTimeMillis();

        try {
            for (int i = 0; i < NUM_RECORDS; i++) {
                ts.insert();
                ts.setInt("id", i);
                ts.setString("name", "rec" + i);
                ts.setBoolean("active", (i % 2 == 0));
                ts.setDouble("salary", i * 1.5);
            }
        } finally {
            ts.close();
            tx.commit();
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 3. Calculate Ratio and Report
        double ratio = (double) duration / EXPECTED_DURATION_MS;

        System.out.printf("Performance Result: %d records inserted in %d ms.%n", NUM_RECORDS, duration);
        System.out.printf("Expected Duration: %d ms.%n", EXPECTED_DURATION_MS);
        System.out.printf("Performance Ratio: %.4f%n", ratio);

        // 4. Assert Performance
        if (ratio > 1.0) {
            String errorMessage = String.format(
                    "@@@ mismatch between actual and expected performance ratios for insert performance test expected [1.0] but found [%s]",
                    ratio
            );
            throw new AssertionError(errorMessage);
        }
    }

    private void cleanDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}