package edu.yu.dbimpl.log;

import edu.yu.dbimpl.file.*;

import java.util.Iterator;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LogMgr extends LogMgrBase {

    /**
     * A pointer to the boundary (start) of the last written record in a page.
     * This is always stored at the beginning of every log block.
     */
    protected static final int LAST_POS_POINTER = 0;

    private final FileMgrBase fm;
    private final String logfile;
    private final PageBase logPage;
    private BlockIdBase currentBlk;
    private int nextLSN = 0;
    private int lastFlushedLSN = -1;

    private final Lock lock = new ReentrantLock();
    private final Condition pageHasSpace = lock.newCondition();

    public LogMgr(FileMgrBase fm, String logfile) {
        super(fm, logfile);
        this.fm = fm;
        this.logfile = logfile;
        this.logPage = new Page(fm.blockSize());

        int logSize = fm.length(logfile);
        if (logSize == 0) {
            // This is a new log, append a block. This needs locking.
            lock.lock();
            try {
                appendNewBlock();
            } finally {
                lock.unlock();
            }
        } else {
            currentBlk = new BlockId(logfile, logSize - 1);
            fm.read(currentBlk, logPage);
            initializeLSN();
        }
    }

    @Override
    public void flush(int lsn) {
        if (lsn < 0 || lsn >= nextLSN) {
            throw new IllegalArgumentException("LSN " + lsn + " is out of valid range (0 to " + (nextLSN - 1) + ")");
        }
        lock.lock();
        try {
            if (lsn > lastFlushedLSN) {
                forceFlush();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Iterator<byte[]> iterator() {
        lock.lock();
        try {
            // First, ensure all buffered records are on disk before iterating
            forceFlush();
            return new LogIterator(fm, currentBlk);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int append(byte[] logrec) {
        lock.lock();
        try {
            // Calculate space needed: record data + 4 bytes for LSN
            int recSize = logrec.length + Integer.BYTES;
            int boundary = logPage.getInt(LAST_POS_POINTER);

            // If there's not enough space, we must flush
            if (boundary - recSize < Integer.BYTES) { // Must leave space for the pointer itself
                // This thread will perform the flush
                forceFlush();
                appendNewBlock();
                // After flushing, signal ALL other waiting threads that space is now available
                pageHasSpace.signalAll();
                boundary = logPage.getInt(LAST_POS_POINTER); // Reset boundary for the new block
            }

            int recPosition = boundary - recSize;
            logPage.setBytes(recPosition, logrec); // setBytes also writes the length
            logPage.setInt(LAST_POS_POINTER, recPosition); // Update the boundary pointer

            int currentLSN = nextLSN;
            nextLSN++;
            return currentLSN;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes the current in-memory log page to disk and updates the last flushed LSN.
     */
    private void forceFlush() {
        fm.write(currentBlk, logPage);
        lastFlushedLSN = nextLSN - 1;
    }

    /**
     * Appends a new, empty block to the log file and initializes it.
     */
    private void appendNewBlock() {
        currentBlk = fm.append(logfile);
        // Initialize the new block by setting its last record pointer to the end of the page
        logPage.setInt(LAST_POS_POINTER, fm.blockSize());
        fm.write(currentBlk, logPage);
    }

    /**
     * Scans the entire log file to count existing records and set the nextLSN accordingly.
     */
    private void initializeLSN() {
        int recordCount = 0;
        // Create an iterator starting at the last block of the log file.
        Iterator<byte[]> it = new LogIterator(fm, currentBlk);
        // Iterate through the entire log to count all existing records.
        while (it.hasNext()) {
            it.next(); // Consume the record
            recordCount++;
        }
        // The next LSN is the total number of records found.
        // The last flushed LSN is one less than that.
        nextLSN = recordCount;
        lastFlushedLSN = recordCount - 1;
    }
}
