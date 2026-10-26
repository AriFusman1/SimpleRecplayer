# PCAP Player Simple

An MVP pcap replayer. You upload a pcap recorded with `dumpcap` on a service host, pick which traffic
to replay, and the player **impersonates the original source**: it sends the same payload bytes to a
service-under-test, **now**, with **the same time gaps** as in the recording.

- Only sends. It never waits for or reacts to replies.
- Uses normal Java sockets: only the application payload (the bytes after the TCP/UDP header) is replayed.
- Only one replay at a time in the whole system.

Backend: Java 17 + Vert.x 4.5. Frontend: React + TypeScript + Vite. Storage: S3 (AWS or MinIO).

---

## 1. Project layout

```
backend/
  pom.xml                                   Maven build, all versions pinned in <properties>
  src/main/resources/config.json            default settings (overridable by env vars)
  src/main/java/com/pcapplayer/
    Main.java                               startup: config -> S3 metadata -> player -> HTTP server
    Config.java                             loads config.json + CONFIG_FILE + environment variables
    Json.java                               the one shared Jackson ObjectMapper

    pcap/                                   (1) reading pcap files - pure logic, no network, no S3
      PcapParser.java                       hand-written classic-pcap parser -> ParsedPcap
      Packet.java                           one IPv4 TCP/UDP packet with a payload
      ParsedPcap.java                       all packets of a file + skipped counts + first/last time
      Mode.java                             TCP_SERVER / UDP_SERVER / TCP_CLIENT (+ protocol, filter side)
      Filter.java                           mode + ports + optional IP + time range
      MessageBuilder.java                   packets + filter -> ordered List<Message> (time shifted)
      Message.java                          (offsetMillis, payload)
      PcapSummary.java                      summary for the Upload page + validation of a filter

    replay/                                 (2) sending over the network with the right timing
      Player.java                           start / stop / status, the timing loop, TCP & UDP sending
      Replay.java                           one replay: what to send + live progress (atomic fields)
      ReplayState.java                      IDLE / WAITING / PLAYING / FINISHED / STOPPED / FAILED
      ReplayStatus.java                     the snapshot returned to the UI

    storage/                                (3) S3
      S3Storage.java                        put / get / list / delete objects (AWS SDK v2)
      MetadataStore.java                    categories, tags, records in ConcurrentHashMaps + S3 writes
      Category.java, Tag.java, PcapRecord.java   the stored data

    api/                                    thin HTTP layer that connects the three packages
      ApiServer.java                        router, body handlers, static files, error -> JSON
      UploadRoutes.java                     analyze / validate / save
      RecordRoutes.java                     list / edit / delete / download records
      CategoryRoutes.java                   categories and tags CRUD
      ReplayRoutes.java                     replay start / stop / status
      Validation.java                       shared input checks (unique name, tags, port)

frontend/
  package.json, vite.config.ts, tsconfig.json, index.html
  src/main.tsx                              mounts <App/>
  src/App.tsx                               nav bar, page switching, data loading, status polling, error popup
  src/api.ts                                TypeScript types + one function per API call
  src/time.ts                               Israel-time formatting/parsing (DD/MM/YYYY HH:MM:SS)
  src/styles.css                            the only stylesheet (dark theme)
  src/components/Modal.tsx                  Modal + ConfirmDialog
  src/components/NowPlaying.tsx             the now-playing bar
  src/components/RecordCard.tsx             one record on the Player page
  src/components/RecordDialogs.tsx          PlayDialog + EditDialog
  src/pages/PlayerPage.tsx                  search/filter + record list
  src/pages/UploadPage.tsx                  drop file -> form -> check -> save
  src/pages/CategoriesPage.tsx              categories and tags

samples/sample.pcap                         a small test recording (see section 4.4)
Dockerfile                                  multi-stage build: frontend -> backend -> runtime
```

---

## 2. Dependencies (all of them)

