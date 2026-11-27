# Changelog

## 3.2.0

- `gcs` has opt-in live tests against a real bucket (`-Drawdata.gcs.live=true`, never in CI): the contract, and a
  token fetched again on expiry and on a 401. Both pass against Google.
- Two guarantees in every provider, checked by the TCK (`aReturnedPublishSurvivesACrashAndLastMessageHoldsItOnceAProducerExists`,
  with the new hook `afterCrash`):
  - **A `publish` that returned survives a crash of the process.** postgres already committed each call. `filesystem`,
    `gcs` and `s3` now end every publish call's Avro block and write it to the buffer file before `publish` returns;
    before, a crash lost the calls in Avro's in-memory block (up to `avro-file.sync.interval`, 512 KiB). The file is
    forced to disk every `avro-file.sync.interval` bytes, as before, so a crash of the machine loses the calls written
    since: about that much, more after a larger call. `local-temp-folder` must outlive the process. `memory` keeps nothing past its process, as before.
  - **Once a client has a producer of a topic, `lastMessage` holds everything the topic will hold**, crash or not.
    `filesystem`, `gcs` and `s3` now start the topic's writer in `producer(topic)`: it takes the buffer folder's lock
    and uploads what a crash left before `producer` returns. Before, that happened at the first publish, so a writer
    that read `lastMessage` to resume after a crash re-published what recovery then uploaded too.
- `producer(topic)` on `filesystem`, `gcs` and `s3` can now throw: `RawdataException` when the topic's buffer folder is
  in use by another client or the store fails, and `RawdataWriterConflictException` when a window a crash left no
  longer follows the topic's end. These came from the first `publish` before.
- Cost in `filesystem`, `gcs` and `s3`: about 20 bytes of Avro block framing per call in the segment, and no disk
  sync per call. The number of segments, requests and when consumers see a message are unchanged.
- A thread whose interrupt flag is set calls the client like any other thread, in every provider, and the flag is
  kept; the TCK checks it (`anInterruptedThreadReadsAndWritesAndKeepsTheFlag`). postgres already did. `filesystem`,
  `gcs` and `s3` failed `producer`, `lastMessage`, a consumer's seek and metadata with `RawdataException`, as a file
  channel, the GCS HTTP client and the AWS SDK fail at once on such a thread; `flush` and `close` failed and left the
  windows on disk, and `publish` skipped the wait for room among the pending uploads. The engine now clears the flag
  for each store call and wait and sets it again after. An interrupt during a call stops it as before, and `receive`
  still throws `InterruptedException`.
- `filesystem`, `gcs` and `s3`: any failure while a call is written, an upload runs or a window is sealed fails the
  topic's writer, an `Error` such as out of memory too. Before, an `Error` in an upload left `flush` and `close`
  waiting for ever (and publishes, once the pending uploads were full), and an unchecked exception while a call was
  written left the writer running, so the next call's block also wrote part of the failed one. `close()` now always
  stops the client's threads and closes the store, whatever failed before; the first failure is thrown and the others
  are suppressed in it.
- `filesystem`, `gcs` and `s3`: recovery keeps a window it rewrites (an open window cut back to its complete
  blocks) as the sealed window before it uploads it. Before, a crash between that upload and the file's removal made
  the next start set the window aside and fail with `RawdataWriterConflictException`, though no other process wrote
  the topic.
- `filesystem`, `gcs` and `s3` refuse `avro-file.max.bytes` above 1 GiB (`AvroUploadWindow.MAX_BYTES_LIMIT`) with
  `IllegalArgumentException` when the client is created. An upload reads the whole window into one array; before, a
  window of 2 GiB or more failed every upload with `OutOfMemoryError`.
- `receiveUpTo`: a read that fails after it took its first messages returns them, and the next receive reads again
  (and fails again if the store still fails), as after a close or an interrupt. Before, the messages taken were lost.
