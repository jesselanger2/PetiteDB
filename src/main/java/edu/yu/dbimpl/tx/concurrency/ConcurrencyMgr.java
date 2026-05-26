package edu.yu.dbimpl.tx.concurrency;

import edu.yu.dbimpl.file.BlockIdBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;

public class ConcurrencyMgr extends ConcurrencyMgrBase{

    private final int txnum;
    private final TxMgrBase txMgr;

    public ConcurrencyMgr(TxMgrBase txMgr, int txnum) {
        super(txMgr, txnum);
        this.txMgr = txMgr;
        this.txnum = txnum;
    }

    @Override
    public void sLock(BlockIdBase blk) {
        TxMgr.getLockTable().sLock(blk, txnum, txMgr.getMaxWaitTimeInMillis());
    }

    @Override
    public void xLock(BlockIdBase blk) {
        TxMgr.getLockTable().xLock(blk, txnum, txMgr.getMaxWaitTimeInMillis());
    }

    @Override
    public void release() {
        TxMgr.getLockTable().unlock(txnum);
    }
}
