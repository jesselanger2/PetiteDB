package edu.yu.dbimpl.demo;

/** Illustrates SOME happy-path usage of the Tx Module APIs.
 *
 * @author Avraham Leff
 */

import java.io.File;
import java.util.Properties;

import edu.yu.dbimpl.buffer.*;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.*;
import edu.yu.dbimpl.log.*;
import edu.yu.dbimpl.tx.TxBase;
import edu.yu.dbimpl.tx.TxMgr;
import edu.yu.dbimpl.tx.TxMgrBase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class SampleTxModuleDemo {

  public static void main(final String[] args) {
    try {
      logger.info("Entered main");

      final Properties dbProperties = new Properties();
      dbProperties.setProperty(DBConfiguration.DB_STARTUP, String.valueOf(true));
      logger.info("Setting DBConfiguration properties with: {}", dbProperties);
      DBConfiguration.INSTANCE.get().setConfiguration(dbProperties);

      final String dirName = "SampleTxModuleDemo";
      final String logFile = "temp_logfile";
      final String dbFile = "testfile";
      final File dbDirectory = new File(dirName);
      final int blockSize = 400;
      final int bufferSize = 10;
      final int maxWaitTime = 500; // ms
      final int maxTxWaitTime = 500; // ms
      final int integerPos = 80;
      final int stringPos = 200;
      final int fortyTwo = 42;
      final String notFortyTwo = "notFortyTwo";
      final BlockId block1 = new BlockId(dbFile, 1);
      final boolean okToLock = true;

      
      final FileMgrBase fileMgr = new FileMgr(dbDirectory, blockSize);
      final LogMgrBase logMgr = new LogMgr(fileMgr, logFile);
      final BufferMgrBase bufferMgr =
        new BufferMgr(fileMgr, logMgr, bufferSize, maxWaitTime);
      logger.info("Created BufferMgr with a buffer size of {} and maxWaitTime "+
                  "of {}", bufferSize, maxWaitTime); 
      final TxMgrBase txMgr = new TxMgr(fileMgr, logMgr, bufferMgr, maxTxWaitTime);

      final TxBase tx1 = txMgr.newTx();
      tx1.pin(block1);

      // per lecture discussion, no need to log if block has not yet been
      // modified
      tx1.setInt(block1, integerPos, fortyTwo, !okToLock);
      tx1.setString(block1, stringPos, notFortyTwo, !okToLock);
      printActiveState(tx1);
      tx1.commit();
      printState(tx1);
      logger.info("Committed {}", tx1);

      final TxBase tx2 = txMgr.newTx();
      tx2.pin(block1);
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, integerPos, tx2.getInt(block1, integerPos));
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, stringPos, tx2.getString(block1, stringPos));

      tx2.setInt(block1, integerPos, fortyTwo + 1, okToLock);
      tx2.setString(block1, stringPos, String.valueOf(fortyTwo), okToLock);
      printActiveState(tx2);
      tx2.commit();
      logger.info("Committed {}", tx2);      
      printState(tx2);

      final TxBase tx3 = txMgr.newTx();
      tx3.pin(block1);
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, integerPos, tx3.getInt(block1, integerPos));
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, stringPos, tx3.getString(block1, stringPos));
      tx3.setInt(block1, integerPos, fortyTwo - 10, okToLock);
      tx3.setString(block1, stringPos, String.valueOf(fortyTwo -10), okToLock);
      printActiveState(tx3);
      tx3.commit();
      logger.info("Committed {}", tx3);      
      printState(tx3);
      
      final TxBase tx4 = txMgr.newTx();
      tx4.pin(block1);
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, integerPos, tx4.getInt(block1, integerPos));
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, stringPos, tx4.getString(block1, stringPos));
      tx4.setInt(block1, integerPos, Integer.MAX_VALUE, okToLock);
      tx4.setString(block1, stringPos, String.valueOf(Integer.MAX_VALUE), okToLock);
      printActiveState(tx4);
      tx4.rollback();
      logger.info("Rolled back {}", tx4);            
      printState(tx4);
      
      final TxBase tx5 = txMgr.newTx();
      tx5.pin(block1);
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, integerPos, tx5.getInt(block1, integerPos));
      logger.info("Someone set blk {}:offset {} to {}",
                  block1, stringPos, tx5.getString(block1, stringPos));
      printActiveState(tx5);
      tx5.rollback();
      logger.info("Rolled back {}", tx5);
      printState(tx5);

    }
    catch (Exception e) {
      logger.error("Problem: ", e);
      throw new RuntimeException(e);
    }
    finally {
      logger.info("Exiting main");
    }
  }

  private static void printActiveState(final TxBase tx) {
    logger.info("Tx {} reports that block size is {} and that there are {} available buffers",
                tx.txnum(), tx.blockSize(), tx.availableBuffs());
  }

  private static void printState(final TxBase tx) {
    logger.info("Status of tx {} is {}", tx.txnum(), tx.getStatus());
  }

  private final static Logger logger =
    LogManager.getLogger(SampleTxModuleDemo.class);



}