- `RawdataMessage.hashCode` no longer depends on the order of the content keys, as `equals` does not.
- `receiveAsync`, in every provider: a future that is cancelled, or times out (`orTimeout`), takes no message, and
  the TCK checks it (`aCancelledOrTimedOutAsynchronousReceiveLosesNoMessage`). Before, the receive behind it went on,
  took the next message and dropped it, and held a thread of the client until a message came (as in 2.x). The future
  is now completed while the consumer's turn is held, so a message taken for a future that is done meanwhile goes back
  to the head of the buffer; a dependent that is not async runs then too, so it must not wait for another receive of
  the same consumer. Closing a consumer fails its outstanding `receiveAsync`, also one whose task had not started
  when the client stopped its threads.
- `cursorOf` by position refuses a negative tolerance with `IllegalArgumentException` (before: not found), and a
  tolerance or `receiveUpTo` timeout as long as a `Duration` can be no longer overflows. The range is computed once,
  by `IdRange` in the spi package, for every provider; the TCK checks both.
- `RawdataClosedException(String, Throwable)`: a read that fails as its consumer closes keeps the failure as the
  cause.
- Docs: asynchronous publishes are not ordered against each other, and each call uses a thread of the client until it
  completes; a resume that overlaps the target topic is safe (the module doc said it threw).
- **Limits are the same in every provider** (Ove, 2026-11-27), the smallest every store holds, so a topic copies
  between any two providers. The API checks them before anything is written:
  - a position or content key is at most 2000 bytes in UTF-8 (`RawdataMessage.MAX_POSITION_BYTES`,
    `MAX_CONTENT_KEY_BYTES`), refused with `IllegalArgumentException` when the message is published (in every
    producer, through `spi.RawdataLimits.requirePublishable`), so stored messages are read whatever their length.
    Before, postgres alone failed on about 2.7 KB, with a raw SQL error, and only for a value that did not compress;
  - a metadata key is at most 127 bytes (`RawdataMetadataClient.MAX_KEY_BYTES`, renamed from `PORTABLE_KEY_BYTES`),
    checked by `requireValidKey`. Before, postgres took 2000, gcs and s3 about 500 and memory any length.
  The TCK's `metadataKeyLimit` hook is removed: a provider declares no limits of its own. TCK tests
  `aPositionOrContentKeyOverTheLimitIsRefused` and `aMetadataKeyOverTheLimitIsRefused` check them in every provider
  with values that do not compress.
- postgres: a publish that waited for its turn while its producer closed writes nothing and throws
  `RawdataClosedException`; a publish under way when the client closes is reported as `RawdataClosedException` (it
  was a plain `RawdataException`) and writes nothing.
- postgres: an SQL failure names the operation, the topic and the SQLState (it was `PostgreSQL: <message>`).
- postgres: a publish wakes the same client's waiting consumers of the topic, as the Avro providers do after an
  upload; `rawdata.postgres.consumer.wake-on-publish` (default `true`) turns it off.
- postgres docs: given ids are kept in any order (the doc said a lower one fails); the provider tests wait on
  `pg_locks` and on the client's own connections instead of a sleep and a server-wide count.
- postgres, new optional settings, off by default (design review pg-3, pg-4):
  - `rawdata.postgres.publish.lock-timeout-ms`: a publish that waits longer for the topic lock fails (SQLState
    `55P03`) and writes nothing;
  - `postgres.driver.tcp-keep-alive`;
  - `postgres.driver.ssl-mode` and `postgres.driver.ssl-root-cert`, passed to pgjdbc's `sslmode` and `sslrootcert`.
