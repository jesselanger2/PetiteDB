package edu.yu.dbimpl.tx;

import edu.yu.dbimpl.buffer.BufferMgrBase;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.log.LogMgrBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

public class TxMgr extends TxMgrBase {

    private final FileMgrBase fm;
    private final LogMgrBase lm;
    private final BufferMgrBase bm;
    private final long maxWaitTimeInMillis;
    private final AtomicInteger nextTxNum;

    private static final Logger logger = LogManager.getLogger(TxMgr.class);
    // Global lock table shared by all transactions
    private static final LockTable lockTable = new LockTable();


    public TxMgr(FileMgrBase fm, LogMgrBase lm, BufferMgrBase bm, long maxWaitTimeInMillis) {
        super(fm, lm, bm, maxWaitTimeInMillis);
        this.fm = fm;
        this.lm = lm;
        this.bm = bm;
        this.maxWaitTimeInMillis = maxWaitTimeInMillis;
        this.nextTxNum = new AtomicInteger(0);

        logger.info("Database is starting up - performing recovery");
        performRecovery();
    }

     // Performs recovery by creating a special recovery transaction
     // that will undo all uncommitted transactions.
    private void performRecovery() {
        try {
            // Create a special recovery transaction
            int recoveryTxNum = nextTxNum.getAndIncrement();
            TxBase recoveryTx = new Tx(recoveryTxNum, fm, lm, bm, this);
            // Perform the recovery operation
            recoveryTx.recover();
            logger.info("Recovery completed successfully");
        } catch (Exception e) {
            logger.error("Recovery failed", e);
            throw new RuntimeException("Failed to perform recovery on startup", e);
        }
    }

    @Override
    public long getMaxWaitTimeInMillis() {
        return maxWaitTimeInMillis;
    }

    @Override
    public TxBase newTx() {
        int txnum = nextTxNum.getAndIncrement();
        logger.debug("Creating new transaction with txnum={}", txnum);
        return new Tx(txnum, fm, lm, bm, this);
    }

    @Override
    public void resetAllLockState() {
        logger.warn("Resetting all lock state");
        lockTable.reset();
    }

    // Provide access to the global lock table
    public static LockTable getLockTable() {
        return lockTable;
    }
}
