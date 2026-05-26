package edu.yu.dbimpl.demo;

/** Illustrates SOME happy-path usage of the Log Module APIs.
 *
 * @author Avraham Leff
 */

import java.io.File;
import java.util.*;
import edu.yu.dbimpl.config.DBConfiguration;
import edu.yu.dbimpl.file.FileMgrBase;
import edu.yu.dbimpl.file.FileMgr;
import edu.yu.dbimpl.file.PageBase;
import edu.yu.dbimpl.file.Page;
import edu.yu.dbimpl.log.LogMgrBase;
import edu.yu.dbimpl.log.LogMgr;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SampleLogModuleDemo {

  public static void main(final String[] args) {
    try {
      logger.info("Entered main");

      final Properties dbProperties = new Properties();
      dbProperties.setProperty(DBConfiguration.DB_STARTUP, String.valueOf(true));
      logger.info("Setting DBConfiguration properties with: {}", dbProperties);
      DBConfiguration.INSTANCE.get().setConfiguration(dbProperties);

      final String dirName = "SampleLogModuleDemo";
      final File dbDirectory = new File(dirName);
      final int blockSize = 400;
      logger.info("Test will append 2 log record and retrieve via LogIterator");

      final FileMgrBase fileMgr = new FileMgr(dbDirectory, blockSize);
      final String logFile = "temp_logfile";
      final LogMgrBase logMgr = new LogMgr(fileMgr, logFile);
      final String s = "Length7";
      final int x = 42;
      logger.info("Creating byte array containing String {} and int {}",
                  s, x);
      logger.info("'maxLength' of this string is {}",
                  PageBase.maxLength(s.length()));

      final int spos = 0;
      final int npos = spos + PageBase.maxLength(s.length());
      final byte[] byteArray = new byte[npos + Integer.BYTES];
      logger.info("Array length before being passed to Page ctor: {}",
                  byteArray.length);
      final PageBase p1 = new Page(byteArray);
      logger.info("Storing string at position {}", spos);
      logger.info("Storing int at position {}", npos);
      p1.setString(spos, s);
      p1.setInt(npos, x);
      logger.info("Byte array length after being passed to Page ctor: {}",
                  byteArray.length);
      final int lsn = logMgr.append(byteArray);
      logger.info("Appended to LogMgr, received LSN of {}", lsn); 

      final String s2 = "len4";
      final int x2 = 99;
      logger.info("Creating byte array containing String {} and int {}",
                  s2, x2);
      logger.info("'maxLength' of this string is {}",
                  PageBase.maxLength(s2.length()));

      final int spos2 = 0;
      int npos2 = spos2 + PageBase.maxLength(s2.length());
      final byte[] byteArray2 =
        new byte[Integer.BYTES + PageBase.maxLength(s2.length())];
      logger.info("Array length before being passed to Page ctor: {}",
                  byteArray2.length);
      final PageBase p2 = new Page(byteArray);
      logger.info("Storing string at position {}", spos2);
      logger.info("Storing int at position {}", npos2);
      p2.setString(spos2, s2);
      p2.setInt(npos2, x2);
      logger.info("Byte array length after being passed to Page ctor: {}",
                  byteArray2.length);
      final int lsn2 = logMgr.append(byteArray);
      logger.info("Appended to LogMgr, received LSN of {}", lsn2); 


      final List<byte[]> logRecords1 = new ArrayList<byte[]>();
      logMgr.iterator().forEachRemaining(logRecords1::add);
      logger.info("# of log records: {}", logRecords1.size());

      logger.info("Creating LogMgr.iterator() and invoking next()");
      Iterator<byte[]> iter = logMgr.iterator();
      while (iter.hasNext()) {
        byte[] rec = iter.next();
        logger.info("LogIterator returned byte array of length {}", rec.length);
        logger.info("Log record size is {}", rec.length);
        final PageBase p = new Page(rec);
        logger.info("Accessing string at position 0");
        final String str = p.getString(0);
        final int npos3 = PageBase.maxLength(str.length());
        logger.info("Accessing int at position {}", npos3);
        final int val = p.getInt(npos3);
        logger.info("Deserialized log record into: [" + str + ", " + val + "]");
      } // iterating over log records
    }   // try
    catch (Exception e) {
      logger.error("Problem: ", e);
      throw new RuntimeException(e);
    }
    finally {
      logger.info("Exiting main");
    }
  } // main

    private final static Logger logger =
      LogManager.getLogger(SampleLogModuleDemo.class);
  }