- The POM names the organisation, Descoped (https://descoped.io).

## 3.1.0

- An id is unique within a topic, in every provider, and the TCK checks it. Publishing the same message again (the
  same id, position, ordering group, sequence number, and content keys and bytes in the same order) is a no-op, also
  within one call: a writer that recovers, such as a saga repeating its last step, or a copy run again, writes nothing
  twice. A different message under an id that is taken is refused with the new `RawdataDuplicateIdException` (a
  `RawdataException` with the topic and the id), and nothing of the call is written. Before, `memory` replaced the
  message, `postgres` refused even the same message through its primary key, and `filesystem`, `gcs` and `s3` stored
  both, so consumers read the id twice.
- `filesystem`, `gcs` and `s3` look up a given id at or below the topic's greatest id: in the windows not yet uploaded
  and in the segments that may hold it. Ids above it, and generated ids, are not looked up. A message given with its
  id is hashed (SHA-256 of its position, ordering fields and content) before the publish lock is taken; a message with
  a generated id is not hashed, as no one can repeat it before its segment is uploaded. The windows not yet uploaded
  keep their ids in memory until then.
- `io.descoped.rawdata.api.spi.RawdataDuplicates`: the rule for providers, `same` and `distinct`.
- The parent POM declares one repository, `https://maven.descoped.io/` (id `descoped-snapshots-public`), for
  SNAPSHOTs only. A build that uses rawdata also looks there for SNAPSHOT versions; releases come only from Maven
  Central.

## 3.0.1

- Given ids are kept as they are, in any order, as in 2.x. 3.0.0 refused a given id that was not greater than the
  topic's last with `IllegalArgumentException`; an application that makes its ids before it publishes, from several
  threads, can publish them as they come again. Generated ids still follow the topic's greatest id. `publish` no
  longer throws `IllegalArgumentException` for such an id: an application that relied on that refusal (to notice a
  copy run twice, for example) no longer gets it.
- A consumer of a topic that is still written gets no guarantee of arrival: it may miss a message whose id lands
  behind its position. A completed topic, read from the beginning, gives every message once. `lastMessage` is the last
  message such a reader gets.
- Duplicate ids are not checked, as in 2.x: `memory` replaces the message, `postgres` refuses it with
  `RawdataException` (its primary key), the Avro providers store both.
- `filesystem`, `gcs` and `s3`: a segment keeps the order messages were published in. Its header's `rawdata.first-id`
  is the segment's lowest id and `rawdata.last-id` the topic's greatest id up to and with it. A consumer started from
  a cursor finds the message with the cursor's id and reads everything after it in segment order, as 2.x did.
  `cursorOf(topic, position, …)` stops at the first id past its time range, as 2.x did, so it does not find a message
  published after a higher id.

## 3.0.0

A clean break from 2.x: the modules now live in one repository and are released together; ids, the reading API,
storage layouts and some configuration changed. 3.0 does not read data written by 2.x, and there is no migration
code. Java 17 or newer.

### Project

- One repository, `descoped/rawdata-client-project`, for every module, with their history; one version for all,
  managed by `rawdata-client-bom`.
- Every library, plugin and engine at its latest stable version: PostgreSQL 18, JUnit 6, Testcontainers 2,
  service-provider-api 3 (providers are named with `@ProviderName`).
- New `rawdata-client-tck`, the TCK: the provider contract as JUnit 6 tests (`RawdataClientContractTest`) and the
  contract of the Avro engine's stores (`AvroStoreContractTest`). Every provider passes it unchanged, so providers
  can be swapped; providers' own tests only check internals.
- New `io.descoped.rawdata.api.spi`: `OrderedRawdataProducer` and `PollingRawdataConsumer`, base classes for
  providers.
- One version of each library in every module's compile and runtime dependencies, checked by the build: slf4j 2,
  Jackson and commons-codec are declared at the newest version where first needed; slf4j-api is excluded from
  HikariCP, Avro and the AWS SDK, Jackson and commons-codec from Avro.
- Every module is a JPMS module. CI checks the providers on the module path and the class path.
- Documentation in `docs/`: for users, a tutorial, the concepts, one page per module, the contract, logging and the
  module path, and troubleshooting; for developers, architecture, building and testing, dependencies, writing a
  provider and releasing. The README covers what the client is, getting started, build, test and release;
  `CONTRIBUTING.md` covers contributing.

### API

- Ids are UUIDv7 (`java.util.UUID`) laid out as PostgreSQL 18's `uuidv7()`, instead of ULIDs; the ULID library is
  gone. `RawdataIds` generates, checks and orders them (`RawdataIds.ORDER`, unsigned); `beginningOf(timestamp)` moved
  there from `RawdataConsumer`, and `RawdataProducer.nextMonotonicUlid` is removed.
- Reactive streams are gone: `RawdataFlows`, `RawdataPublisher`, `RawdataSubscriber` and the reactive-streams
  dependency are removed. Consumers are pulled: `receive`, the new `receiveUpTo(max, timeout)` for batches, and
  `receiveAsync`. The new `RawdataCopy.copy` copies a topic in batches.
- A topic only grows at its end: an id the producer gives is greater than every id in the topic, and an id the caller
  gives must be, or the publish throws `IllegalArgumentException` and writes nothing.
- Only one process writes a topic. A publish that finds another process wrote since this client last did throws the
  new `RawdataWriterConflictException` and writes nothing, in every provider.
- NUL (U+0000) is refused in positions, ordering groups, content names and metadata keys, which every provider must
  store. A store that does not exist (a bucket, a database) fails with `RawdataException` instead of reading as empty.
- Topic names follow one rule in every provider (`RawdataTopics`): 1 to 100 of `A-Z a-z 0-9 _ . -`, starting with a
  letter or digit.
- `RawdataMessage` is a sealed interface built only by its builder; content is copied in and out.
- Closing is the same everywhere: after `close()` every method throws `RawdataClosedException`; closing wakes a
  waiting `receive` and fails pending asynchronous calls.

### `postgres`

- Three shared tables keyed by topic (`rawdata_message`, `rawdata_content`, `rawdata_metadata`) instead of tables
  per topic; the topic is only ever a bind parameter. Created when missing; a role without `CREATE` can use existing
  tables.
- A publish holds the topic's advisory lock while it reads the last id and writes, and fails with
  `RawdataWriterConflictException` if another process wrote the topic. Connections run at READ COMMITTED. A metadata
  key is at most 2000 bytes.
- PostgreSQL 18 only. Required configuration is `postgres.driver.user`, `.password` and `.database`; the other keys
  are unchanged and have defaults.
- Fixed: SQL built from topic names, tables dropped on a missing topic, a commit on `close()`, order flips within a
  millisecond, and the consumer's prefetch races.

### `filesystem` and `gcs`

- Split into `rawdata-client-provider-avro` (the engine, module `io.descoped.rawdata.provider.avro`),
  `rawdata-client-provider-filesystem` (module `io.descoped.rawdata.provider.filesystem`) and
  `rawdata-client-provider-gcs` (module `io.descoped.rawdata.provider.gcs`). Packages renamed from
  `io.descoped.rawdata.avro`.
- The upload window of 2.x stays, fixed. A producer buffers on a local disk and uploads one Avro file per window,
  which closes on `avro-file.max.seconds` (now also by a timer, without new publishes), on `avro-file.max.bytes`,
  or when a producer closes. Messages are visible and durable once their window is uploaded.
- Large segments are read in ranges, as 2.x read through a seekable channel. Each segment's header holds its first
  and last id, count and last-block offset, as 2.x's file name did. A seek reads only headers, `lastMessage` only the
  last block, and a consumer streams a segment 4 MiB at a time instead of downloading it whole.
- Each window is a segment, numbered without gaps and created only if its number is free (a hard link, or an upload
  with the `ifGenerationMatch=0` precondition). A number already taken means another writer
  (`RawdataWriterConflictException`).
- Durability:
  - each Avro block is forced to disk;
  - a closed window is sealed before upload;
  - a failed upload fails the writer loudly and keeps its windows on disk, with no silent close and no deadlock;
  - a restarted writer uploads the windows a crash left, a sealed one unchanged, an open one cut back to its complete
    blocks, but only right after the topic's end;
  - a leftover that no longer fits is kept as `*.invalid`;
  - a lock file keeps a buffer folder to one process;
  - reads are pinned to the object they opened (the GCS generation, the S3 ETag, the file on disk);
  - every upload carries its CRC32C, so GCS and S3 refuse bytes damaged on the way; a create that finds its segment
    taken compares the size and CRC32C before it downloads anything;
  - uploads across topics adapt to throttling (AIMD: start at 4, one more per round of uploads at the limit, half when
    an upload gets a 429 or 503 or times out) up to `avro-file.upload.max-concurrency` (default 16); each running
    upload holds its window in memory;
  - `avro-file.max.pending-uploads` (default 4) bounds the windows waiting for upload.
  - `avro-file.close-timeout-seconds` (default 30) bounds how long `close()` waits for the uploads; what is not
    uploaded by then stays on disk for the next writer.
- New layout: `<topic>/segments/<19 digits>.avro` and `<topic>/metadata/k<hex of the key>`. The Avro record is
  2.x's `RawdataMessage`; only the id's `fixed(16)` type is renamed from `ulid` to `uuid`. On a file system, topic
  folders keep topics that differ only by case apart.
- Configuration:
  - the 2.x keys stay: `local-temp-folder` (required), `avro-file.max.seconds`, `avro-file.max.bytes`,
    `avro-file.sync.interval`, `listing.min-interval-seconds`, `gcs.listing.min-interval-seconds` and
    `gcs.credential-provider` (`service-account`, the default, `compute-engine`, and now `application-default` and
    `none`);
  - they now have defaults (60 s, 64 MiB, 512 KiB, 1 s) and are read with dynamic-configuration's value rules;
  - new: `avro-file.max.pending-uploads`, `avro-file.close-timeout-seconds`, `avro-file.upload.max-concurrency`,
    `gcs.prefix`, `gcs.endpoint`, and `s3.listing.min-interval-seconds` for `s3`.
- Metadata keys are at most 127 bytes (`filesystem`) or about 500 bytes (`gcs`, `s3`).
- `gcs` no longer uses Google's client libraries: it talks to GCS's JSON API on the JDK's `java.net.http`, and does
  Google's OAuth 2.0 itself (service account keys, gcloud user credentials, the metadata server, and Application
  Default Credentials in Google's order). Its only dependency besides the Avro engine is Jackson: about 56 Google jars
  fewer, and the copy tool's zip is 7 MB instead of 61 MB. `gcs` is a named module and runs on the module path.
  `external_account` (workload identity federation) is not supported yet.
- Fixed: files that started in the same millisecond hid each other; an upload failure deadlocked the producer; a
  window closed only when the next publish came, so a quiet producer never uploaded; a later upload with an older name
  was skipped; topics could escape the storage folder; file handles leaked; a closed consumer threw
  `NullPointerException`; a cursor at the start of a millisecond skipped it.

### `s3` (new)

- `rawdata-client-provider-s3` (module `io.descoped.rawdata.provider.s3`): the Avro engine on an Amazon S3 bucket,
  with the same layout and ordering as `gcs`. A segment is created with `If-None-Match: *`.
- The AWS SDK for Java 2 with its JDK-based HTTP client; the SDK's default credentials chain (environment, web
  identity, profiles with SSO, container and instance credentials) and region chain. Path-style requests for bucket
  names with dots; optional `AES256` or `aws:kms` server-side encryption; `s3.endpoint` for S3-compatible stores.
- Tested against S3Mock, and by opt-in live tests against real S3.

### Copy tool

- New `rawdata-copy` (`rawdata-copy-util`): copies topics between stores, keeping ids and order, and continues after
  the target's last id. All its logging, including `System.Logger` and `java.util.logging`, goes through slf4j.
  Distributed as `rawdata-copy-<version>.zip` on GitHub releases, not on Maven Central.

## 2.0.0

The last release of the separate module repositories (`rawdata-client-api`, `rawdata-client-provider-postgres`,
`rawdata-client-provider-gcs`); their history is in this repository, with tags such as `rawdata-client-api/2.0.0`.
Released by Descoped in 2024 from its fork of Statistics Norway's repositories, with the packages moved to
`io.descoped`.
