# PetiteDB

PetiteDB is a small Java storage-engine project that implements the core layers of a relational database system. It is organized as a set of manager modules that build on each other: file storage, logging, buffering, records, transactions, metadata, and static hash indexes.

The project is written for Java 17 and built with Maven.

## What It Includes

- **File module**: block-oriented random access over database files.
- **Log module**: append-only log records with reverse iteration for recovery.
- **Buffer module**: in-memory buffer pool with pin/unpin semantics, dirty-page flushing, and configurable eviction policy support.
- **Record module**: schemas, layouts, record pages, record identifiers, and table scans.
- **Transaction module**: transaction lifecycle, locking, rollback, commit, and startup recovery.
- **Metadata module**: table catalog management for persisted table and field metadata.
- **Index module**: index metadata and static hash index support.
- **Demo programs and tests**: sample usage for the major modules plus JUnit coverage under `src/test/java`.

## Project Layout

```text
src/main/java/edu/yu/dbimpl/
  buffer/       Buffer pool and buffer frame management
  config/       Global PetiteDB startup configuration
  file/         Block, page, and file-manager primitives
  index/        Index descriptors, manager, and static hash index
  log/          Log manager and log iterator
  metadata/     Table catalog manager
  query/        Scan and datum abstractions
  record/       Schema, layout, record page, RID, and table scan
  tx/           Transactions, concurrency, locking, and recovery

src/test/java/edu/yu/dbimpl/
  demo/         Sample module demos with `main` methods
  */            Unit and integration-style module tests
```

Most modules define an abstract `*Base` API and a concrete implementation class. The base classes document the required behavior and constructor signatures; the implementation classes provide the working PetiteDB behavior.

## Requirements

- Java 17
- Maven 3.x

Check your local versions:

```bash
java -version
mvn -version
```

## Build and Test

Compile the project:

```bash
mvn compile
```

Run the test suite:

```bash
mvn test
```

Package the project:

```bash
mvn package
```

## Startup Configuration

PetiteDB uses the `DBConfiguration` singleton for global startup settings. Clients must configure it before constructing any manager classes.

```java
Properties props = new Properties();
props.setProperty(DBConfiguration.DB_STARTUP, "true");
DBConfiguration.INSTANCE.get().setConfiguration(props);
```

Important properties:

- `db.startup`: required. Use `true` to initialize a fresh database directory, or `false` to reuse existing persisted state.
- `n.static.hash.buckets`: optional. Configures the number of buckets used by the static hash index implementation. Defaults to `100`.

## Typical Manager Setup

The modules are intended to be initialized from the bottom of the storage stack upward:

```java
Properties props = new Properties();
props.setProperty(DBConfiguration.DB_STARTUP, "true");
DBConfiguration.INSTANCE.get().setConfiguration(props);

File dbDirectory = new File("petitedb-data");
int blockSize = 400;
int bufferSize = 10;
int maxBufferWaitMs = 500;
int maxTxWaitMs = 500;

FileMgrBase fileMgr = new FileMgr(dbDirectory, blockSize);
LogMgrBase logMgr = new LogMgr(fileMgr, "petitedb.log");
BufferMgrBase bufferMgr = new BufferMgr(fileMgr, logMgr, bufferSize, maxBufferWaitMs);
TxMgrBase txMgr = new TxMgr(fileMgr, logMgr, bufferMgr, maxTxWaitMs);

TxBase tx = txMgr.newTx();
```

From there, transactions can pin blocks, read and write typed values, use table scans, create table metadata, and interact with indexes.

## Demo Programs

Sample usage lives in `src/test/java/edu/yu/dbimpl/demo`:

- `SampleFileModuleDemo`
- `SampleLogModuleDemo`
- `SampleBufferModuleDemo`
- `SampleTxModuleDemo`
- `SampleRecordModuleDemo`

These classes contain `main` methods and are useful for stepping through module behavior in an IDE.

## Testing Notes

Tests are organized by module:

- `file`: page and file-manager behavior
- `log`: log manager behavior
- `buffer`: buffer and buffer-manager behavior
- `record`: schema, layout, record page, and table scan behavior
- `tx`: transaction lifecycle, concurrency, and recovery behavior
- `metadata`: table catalog behavior
- `index`: index descriptor, manager, and static hash index behavior

Some tests and demos create local database directories or log files as part of exercising persistence behavior.

## Dependencies

Runtime:

- Log4j 2.20.0

Test:

- JUnit 4.13.2
- JUnit Jupiter 5.8.1

## Notes for Contributors

- Keep constructor signatures compatible with the corresponding `*Base` classes.
- Configure `DBConfiguration` before creating manager instances.
- Preserve transaction and recovery semantics when changing buffer, log, or file behavior.
- Prefer focused module tests when changing a single layer, and broader integration tests when changes cross module boundaries.
