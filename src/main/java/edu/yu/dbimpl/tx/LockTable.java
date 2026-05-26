package edu.yu.dbimpl.tx;

import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.tx.concurrency.LockAbortException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Global lock table that manages all locks in the system.
 * <p>
 * Implements a strict two-phase locking (2PL) protocol with:
 * - Shared (S) locks for read operations
 * - Exclusive (X) locks for write operations
 * - Timeout-based deadlock detection
 * <p>
 * Thread-safe: All operations are synchronized using a global lock.
 */
public class LockTable {

    private static final Logger logger = LogManager.getLogger(LockTable.class);

    /**
     * Encapsulates the lock state for a single block.
     *
     * A block can have:
     * - Multiple S-locks (shared by multiple transactions)
     * - One X-lock (exclusive to one transaction)
     */
    private static class LockInfo {
        // Set of transaction IDs holding S locks on this block
        private final Set<Integer> sLocks = new HashSet<>();
        // Transaction ID holding X lock on this block (-1 if none)
        private int xLock = -1;

        /**
         * Check if an X lock is currently held.
         */
        boolean hasXLock() {
            return xLock != -1;
        }

        /**
         * Check if the given transaction holds an S lock.
         */
        boolean hasSLock(int txnum) {
            return sLocks.contains(txnum);
        }

        /**
         * Check if the given transaction holds any lock (S or X).
         */
        boolean hasAnyLock(int txnum) {
            return hasSLock(txnum) || xLock == txnum;
        }

        /**
         * Check if the block has no locks at all.
         */
        boolean isEmpty() {
            return sLocks.isEmpty() && xLock == -1;
        }

        @Override
        public String toString() {
            return "LockInfo{sLocks=" + sLocks + ", xLock=" + xLock + "}";
        }
    }

    // Map from block to its lock information
    private final Map<BlockIdBase, LockInfo> locks;
    // Global lock for synchronizing access to the lock table
    private final Lock lock;
    // Condition variable for waiting when locks cannot be acquired
    private final Condition lockReleased;

    /**
     * Creates a new lock table.
     */
    LockTable() {
        this.locks = new HashMap<>();
        this.lock = new ReentrantLock(true);
        this.lockReleased = lock.newCondition();
    }