### Backend (runtime)
| Library | Version | Why |
|---|---|---|
| `io.vertx:vertx-core` | 4.5.10 | HTTP server, event loop, executeBlocking |
| `io.vertx:vertx-web` | 4.5.10 | Router, body handler, static files |
| `com.fasterxml.jackson.core:jackson-core` / `jackson-databind` | 2.16.1 | JSON <-> Java objects (same version as Vert.x uses) |
| `software.amazon.awssdk:s3` | 2.25.60 | S3 client (Netty and Apache HTTP clients **excluded**) |
| `software.amazon.awssdk:url-connection-client` | 2.25.60 | small JDK-based HTTP client for the S3 SDK |

Transitive dependencies come from those (Netty via Vert.x; the AWS SDK core modules).

### Backend (build only)
Maven 3.9.x, `maven-compiler-plugin` 3.13.0, `maven-shade-plugin` 3.5.3 (builds the fat jar). JDK 17.

### Frontend
| Package | Version | Kind |
|---|---|---|
| `react`, `react-dom` | 18.3.1 | runtime (the only two) |
| `vite` | 5.4.8 | build |
| `@vitejs/plugin-react` | 4.3.2 | build |
| `typescript` | 5.5.4 | build |
| `@types/react` / `@types/react-dom` | 18.3.11 / 18.3.1 | build |

Node.js 20 is used to build.

---

## 3. Configuration

Settings are read in this order, each overriding the previous one:

1. `backend/src/main/resources/config.json` (packaged defaults)
2. a JSON file named by the `CONFIG_FILE` environment variable (same keys), if set
3. single environment variables

| config.json key | Environment variable | Default | Meaning |
|---|---|---|---|
| `httpPort` | `HTTP_PORT` | `8080` | HTTP port |
| `maxUploadMb` | `MAX_UPLOAD_MB` | `1024` | largest pcap that can be uploaded |
| `tempDir` | `TEMP_DIR` | `/tmp/pcap-player` | uploads waiting to be saved, downloads, Vert.x file cache |
| `s3Endpoint` | `S3_ENDPOINT` | `http://localhost:9000` | S3/MinIO URL. **Empty = real AWS** |
| `s3Region` | `S3_REGION` | `us-east-1` | region |
| `s3Bucket` | `S3_BUCKET` | `pcap-player-simple` | bucket (created at startup if missing) |
| `s3AccessKey` | `S3_ACCESS_KEY` | `minioadmin` | access key. **Empty = default AWS credential chain** (IAM role, `AWS_*` env vars...) |
| `s3SecretKey` | `S3_SECRET_KEY` | `minioadmin` | secret key |
| `s3PathStyle` | `S3_PATH_STYLE` | `true` | path-style URLs (`http://host/bucket/key`). `true` for MinIO, usually `false` for AWS |

Use a **separate, new bucket** for this MVP.

---

## 4. Build and run locally

### 4.1 Start MinIO (local S3)

```bash
docker run -d --name minio -p 9000:9000 -p 9001:9001 minio/minio:RELEASE.2024-10-13T13-34-11Z server /data --console-address ":9001"
```

