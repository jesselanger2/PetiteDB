package edu.yu.dbimpl.buffer;

import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgrBase;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BufferMgr extends BufferMgrBase {

    private final Buffer[] bufferPool;
    private final int numBuffers;
    private int numAvailable;
    private final long maxWaitTime;
    private final EvictionPolicy policy;
    // A map for lookups of blocks that are already in a buffer
    private final Map<BlockIdBase, Buffer> blockMap;
    // Concurrency control
    private final Lock lock;
    private final Condition bufferAvailable;
    // For CLOCK eviction policy
    private int clockHand = 0;

    public BufferMgr(FileMgrBase fileMgr, LogMgrBase logMgr, int nBuffers, int maxWaitTime) {
        this(fileMgr, logMgr, nBuffers, maxWaitTime, EvictionPolicy.NAIVE);
    }

    public BufferMgr(FileMgrBase fileMgr, LogMgrBase logMgr, int nBuffers, int maxWaitTime, EvictionPolicy policy) {
        super(fileMgr, logMgr, nBuffers, maxWaitTime, policy);
        if (nBuffers <= 0) {
            throw new IllegalArgumentException("Number of buffers must be greater than 0");
        }
        if (maxWaitTime <= 0) {
            throw new IllegalArgumentException("Max wait time must be greater than 0");
        }
        this.numBuffers = nBuffers;
        this.numAvailable = nBuffers;
        this.maxWaitTime = maxWaitTime;
        this.policy = policy;
        this.bufferPool = new Buffer[nBuffers];
        this.blockMap = new HashMap<>();
        // Initialize concurrency controls
        this.lock = new ReentrantLock();
        this.bufferAvailable = lock.newCondition();
        // Populate the pool with new Buffer objects
        for (int i = 0; i < nBuffers; i++) {
            bufferPool[i] = new Buffer(fileMgr, logMgr);
        }
    }

    @Override
    public int available() {
        lock.lock();
        try {
            return numAvailable;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flushAll(int txnum) {
        if (txnum < 0) {
            throw new IllegalArgumentException("Transaction number must be non-negative");
        }
        lock.lock();
        try {
            for (Buffer buff : bufferPool) {
                if (buff.getModifyingTx() == txnum) {
                    buff.flush();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unpin(BufferBase buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        lock.lock();
        try {
            Buffer buff = (Buffer) buffer;
            if (!buff.isPinned()) {
                throw new IllegalArgumentException("Buffer is not currently pinned");
            }
            buff.decrementPinCount();
            if (!buff.isPinned()) {
                // This buffer just became available
                numAvailable++;
                // Wake up any waiting threads
                bufferAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BufferBase pin(BlockIdBase blk) {
        if (blk == null) {
            throw new IllegalArgumentException("BlockId cannot be null");
        }
        lock.lock();
        Buffer buff;
        try {
            // Scenario 1 & 2: Block is already in a buffer
            buff = findExistingBuffer(blk);
            if (buff != null) {
                // Scenario 2: Block is unpinned in buffer
                if (!buff.isPinned()) {
                    numAvailable--;
                }
                buff.incrementPinCount();
                return buff;
            }
            // Scenario 3 & 4: Block is not in a buffer
            buff = findUnpinnedBuffer();
            if (buff == null) {
                // Scenario 4: All buffers are pinned, must wait
                long waitEndTime = System.currentTimeMillis() + maxWaitTime;
                while (buff == null) {
                    try {
                        long remainingWait = waitEndTime - System.currentTimeMillis();
                        if (remainingWait <= 0) {
                            throw new BufferAbortException("Timeout: No buffer became available");
                        }
                        // Atomically releases the lock and waits
                        bufferAvailable.await(remainingWait, TimeUnit.MILLISECONDS);
                        // Woke up, re-check for an unpinned buffer
                        buff = findUnpinnedBuffer();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BufferAbortException("Thread interrupted while waiting for a buffer");
                    }
                }
            }
            // At this point, 'buff' is a chosen unpinned buffer
            if (buff.block() != null) {
                // If the buffer was assigned to another block, remove that mapping
                blockMap.remove(buff.block());
            }

            blockMap.put(blk, buff);    // Add the new block's mapping
            buff.incrementPinCount();   // Increment pin count for the new block
            numAvailable--;             // Decrease available buffer count

        } finally {
            lock.unlock();
        }

        buff.flush();   // Flush the buffer to disk if it was dirty before assigning the new block
        buff.assignToBlock(blk);    // Assign the new block to this buffer
        return buff;
    }

    /**
     * Helper to quickly find if a block is already in the pool.
     * @param blk The block to find.
     * @return The Buffer holding the block, or null if not found.
     */
    private Buffer findExistingBuffer(BlockIdBase blk) {
        return blockMap.get(blk);
    }

    /**
     * Helper to find an unpinned buffer using the configured eviction policy.
     * Returns null if all buffers are pinned.
     */
    private Buffer findUnpinnedBuffer() {
        if (numAvailable == 0) {
            return null;
        }
        if (policy == EvictionPolicy.NAIVE) {
            for (Buffer buff : bufferPool) {
                if (!buff.isPinned()) {
                    return buff;
                }
            }
        } else if (policy == EvictionPolicy.CLOCK) {
            int scanned = 0;
            int maxScans = numBuffers * 2;
            while (scanned < maxScans) {
                Buffer buff = bufferPool[clockHand];
                if (!buff.isPinned()) {
                    if (!buff.getReferenceBit()) {
                        int toEvict = clockHand;
                        clockHand = (clockHand + 1) % numBuffers;
                        return bufferPool[toEvict];
                    } else {
                        buff.clearReferenceBit();
                    }
                }
                clockHand = (clockHand + 1) % numBuffers;
                scanned++;
            }
        }
        return null;
    }

    @Override
    public EvictionPolicy getEvictionPolicy() {
        return this.policy;
    }
}