    /**
     * Acquire a shared lock on the block.
     *
     * An S-lock can be acquired if:
     * - No transaction holds an X-lock on the block, OR
     * - This transaction already holds any lock on the block
     *
     * @param blk the block to lock
     * @param txnum the transaction requesting the lock
     * @param maxWaitTime maximum time to wait in milliseconds
     * @throws LockAbortException if timeout occurs
     */
    public void sLock(BlockIdBase blk, int txnum, long maxWaitTime) {
        if (blk == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }
        if (maxWaitTime <= 0) {
            throw new IllegalArgumentException("maxWaitTime must be positive");
        }
        lock.lock();
        try {
            LockInfo lockInfo = locks.computeIfAbsent(blk, k -> new LockInfo());
            // If we already have any lock on this block, return
            if (lockInfo.hasAnyLock(txnum)) {
                return;
            }
            long startTime = System.currentTimeMillis();
            long remainingTime = maxWaitTime;
            // Wait until we can acquire the S lock
            // Can acquire S lock if: no X lock exists OR the X lock is held by us
            while (lockInfo.hasXLock() && lockInfo.xLock != txnum) {
                if (remainingTime <= 0) {
                    logger.warn("Tx {} timeout waiting for S-lock on block {} (held by Tx {})",
                            txnum, blk, lockInfo.xLock);
                    throw new LockAbortException(
                            "Timeout: Transaction " + txnum +
                                    " could not acquire shared lock on block " + blk +
                                    " (X-lock held by transaction " + lockInfo.xLock + ")");
                }
                logger.debug("Tx {} waiting for S-lock on block {} (X-lock held by Tx {})",
                        txnum, blk, lockInfo.xLock);
                try {
                    // Wait for a lock to be released
                    boolean signaled = lockReleased.await(remainingTime, TimeUnit.MILLISECONDS);
                    if (!signaled) {
                        logger.debug("Tx {} wait timed out", txnum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Tx {} interrupted while waiting for S-lock", txnum, e);
                    throw new LockAbortException(
                            "Transaction " + txnum + " interrupted while waiting for lock", e);
                }
                // Update remaining time
                long elapsed = System.currentTimeMillis() - startTime;
                remainingTime = maxWaitTime - elapsed;
            }
            // Acquire the S lock
            lockInfo.sLocks.add(txnum);
            logger.debug("Tx {} acquired S-lock on block {}", txnum, blk);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquire an exclusive lock on the block.
     *
     * An X-lock can be acquired if:
     * - No transaction holds an X-lock on the block, AND
     * - No other transaction holds an S-lock on the block
     *
     * If this transaction holds an S-lock, it will be upgraded to an X-lock.
     *
     * @param blk the block to lock
     * @param txnum the transaction requesting the lock
     * @param maxWaitTime maximum time to wait in milliseconds
     * @throws LockAbortException if timeout occurs
     */
    public void xLock(BlockIdBase blk, int txnum, long maxWaitTime) {
        if (blk == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }
        if (maxWaitTime <= 0) {
            throw new IllegalArgumentException("maxWaitTime must be positive");
        }
        lock.lock();
        try {
            LockInfo lockInfo = locks.computeIfAbsent(blk, k -> new LockInfo());
            // If we already have an X lock, return
            if (lockInfo.xLock == txnum) {
                return;
            }
            long startTime = System.currentTimeMillis();
            long remainingTime = maxWaitTime;
            // Wait until we can acquire the X lock
            // Can only acquire X lock if:
            // 1. No X lock is held by another transaction
            // 2. No S locks are held by other transactions (only by us, if at all)
            while (lockInfo.hasXLock() || hasOtherSLocks(lockInfo, txnum)) {
                if (remainingTime <= 0) {
                    logger.warn("Tx {} timeout waiting for X-lock on block {} (state: {})",
                            txnum, blk, lockInfo);
                    throw new LockAbortException(
                            "Timeout: Transaction " + txnum +
                                    " could not acquire exclusive lock on block " + blk +
                                    " (current state: " + lockInfo + ")");
                }
                logger.debug("Tx {} waiting for X-lock on block {} (state: {})",
                        txnum, blk, lockInfo);
                try {
                    // Wait for a lock to be released
                    boolean signaled = lockReleased.await(remainingTime, TimeUnit.MILLISECONDS);
                    if (!signaled) {
                        logger.debug("Tx {} wait timed out", txnum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Tx {} interrupted while waiting for X-lock", txnum, e);
                    throw new LockAbortException(
                            "Transaction " + txnum + " interrupted while waiting for lock", e);
                }
                // Update remaining time
                long elapsed = System.currentTimeMillis() - startTime;
                remainingTime = maxWaitTime - elapsed;
            }
            // Upgrade from S lock to X lock if we have an S lock
            if (lockInfo.hasSLock(txnum)) {
                lockInfo.sLocks.remove(txnum);
            }
            // Acquire the X lock
            lockInfo.xLock = txnum;
            logger.debug("Tx {} acquired X-lock on block {}", txnum, blk);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Helper method to check if any transaction other than txnum holds an S lock.
     */
    private boolean hasOtherSLocks(LockInfo lockInfo, int txnum) {
        if (lockInfo.sLocks.isEmpty()) {
            return false;
        }
        if (lockInfo.sLocks.size() == 1 && lockInfo.hasSLock(txnum)) {
            return false;
        }
        return true;
    }

    /**
     * Release all locks held by the transaction.
     *
     * This method is called when a transaction commits or rolls back.
     * It implements the "shrinking phase" of 2PL by releasing all locks at once.
     *
     * @param txnum the transaction releasing its locks
     */
    public void unlock(int txnum) {
        lock.lock();
        try {
            int sLocksReleased = 0;
            int xLocksReleased = 0;
            logger.debug("Tx {} attempting to release all locks", txnum);
            // Remove all locks held by this transaction
            for (Map.Entry<BlockIdBase, LockInfo> entry : locks.entrySet()) {
                LockInfo lockInfo = entry.getValue();
                BlockIdBase blk = entry.getKey();
                // Remove S lock if held
                if (lockInfo.hasSLock(txnum)) {
                    lockInfo.sLocks.remove(txnum);
                    sLocksReleased++;
                    logger.debug("Tx {} released S-lock on block {}", txnum, blk);
                }
                // Remove X lock if held
                if (lockInfo.xLock == txnum) {
                    lockInfo.xLock = -1;
                    xLocksReleased++;
                    logger.debug("Tx {} released X-lock on block {}", txnum, blk);
                }
            }
            // Wake up all waiting threads since locks were released
            if (sLocksReleased > 0 || xLocksReleased > 0) {
                lockReleased.signalAll();
                logger.debug("Tx {} released {} S-locks and {} X-locks; notified all waiting transactions",
                        txnum, sLocksReleased, xLocksReleased);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reset all lock state (for testing purposes).
     *
     * WARNING: This forcibly releases all locks held by all transactions.
     * Should only be used for testing or system reset scenarios.
     */
    void reset() {
        lock.lock();
        try {
            int totalLocks = locks.size();
            locks.clear();
            lockReleased.signalAll();
            logger.warn("LockTable reset - cleared {} block entries", totalLocks);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current number of blocks with locks (for testing/debugging).
     */
    int getLockedBlockCount() {
        lock.lock();
        try {
            return locks.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if a transaction holds any lock on a block (for testing/debugging).
     */
    boolean hasLock(BlockIdBase blk, int txnum) {
        lock.lock();
        try {
            LockInfo lockInfo = locks.get(blk);
            return lockInfo != null && lockInfo.hasAnyLock(txnum);
        } finally {
            lock.unlock();
        }
    }
}