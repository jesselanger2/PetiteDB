package edu.yu.dbimpl.file;

import edu.yu.dbimpl.config.DBConfiguration;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FileMgrTest {

    private File testDbDirectory;
    private final int blockSize = 400; // A standard block size for testing

    /**
     * This method runs before each test. It ensures a clean environment by:
     * 1. Deleting the test directory if it exists from a previous run.
     * 2. Setting the DBConfiguration to "startup" mode, so FileMgr initializes a fresh database.
     * 3. Creating the test directory.
     */
    @BeforeEach
    void setUp() throws IOException {
        String testDirName = "filemgr_test_dir";
        testDbDirectory = new File(testDirName);
        // Clean up any remnants from previous failed tests
        if (testDbDirectory.exists()) {
            deleteDirectory(testDbDirectory.toPath());
        }

        // Configure the system for a fresh database startup
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "true");
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        // Explicitly create the directory for the test
        assertTrue(testDbDirectory.mkdir(), "Test directory should be created");
    }

    /**
     * This method runs after each test. It cleans up the test directory
     * to ensure that tests are isolated and don't interfere with each other.
     */
    @AfterEach
    void tearDown() throws IOException {
        if (testDbDirectory.exists()) {
            deleteDirectory(testDbDirectory.toPath());
        }
    }

    @Test
    @DisplayName("Write and Read a single block with various data types")
    void testWriteAndRead() {
        FileMgr fm = new FileMgr(testDbDirectory, blockSize);
        BlockIdBase blk = new BlockId("testfile.dat", 0);

        // 1. Write data to a page
        Page writePage = new Page(blockSize);
        String testString = "Hello, PetiteDB!";
        int testInt = 2025;
        double testDouble = 3.14159;
        boolean testBool = true;
        byte[] testBytes = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

        writePage.setString(10, testString);
        writePage.setInt(60, testInt);
        writePage.setDouble(100, testDouble);
        writePage.setBoolean(150, testBool);
        writePage.setBytes(200, testBytes);

        fm.write(blk, writePage);

        // 2. Read the data back into a new page
        Page readPage = new Page(blockSize);
        fm.read(blk, readPage);

        // 3. Verify the data
        assertEquals(testString, readPage.getString(10));
        assertEquals(testInt, readPage.getInt(60));
        assertEquals(testDouble, readPage.getDouble(100));
        assertEquals(testBool, readPage.getBoolean(150));
        assertArrayEquals(testBytes, readPage.getBytes(200));
    }

    @Test
    @DisplayName("Append blocks to a file and check file length")
    void testAppendAndLength() {
        FileMgr fm = new FileMgr(testDbDirectory, blockSize);
        String filename = "appendtest.dat";

        assertEquals(0, fm.length(filename), "Length of a new file should be 0");

        // Append first block
        BlockIdBase blk0 = fm.append(filename);
        assertEquals(0, blk0.number(), "First appended block should have number 0");
        assertEquals(1, fm.length(filename), "Length after one append should be 1");

        // Append second block
        BlockIdBase blk1 = fm.append(filename);
        assertEquals(1, blk1.number(), "Second appended block should have number 1");
        assertEquals(2, fm.length(filename), "Length after two appends should be 2");
    }

    @Test
    @DisplayName("Test data persistence between FileMgr instances")
    void testPersistence() {
        // --- Phase 1: Write data with the first FileMgr ---
        FileMgr fm1 = new FileMgr(testDbDirectory, blockSize);
        BlockIdBase blk = new BlockId("persist_test.dat", 5);
        Page writePage = new Page(blockSize);
        int magicNumber = 12345;
        writePage.setInt(0, magicNumber);
        fm1.write(blk, writePage);

        // --- Phase 2: Create a new FileMgr that uses the existing database ---
        Properties props = new Properties();
        props.setProperty(DBConfiguration.DB_STARTUP, "false"); // IMPORTANT: Don't re-initialize
        DBConfiguration.INSTANCE.get().setConfiguration(props);

        FileMgr fm2 = new FileMgr(testDbDirectory, blockSize);
        Page readPage = new Page(blockSize);
        fm2.read(blk, readPage);

        assertEquals(magicNumber, readPage.getInt(0), "Data should persist across FileMgr instances");
    }

    @Test
    @DisplayName("Test database re-initialization")
    void testReinitialization() {
        // --- Phase 1: Write data ---
        FileMgr fm1 = new FileMgr(testDbDirectory, blockSize);
        BlockIdBase blk = new BlockId("reinit_test.dat", 0);
        Page writePage = new Page(blockSize);
        writePage.setInt(0, 999);
        fm1.write(blk, writePage);

        // --- Phase 2: Create a new FileMgr with startup=true to wipe the DB ---
        // The @BeforeEach already set startup=true, so we just need a new FileMgr instance
        FileMgr fm2 = new FileMgr(testDbDirectory, blockSize);
        Page readPage = new Page(blockSize);
        fm2.read(blk, readPage); // Read from the same block

        assertEquals(0, readPage.getInt(0), "Data should be zeroed out after re-initialization");
    }

    @Test
    @DisplayName("Read from a non-existent block should result in a zeroed page")
    void testReadFromUnwrittenBlock() {
        FileMgr fm = new FileMgr(testDbDirectory, blockSize);
        // We never write to this block
        BlockIdBase blk = new BlockId("unwritten.dat", 10);
        Page page = new Page(blockSize);

        fm.read(blk, page);

        // Check a few random spots in the page to see if they are zero
        assertEquals(0, page.getInt(0));
        assertEquals(0.0, page.getDouble(50));
        assertFalse(page.getBoolean(100));
    }

    @Test
    @DisplayName("Page should throw exception for out-of-bounds access")
    void testPageBoundsCheck() {
        Page page = new Page(blockSize); // blockSize is 400

        // Test writing past the end
        assertThrows(IllegalArgumentException.class, () -> page.setInt(398, 123));

        // Test reading past the end
        assertThrows(IllegalArgumentException.class, () -> page.getInt(397));

        // Test writing with a negative offset
        assertThrows(IllegalArgumentException.class, () -> page.setInt(-1, 123));
    }

    @Nested
    @DisplayName("Concurrency Tests for FileMgr")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent reads and writes to the same file should not corrupt data")
        void testConcurrentReadsAndWrites() throws InterruptedException, ExecutionException {
            final FileMgr fm = new FileMgr(testDbDirectory, blockSize);
            final String filename = "concurrent_file.dat";
            final int nThreads = 10;
            final int nBlocks = 50;
            final int operationsPerThread = 100;
            final ExecutorService executor = Executors.newFixedThreadPool(nThreads);
            final Random random = new Random();

            // A thread-safe map to store the "correct" value for each block
            final ConcurrentHashMap<Integer, Integer> blockTruth = new ConcurrentHashMap<>();

            Callable<Boolean> task = () -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    int blockNum = random.nextInt(nBlocks);
                    BlockIdBase blk = new BlockId(filename, blockNum);

                    // 50/50 chance to either read or write
                    if (random.nextBoolean()) {
                        // WRITE operation
                        Page writePage = new Page(blockSize);
                        int valueToWrite = random.nextInt();
                        writePage.setInt(0, valueToWrite);
                        fm.write(blk, writePage);
                        blockTruth.put(blockNum, valueToWrite); // Record the truth
                    } else {
                        // READ operation
                        Page readPage = new Page(blockSize);
                        fm.read(blk, readPage);
                        Integer expectedValue = blockTruth.get(blockNum);
                        if (expectedValue != null) {
                            assertEquals(expectedValue.intValue(), readPage.getInt(0),
                                    "Read incorrect value from block " + blockNum);
                        }
                    }
                }
                return true;
            };

            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < nThreads; i++) {
                futures.add(executor.submit(task));
            }

            // Wait for all threads to complete and check for exceptions
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(), "A thread failed its execution");
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Concurrent appends should be atomic and correct")
        void testConcurrentAppends() throws InterruptedException {
            final FileMgr fm = new FileMgr(testDbDirectory, blockSize);
            final String filename = "concurrent_append.dat";
            final int nThreads = 20;
            final int appendsPerThread = 10;
            final ExecutorService executor = Executors.newFixedThreadPool(nThreads);

            Runnable task = () -> {
                for (int i = 0; i < appendsPerThread; i++) {
                    fm.append(filename);
                }
            };

            for (int i = 0; i < nThreads; i++) {
                executor.submit(task);
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            int expectedLength = nThreads * appendsPerThread;
            assertEquals(expectedLength, fm.length(filename), "File length should match total appends");
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