Login for the console (http://localhost:9001): `minioadmin` / `minioadmin`. The app creates the bucket itself.

### 4.2 Build and run the app (one jar)

```bash
cd frontend && npm ci && npm run build
```

This writes the built UI into `backend/src/main/resources/webroot`, so it ends up inside the jar.

```bash
cd backend && mvn -B package
```

```bash
java -jar backend/target/pcap-player-simple.jar
```

Open http://localhost:8080. Health check: `GET http://localhost:8080/health` -> `{"status":"UP"}`.

### 4.3 Frontend development with hot reload

Run the jar (above), then:

```bash
cd frontend && npm run dev
```

Open http://localhost:5173. Vite forwards `/api` and `/health` to `localhost:8080`.

### 4.4 Try it

1. Categories & Tags: create a category `Demo`, mode **TCP server**.
2. Upload `samples/sample.pcap`, category `Demo`, tick destination port `5000`, Check, Save.
3. In a terminal start a listener: `nc -lk 5000` (prints what arrives).
4. Player: Play -> target `127.0.0.1`, port `5000`. You will see `msg-0` ... `msg-19`, one every 200 ms.

The sample also has UDP to port 6000 (try a **UDP server** category with `nc -lu 6000`) and TCP sent
*from* port 7000 (try a **TCP client** category; after Play, connect with `nc 127.0.0.1 5000`).
It also contains one IP fragment and one truncated packet, so you can see both warnings.

---

## 5. Docker image and OpenShift

```bash
docker build -t pcap-player-simple:1.0.0 .
```

```bash
docker run -p 8080:8080 -e S3_ENDPOINT=http://host.docker.internal:9000 pcap-player-simple:1.0.0
```

The Dockerfile has 3 stages: `node` builds the UI, `maven` builds the fat jar (with the UI inside),
and a small `eclipse-temurin` JRE image runs it.

OpenShift notes:
- The image runs as a non-root user and works with **any** user id (OpenShift assigns a random one in group 0).
  All writes go to `TEMP_DIR` under `/tmp`.
- Run **one replica** (`replicas: 1`): the "only one replay at a time" rule and the pending uploads live in memory.
- Liveness/readiness probe: `GET /health` on port 8080.
- Set S3 settings with environment variables (secret key from a Secret).
- In TCP-client mode the player **listens** on a port chosen in the Play dialog; to reach it from outside the
  pod, expose that port with a Service.

---

## 6. API

All errors return `{"error": "message"}` with status 400 (bad input), 404 (not found),
409 (conflict, e.g. a replay is running), 413 (file too big) or 500. The UI shows the message in a popup.

| Method & path | Body | Returns |
|---|---|---|
| `GET /health` | - | `{"status":"UP"}` |
| `POST /api/pcap/analyze` | multipart, field `file` | `{uploadId, fileName, fileSize, summary}` |
| `POST /api/pcap/validate` | `{uploadId, categoryId, ports, ipFilter, startTime, endTime}` | `{messageCount, durationMillis, protocol, categoryName, mode, warnings}` |
| `POST /api/records` | `{uploadId, name, description, categoryId, tagIds, ports, ipFilter, startTime, endTime}` | the new record |
| `GET /api/records` | - | all records, newest first |
| `PUT /api/records/{id}` | `{name, description, startTime, endTime}` | the updated record |
| `DELETE /api/records/{id}` | - | `{deleted: id}` |
| `GET /api/records/{id}/download` | - | the original pcap file |
| `GET /api/categories` | - | `[{id, name, mode}]` |
| `POST /api/categories` | `{name, mode}` | the category |
| `PUT /api/categories/{id}` | `{name}` (mode can't change) | the category |
| `DELETE /api/categories/{id}` | - | deletes it **and its records, files and tags** |
| `GET /api/tags` | - | `[{id, name, categoryId}]` (`categoryId` null = global) |
| `POST /api/tags` | `{name, categoryId}` | the tag |
| `PUT /api/tags/{id}` | `{name}` | the tag |
| `DELETE /api/tags/{id}` | - | deletes it and removes it from records |
| `POST /api/replay/start` | `{recordId, host, port}` (`host` ignored in TCP-client mode) | replay status |
| `POST /api/replay/stop` | - | replay status |
| `GET /api/replay/status` | - | replay status |

All times are **epoch milliseconds** in the API. The UI shows them in Israel time (Asia/Jerusalem).

---

## 7. Connection modes

The mode belongs to the **category** and describes **the original service** that was recorded:

| Mode | Original service was... | Player acts as... | Packets sent |
|---|---|---|---|
| TCP server | TCP server | TCP client, connects to target IP:port | TCP, **destination** port in selected ports |
| UDP server | UDP receiver | UDP sender to target IP:port | UDP, **destination** port in selected ports |
| TCP client | TCP client that connected to the source | TCP server, listens on a port, accepts one connection | TCP, **source** port in selected ports |

The optional IP filter is on the same side as the port filter.

---

## 8. The pcap file format (what the parser reads)

Classic pcap = one 24-byte **global header**, then for every packet a 16-byte **packet header**
followed by the captured bytes. See `PcapParser.parse`.

**Global header** (byte order = the capturing machine's, detected from the magic number):

| Offset | Size | Field | Used for |
|---|---|---|---|
| 0 | 4 | magic | `a1b2c3d4` = microseconds, `a1b23c4d` = nanoseconds; read swapped (`d4c3b2a1`, `4d3cb2a1`) = little-endian file. `0a0d0d0a` = pcapng -> rejected |
| 4 | 2+2 | version | ignored |
| 8 | 4 | thiszone | ignored |
| 12 | 4 | sigfigs | ignored |
| 16 | 4 | snaplen | ignored |
| 20 | 4 | link type | `1` Ethernet, `113` Linux SLL, `276` Linux SLL2; anything else -> rejected |

**Packet header** (same byte order):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | timestamp seconds |
| 4 | 4 | timestamp microseconds (or nanoseconds) |
| 8 | 4 | captured length (bytes stored in the file) |
| 12 | 4 | original length (bytes on the wire) - if bigger than captured, the packet was **truncated** -> skipped |

**Inside the packet** (always big-endian / network byte order):

- **Ethernet**: dst MAC (6), src MAC (6), EtherType at offset **12**. If EtherType is `0x8100` (802.1Q VLAN),
  the real EtherType is at offset **16** and IP starts at **18**; otherwise IP starts at **14**.
- **Linux SLL** (16-byte header): protocol at offset **14**, IP starts at **16**.
- **Linux SLL2** (20-byte header): protocol at offset **0**, IP starts at **20**.
- Protocol/EtherType must be `0x0800` (IPv4), otherwise the packet is ignored (IPv6, ARP...).
- **IPv4**: byte 0 = version (high 4 bits, must be 4) + header length in 32-bit words (low 4 bits);
  bytes 2-3 total length (used to cut off Ethernet padding); bytes 6-7 flags + fragment offset
  (`0x2000` = more fragments, low 13 bits = offset; either set -> **fragment** -> skipped);
  byte 9 protocol (6 TCP, 17 UDP, others ignored); bytes 12-15 source IP; 16-19 destination IP.
- **TCP**: bytes 0-1 source port, 2-3 destination port, byte 12 high 4 bits = header length in 32-bit words.
  Payload = after the header, up to the IP total length.
- **UDP**: bytes 0-1 source port, 2-3 destination port, header is always 8 bytes.
- Packets with an empty payload (e.g. pure TCP ACKs) are ignored.

Deliberately **not** handled: IP fragment reassembly, TCP stream reassembly, removing retransmissions/duplicates.
Each captured packet's payload is sent as-is, in capture order.

---

## 9. Threads - the 3 kinds

| Thread | What runs there | Rule |
|---|---|---|
| **Vert.x event loop** | every route handler, `sendJson`, `sendFile`, timers | must never block |
| **Vert.x worker** (via `vertx.executeBlocking`) | S3 calls, parsing pcaps, building messages | may block. Jobs are *ordered*: they run one at a time, which keeps metadata changes race-free |
| **Replay executor** (`Player.executor`, fixed pool of 2) | thread 1: the send loop. Thread 2: TCP only, reads and discards replies | may block/sleep for as long as the replay lasts |

The replay executor and Vert.x threads share data only through `AtomicReference`, `AtomicBoolean`,
`AtomicInteger`, `AtomicLong` (in `Player` / `Replay`). Metadata lives in `ConcurrentHashMap`s
(`MetadataStore`, `UploadRoutes.pending`). No `synchronized`, no `wait/notify`, no `new Thread`.

Startup (`Main.main`) runs on the main thread: it loads all metadata from S3 *before* the HTTP server starts.

---

## 10. Code flow walkthroughs

Notation: **file -> function** (input) -> output. *[thread]*.

### 10.1 Upload

1. **UploadPage.tsx -> `onDrop` / file input `onChange`** — the user drops or picks one file -> calls `analyze(file)`. *[browser]*
2. **UploadPage.tsx -> `analyze(picked)`** -> **api.ts -> `api.analyze(file)`** — builds a `FormData` with field `file`,
   `POST /api/pcap/analyze`. *[browser]*
3. **ApiServer.java -> `uploadBody`** (Vert.x `BodyHandler`) — streams the upload to `TEMP_DIR/uploads/<random>`;
   rejects files over `maxUploadMb` with 413. Uploaded files are **not** auto-deleted (we keep them until save). *[event loop, non-blocking]*
4. **UploadRoutes.java -> `analyze(ctx)`** — checks there is exactly one file, then calls
   `ApiServer.blocking(vertx, ctx, work)`. *[event loop]*
5. **ApiServer.java -> `blocking(vertx, ctx, work)`** — `vertx.executeBlocking(work)`; on success `sendJson`, on failure `ctx.fail`. *[event loop -> worker]*
6. Inside the work *[worker]*:
   - **PcapParser.java -> `parse(Path)`** — reads the file (`Files.readAllBytes`) -> `parse(byte[])`:
     detects byte order + time unit from the magic, checks the link type, loops over packet headers.
     For each packet: truncated? -> count + skip. **`findIpv4Start`** (link-layer header -> IPv4 offset or -1).
     **`isFragment`** -> count + skip. **`parseIpv4`** -> `Packet` or null (not TCP/UDP or empty payload).
     Returns **`ParsedPcap`** (packets, first/last packet time, total, fragments, truncated).
     A bad file throws `IllegalArgumentException` -> the temp file is deleted -> HTTP 400 popup.
   - Saves `PendingUpload(file, fileName, size, parsed, createdAt)` in the `pending` `ConcurrentHashMap` under a new `uploadId`.
   - **PcapSummary.java -> `summarize(ParsedPcap)`** -> `Summary` (recording start/end, packet counts, protocols,
     destination/source ports with protocol + count, destination/source IPs with count, warnings).
   - Returns `AnalyzeResponse(uploadId, fileName, fileSize, summary)` -> JSON. *[event loop]*
7. **UploadPage.tsx** — shows the file panel, fills defaults: name = file name, start = recording start rounded **down**
   to the second, end = recording end rounded **up** (`time.ts -> floorSecond / ceilSecond / formatTime`).
8. The user picks a category. **`onCategoryChange`** resets ports/IP and drops tags of other categories.
   The port list shown = `summary.dstPorts` (TCP/UDP server) or `summary.srcPorts` (TCP client), filtered to the mode's protocol
   (`api.ts -> filtersOnSource, protocolOf`). Tags shown = global + this category's.
9. **Check** -> **UploadPage.tsx -> `runCheck()`** -> `formValues()` (parses the times with **time.ts -> `parseTime`**,
   which converts Israel wall time to epoch millis) -> **`api.validate`** -> `POST /api/pcap/validate`.
10. **UploadRoutes.java -> `validate(ctx)`** — `Json.read` the body *[event loop]*, then *[worker]*:
    `pendingUpload(uploadId)` (404 if expired), `store.category(id)`, builds a **`Filter`**, calls
    **PcapSummary.java -> `validate(ParsedPcap, Filter)`**:
    - **`validateTimeRange`** — range must be inside [recording start floored, recording end ceiled] and start < end;
    - at least one port;
    - the file must have packets of the mode's protocol ("protocol not matching the mode");
    - **MessageBuilder.java -> `build(packets, filter)`** — keeps packets where `matches()` is true
      (protocol, port on the right side, IP on the right side, time in range) and makes
      `Message(packetTime - rangeStart, payload)`; zero messages -> error;
    - returns `Check(messageCount, durationMillis = end - start, protocol, warnings)`.
    Response: `ValidateResponse` -> the "Before saving" panel. Changing any filter field clears this panel (`changed()`).
11. **Save record** -> **UploadPage.tsx -> `save()`** -> **`api.saveRecord`** -> `POST /api/records`.
12. **UploadRoutes.java -> `save(ctx)`** *[worker]*:
    - **Validation.java -> `recordName(store, name, null)`** — trimmed, not empty, unique (case-insensitive) -> else 400
      "already exists, please choose a different name".
    - **`tagsAllowed`** — each tag must exist and be global or of this category.
    - **`PcapSummary.validate`** again (never trust the browser).
    - **MetadataStore.java -> `savePcapFile(id, file)`** -> **S3Storage.java -> `putFile("pcaps/{id}.pcap")`**.
    - builds **`PcapRecord`** (with `Stats`, recording start/end, file name/size, upload date) ->
      **`MetadataStore.saveRecord`** -> **`S3Storage.putBytes("records/{id}.json")`** and puts it in the `records` map.
    - removes the pending upload and deletes the temp file. Returns the record.
13. **UploadPage.tsx** -> `reload()` (App.tsx: fetches categories, tags, records) -> `onSaved()` switches to the Player page.

Unsaved uploads are dropped after 1 hour: **UploadRoutes -> `removeOldPendingUploads`**, run every 10 minutes by `vertx.setPeriodic` *[event loop; file delete is async]*.

### 10.2 Play

1. **PlayerPage.tsx** — the Play button on **RecordCard.tsx** is disabled while `isReplayActive(replay)`. Click -> `setPlaying(record)` -> **RecordDialogs.tsx -> `PlayDialog`**.
   TCP/UDP server modes ask for target IP + port (default 5000); TCP client mode asks only for the listen port (default 5000).
2. **PlayDialog -> `start()`** -> **api.ts -> `api.startReplay(recordId, host, port)`** -> `POST /api/replay/start`.
3. **ReplayRoutes.java -> `start(ctx)`** — reads `StartRequest` *[event loop]*, then *[worker]*:
   - `player.status().state().isDone()` — fail fast (409) if a replay is running, before downloading anything;
   - `store.record(id)`, `store.category(...)`, **`Validation.port`**, target host required unless TCP client;
   - **RecordRoutes.java -> `loadParsed(recordId)`** — **`MetadataStore.downloadPcapFile`** -> **`S3Storage.downloadToFile`**
     (streams `pcaps/{id}.pcap` into a temp file) -> **`PcapParser.parse`** -> deletes the temp file -> `ParsedPcap`;
   - **`RecordRoutes.filterFor(record, category, start, end)`** -> `Filter`;
   - **`MessageBuilder.build`** -> `List<Message>` (ordered, `offsetMillis` from the range start);
   - **Player.java -> `start(recordId, name, mode, host, port, rangeStart, messages)`**:
     reads `current`; if that replay isn't done -> `IllegalStateException` (409). Creates a **`Replay`**
     (state `WAITING` for TCP client, else `PLAYING`), then `current.compareAndSet(previous, replay)` —
     the atomic "only one replay" guard — and `executor.submit(() -> run(replay))`. Returns immediately;
   - returns `player.status()` -> JSON. **App.tsx** `setReplay(status)` shows the bar at once.
4. **Player.java -> `run(replay)`** *[replay executor, thread 1]* — picks by mode:
   - **`playAsTcpClient`** (TCP server mode): new `Socket`, registers it in `replay.openSocket` (so Stop can close it),
     `setTcpNoDelay(true)`, `connect(host:port, 5 s timeout)`, then **`sendOverTcp`**.
   - **`playAsTcpServer`** (TCP client mode): `ServerSocket` bound to the port, state stays `WAITING`,
     `accept()` blocks until the service connects; the listening socket is then closed (only one connection),
     state -> `PLAYING`, **`sendOverTcp`**.
   - **`playAsUdpSender`** (UDP server mode): `DatagramSocket`; each payload is one `DatagramPacket` to host:port.
   - **`sendOverTcp`** — submits **`discardReplies(in)`** to the executor *[replay executor, thread 2]*: reads and ignores
     everything until the socket closes, so replies can't fill the buffers. Then one `out.write(payload)` + `flush()` per message.
   - **`sendWithTiming(replay, sender)`** — the timing loop: `start = System.nanoTime()` (stored in `replay.startNanos`);
     for each message `due = start + offsetMillis`; sleeps in steps of at most 100 ms until `due` (checking
     `stopRequested` each step); sends; `replay.sent.incrementAndGet()`. Always measured from the same `start`, so there is no drift.
   - End: `replay.finish(FINISHED)`; on an exception `finish(FAILED, message)` (e.g. "Connection refused",
     "Connection reset"), or `STOPPED` if Stop was requested. The socket is always closed in `finally`.
5. **Status polling** — **App.tsx** `setInterval` every 1 s -> **`api.replayStatus()`** -> `GET /api/replay/status` ->
   **ReplayRoutes.java -> `status`** -> **Player.java -> `status()`** -> **Replay.java -> `toStatus()`** *[event loop; only reads atomic fields]*:
   elapsed = now (or end) - start; remaining = last message offset - elapsed; recording clock = range start + elapsed.
   **NowPlaying.tsx** renders name, target, state ("Waiting for connection on port X"), progress bar
   (elapsed / (elapsed + remaining)), elapsed/remaining, recording clock, messages sent/total, Stop button,
   and the error when `FAILED`. After the replay ends the bar shows the final state until you press Close.
6. **Stop** — **NowPlaying.tsx** Stop -> **App.tsx -> `stopReplay()`** -> `POST /api/replay/stop` ->
   **ReplayRoutes.java -> `stop`** -> **Player.java -> `stop()`** *[event loop]*: sets `replay.stopRequested = true` and closes
   `replay.openSocket` (this wakes up a blocked `accept()` or `write()`). The send loop notices within 100 ms
   and the replay ends as `STOPPED`.

### 10.3 Edit

1. **RecordCard.tsx** Edit -> **RecordDialogs.tsx -> `EditDialog`** — name, description, start, end (text in `DD/MM/YYYY HH:MM:SS`).
2. **`save()`** -> `parseTime` both times -> **`api.updateRecord`** -> `PUT /api/records/{id}`.
3. **RecordRoutes.java -> `update(ctx)`** *[worker]*: `Validation.recordName(store, name, id)` (unique, except itself).
   If the time range changed: **`loadParsed`** (download + parse) -> **`PcapSummary.validate`** with the record's filter and the new
   range (time inside the recording, messages > 0) -> new `Stats`. Builds the updated `PcapRecord` ->
   **`MetadataStore.saveRecord`** (S3 `records/{id}.json` + map). Returns it; the UI reloads.

### 10.4 Download

1. **RecordCard.tsx** — a plain link to `/api/records/{id}/download` (the browser downloads it).
2. **RecordRoutes.java -> `download(ctx)`** — `executeBlocking` *[worker]*: `store.record(id)`, **`tempFile()`**,
   **`MetadataStore.downloadPcapFile`** (S3 -> temp file). Then *[event loop]*: sets `Content-Disposition: attachment; filename="<original name>"`,
   `response.sendFile(temp)` (non-blocking streaming), and deletes the temp file when done.

### 10.5 Delete a record

1. **RecordCard.tsx** Delete -> **PlayerPage.tsx** `setDeleting(record)` -> **Modal.tsx -> `ConfirmDialog`**.
2. Confirm -> **`confirmDelete()`** -> **`api.deleteRecord`** -> `DELETE /api/records/{id}`.
3. **RecordRoutes.java -> `delete(ctx)`** *[worker]* -> **MetadataStore.java -> `deleteRecord(id)`** -> deletes
   `pcaps/{id}.pcap` and `records/{id}.json` in S3, removes it from the map. UI reloads.

### 10.6 Categories and tags

All in **CategoriesPage.tsx** -> **api.ts** -> **CategoryRoutes.java** -> **MetadataStore.java** -> **S3Storage.java**.
Every write route runs in `executeBlocking` *[worker]*; the list routes read the maps directly *[event loop]*.

- **Create category**: `addCategory()` -> `POST /api/categories` -> `createCategory` -> `uniqueCategoryName` (not empty, unique) ->
  new `Category(uuid, name, mode)` -> `saveCategory` -> map + rewrite `categories.json`.
- **Rename category**: inline edit (`startRename` / `saveRename`) -> `PUT /api/categories/{id}` -> `renameCategory` — keeps
  the old **mode** (it can't change: the records were filtered for it).
- **Delete category**: `ConfirmDialog` shows how many records and tags will be deleted (counted in the browser) ->
  `DELETE /api/categories/{id}` -> `deleteCategory` -> **`MetadataStore.deleteCategory`**: `deleteRecord` for each of its
  records (file + json), removes its tags (rewrites `tags.json`), removes it (rewrites `categories.json`).
- **Create tag**: `addTag()` -> `POST /api/tags` (`categoryId` null = global) -> `createTag` -> the category must exist,
  `uniqueTagName` (unique within global or within that category) -> `saveTag` -> rewrite `tags.json`.
- **Rename tag**: `PUT /api/tags/{id}` -> `renameTag` (scope stays the same).
- **Delete tag**: `ConfirmDialog` shows how many records use it -> `DELETE /api/tags/{id}` -> **`MetadataStore.deleteTag`**:
  rewrites every record that has the tag without it, then removes the tag (rewrites `tags.json`).

### 10.7 Startup

**Main.java -> `main`** *[main thread]*: **`Config.load()`** -> creates `TEMP_DIR` -> `Vertx.vertx(...)` with the file cache in
`TEMP_DIR/vertx-cache` (the working directory may be read-only on OpenShift) -> **`MetadataStore.load()`**:
**`S3Storage.ensureBucket()`** (create if missing), reads `categories.json`, `tags.json` and every `records/*.json`
into the maps -> `new Player()` -> **`ApiServer.createRouter(...)`** -> `createHttpServer().listen(httpPort)`.

### 10.8 Errors

Any exception in a route (thrown on the event loop or passed via `ctx.fail` from a worker) reaches
**ApiServer.java -> `handleFailure`**: `IllegalArgumentException` -> 400, `NoSuchElementException` -> 404,
`IllegalStateException` -> 409, body too large -> 413, anything else -> 500 (stack trace printed).
The body is `{"error": "..."}`. In the browser **api.ts -> `request`** throws `Error(message)` and the pages
call `showError` -> **App.tsx** shows the Error modal.

---

## 11. S3 layout

```
<bucket>/
  categories.json          [{id, name, mode}]
  tags.json                [{id, name, categoryId}]
  records/{recordId}.json  one PcapRecord
  pcaps/{recordId}.pcap    the original uploaded file (one per record, never shared)
```

---

## 12. Known limits of this MVP

- No IP fragment / TCP stream reassembly, no retransmission removal (see warnings on each record).
- The whole pcap is read into memory when analyzing and when starting a replay; very large files need a large heap
  (`-XX:MaxRAMPercentage=75` is set in the image).
- Blocking jobs run one at a time (ordered `executeBlocking`), so a big upload delays other changes for a moment.
- Pending uploads, the replay status and the "one replay" rule are in memory: run a single replica.
- Timing precision is about 1 ms.
