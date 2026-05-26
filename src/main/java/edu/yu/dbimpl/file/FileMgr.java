package edu.yu.dbimpl.file;

import edu.yu.dbimpl.config.DBConfiguration;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FileMgr extends FileMgrBase {

    private final static Logger logger = LogManager.getLogger(FileMgr.class);
    private final File dbDirectory;
    private final int blocksize;
    // Locks for each file to manage concurrent access
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> fileLocks;
    // A constant for the maximum number of files to keep open.
    private static final int MAX_OPEN_FILES = 250;
    // Caching open file channels for performance
    private final Map<String, FileChannel> openFiles;

    public FileMgr(File dbDirectory, int blocksize) {
        super(dbDirectory, blocksize);
        if (blocksize <= 0) {
            throw new IllegalArgumentException("blocksize must be a positive integer");
        }
        this.dbDirectory = dbDirectory;
        this.blocksize = blocksize;
        this.fileLocks = new ConcurrentHashMap<>();
        // Initialize the LRU cache for open file channels
        Map<String, FileChannel> lruCache = new LinkedHashMap<>(MAX_OPEN_FILES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileChannel> eldest) {
                if (size() > MAX_OPEN_FILES) {
                    try {
                        eldest.getValue().close();
                        return true;
                    } catch (IOException e) {
                        logger.error(
                                "Failed to close file channel for evicted file: {}",
                                eldest.getKey(), e);
                        return false;
                    }
                }
                return false;
            }
        };
        this.openFiles = Collections.synchronizedMap(lruCache);
        // Check if the database is starting up fresh. If so, delete existing files.
        boolean isNew = DBConfiguration.INSTANCE.get().isDBStartup();
        logger.info("(Re)initialize database: {}", isNew);
        if (isNew && dbDirectory.exists()) {
            logger.info(
                    "Database is starting fresh. Deleting existing files in directory: {}",
                    dbDirectory.getAbsolutePath());
            closeAllFiles();
            // Recursively delete directory contents for a fresh start
            deleteDirectory(dbDirectory);
        }
        // Create the directory if it doesn't exist
        if (!dbDirectory.exists()) {
            logger.info("Creating database directory: {}", dbDirectory.getAbsolutePath());
            if (!dbDirectory.mkdirs()) {
                throw new RuntimeException("Could not create directory: " + dbDirectory.getAbsolutePath());
            }
        }
    }

    @Override
    public void read(BlockIdBase blk, PageBase p) {
        if (blk == null || p == null) {
            throw new IllegalArgumentException("blk and p must not be null");
        }
        String filename = blk.fileName();
        int blkNum = blk.number();
        logger.info("Reading block {} from file {}", blkNum, filename);
        ReentrantReadWriteLock lock = getFileLock(filename);
        lock.readLock().lock();
        try {
            FileChannel channel = getFileChannel(filename);
            ByteBuffer buffer = ((Page) p).getBuffer();
            buffer.clear();
            long position = (long) blkNum * blocksize;
            channel.read(buffer, position);
        } catch (IOException e) {
            logger.error("Failed to read block {} from file {}", blkNum, filename, e);
            throw new RuntimeException("Error reading block", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void write(BlockIdBase blk, PageBase p) {
        if (blk == null || p == null) {
            throw new IllegalArgumentException("blk and p must not be null");
        }
        String filename = blk.fileName();
        int blkNum = blk.number();
        logger.info("Writing page to block {} in file {}", blkNum, filename);
        ReentrantReadWriteLock lock = getFileLock(filename);
        lock.writeLock().lock();
        try {
            FileChannel channel = getFileChannel(filename);
            ByteBuffer buffer = ((Page) p).getBuffer();
            buffer.rewind();
            long position = (long) blkNum * blocksize;
            channel.write(buffer, position);
        } catch (IOException e) {
            logger.error("Failed to write page to file {}", filename, e);
            throw new RuntimeException("Error writing block", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public BlockIdBase append(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("filename must have a trimmed length greater than 0");
        }
        logger.info("Appending new block to file {}", filename);
        ReentrantReadWriteLock lock = getFileLock(filename);
        lock.writeLock().lock();
        try {
            int newBlockNum = length(filename); // Get the current length to determine the new block number
            BlockIdBase newBlk = new BlockId(filename, newBlockNum);
            // Extend the file by writing an empty block of the correct size
            Page emptyPage = new Page(new byte[blocksize]);
            write(newBlk, emptyPage); // Use our write method to handle file creation and writing
            return newBlk;
        } catch (Exception e) {
            logger.error("Failed to append block to file {}", filename, e);
            throw new RuntimeException("Error appending block", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int length(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("filename must have a trimmed length greater than 0");
        }
        ReentrantReadWriteLock lock = getFileLock(filename);
        lock.readLock().lock();
        try {
            File file = new File(dbDirectory, filename);
            if (!file.exists()) {
                return 0;
            }
            long fileLength = file.length();
            return (int) (fileLength / blocksize);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int blockSize() {
        return blocksize;
    }

    /**
     * Helper method to delete a directory and all its contents.
     */
    private synchronized void deleteDirectory(File directory) {
        // First close any open channels
        synchronized (openFiles) {
            for (FileChannel channel : openFiles.values()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    logger.error("Error closing channel during directory deletion", e);
                }
            }
            openFiles.clear();
        }
        // Clear all locks
        fileLocks.clear();
        // Proceed with deletion
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        if (!directory.delete()) {
            throw new RuntimeException("Failed to delete " + directory);
        }
    }

    /**
     * Helper method to close all open file channels.
     */
    private void closeAllFiles() {
        logger.info("Closing all open file channels.");
        // We must synchronize on the map when iterating
        synchronized(openFiles) {
            for (FileChannel fc : openFiles.values()) {
                try {
                    fc.close();
                } catch (IOException e) {
                    logger.error("Error closing a file channel", e);
                }
            }
            openFiles.clear();
        }
    }

    /**
     * Helper method to get or create a lock for a given filename.
     */
    private ReentrantReadWriteLock getFileLock(String filename) {
        return fileLocks.computeIfAbsent(filename, k -> new ReentrantReadWriteLock());
    }

    /**
     * Helper method to get or create a FileChannel for a given filename.
     */
    private FileChannel getFileChannel(String filename) throws IOException {
        synchronized (openFiles) {
            FileChannel fc = openFiles.get(filename);
            if (fc != null && fc.isOpen()) {
                return fc;
            }
            // Need to create new channel
            File file = new File(dbDirectory, filename);
            fc = FileChannel.open(file.toPath(),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.DSYNC);
            openFiles.put(filename, fc);
            return fc;
        }
    }
}