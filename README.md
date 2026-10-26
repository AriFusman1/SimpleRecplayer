# PCAP Player Simple

An MVP pcap replayer. You upload a pcap recorded with `dumpcap` on a service host, pick which traffic
to replay, and the player **impersonates the original source**: it sends the same payload bytes to a
service-under-test, **now**, with **the same time gaps** as in the recording.

- Only sends. It never waits for or reacts to replies.
- Uses normal TCP/UDP sockets (Vert.x networking): only the application payload (the bytes after the TCP/UDP header) is replayed.
- Only one replay at a time in the whole system.

Backend: Java 17 + Vert.x 4.5 + Pcap4J 1.8 (libpcap). Frontend: React + TypeScript + Vite. Storage: S3 (AWS or MinIO).

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
      PcapReader.java                       reads classic pcap files with Pcap4J/libpcap -> ParsedPcap
      Packet.java                           one IPv4 TCP/UDP packet with a payload
      ParsedPcap.java                       all packets of a file + skipped counts + first/last time
      Mode.java                             TCP_SERVER / UDP_SERVER / TCP_CLIENT (+ protocol, filter side)
      Filter.java                           mode + ports + optional IP + time range
      MessageBuilder.java                   packets + filter -> ordered List<Message> (time shifted)
      Message.java                          (offsetMillis, payload)
      PcapSummary.java                      summary for the Upload page + validation of a filter

    replay/                                 (2) sending over the network with the right timing
      Player.java                           start / stop / status; deploys one ReplayVerticle per replay
      ReplayVerticle.java                   one running replay: Vert.x sockets + timers on its own context
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
| `io.vertx:vertx-core` | 4.5.10 | HTTP server, event loop, executeBlocking; replay networking (`NetClient`, `NetServer`, `DatagramSocket`) and timers |
| `io.vertx:vertx-web` | 4.5.10 | Router, body handler, static files |
| `com.fasterxml.jackson.core:jackson-core` / `jackson-databind` | 2.16.1 | JSON <-> Java objects (same version as Vert.x uses) |
| `software.amazon.awssdk:s3` | 2.25.60 | S3 client (Netty and Apache HTTP clients **excluded**) |
| `software.amazon.awssdk:url-connection-client` | 2.25.60 | small JDK-based HTTP client for the S3 SDK |
| `org.pcap4j:pcap4j-core` | 1.8.2 | opens pcap files through the native libpcap and decodes Ethernet / VLAN / Linux SLL / IPv4 / TCP / UDP |
| `org.pcap4j:pcap4j-packetfactory-static` | 1.8.2 | Pcap4J's packet decoders, registered statically (no class scanning) |
| `net.java.dev.jna:jna` | 5.14.0 | how Pcap4J calls libpcap. Pinned above the 5.3.1 that Pcap4J brings, which has no ARM64 (Apple Silicon) support |

Transitive dependencies come from those (Netty via Vert.x; the AWS SDK core modules; `slf4j-api` via Pcap4J and the AWS SDK).

### Native library
Pcap4J needs **libpcap** at runtime (only the library, not the development headers):
- macOS: built in, nothing to install.
- Debian/Ubuntu: `apt-get install libpcap0.8`. That package has `libpcap.so.0.8` but not the plain `libpcap.so`, so start
  Java with `-Dorg.pcap4j.core.pcapLibName=libpcap.so.0.8` (the Docker image does this), or install `libpcap-dev`.
- Reading files needs no root and no capabilities (only live capture would).

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
and a small `eclipse-temurin` JRE image runs it. The runtime stage installs `libpcap0.8` (for Pcap4J) and passes
`-Dorg.pcap4j.core.pcapLibName=libpcap.so.0.8` to Java.

OpenShift notes:
- The image runs as a non-root user and works with **any** user id (OpenShift assigns a random one in group 0).
  All writes go to `TEMP_DIR` under `/tmp`. libpcap is only used to read files, so no extra capabilities are needed.
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

## 8. Reading pcap files (`PcapReader`)

Classic pcap = one 24-byte **global header**, then for every packet a 16-byte **packet header**
(timestamp, captured length, original length) followed by the captured bytes.

**libpcap** (through Pcap4J) reads the file format: byte order, microsecond or nanosecond timestamps.
`PcapReader.open` asks for nanosecond precision (`Pcaps.openOffline(path, TimestampPrecision.NANO)`) and falls back to
`Pcaps.openOffline(path)` if the libpcap is too old for that. **Pcap4J** decodes the network headers. `PcapReader` only decides
what to keep, with these rules:

- **File checks** (`checkMagic`, before libpcap opens it): smaller than 24 bytes -> "too small"; magic `0a0d0d0a` = pcapng
  -> rejected with the `editcap` hint (libpcap could read pcapng, but we keep supporting classic pcap only);
  any magic other than `a1b2c3d4` / `a1b23c4d` (or byte-swapped) -> "not a pcap file".
- **Link type**: `1` Ethernet, `113` Linux SLL, `276` Linux SLL2; anything else -> rejected.
- Each packet is read with `getNextRawPacketEx()` (the raw bytes, so we know the exact captured length);
  the timestamp comes from `handle.getTimestamp()` and the original length from `handle.getOriginalLength()`.
  End of file = `EOFException`. A file cut in the middle of a packet (libpcap error) counts as one truncated packet and ends the read.
- **Truncated** (captured length < original length, i.e. cut by the snap length) -> counted + skipped.
- **Finding IPv4** (`findIpv4Start`): Ethernet (with or without 802.1Q VLAN tags) and Linux SLL are decoded by Pcap4J;
  we add up the header lengths in front of the `IpV4Packet`. **SLL2** isn't known to Pcap4J 1.8.2, so `sll2Ipv4Start`
  reads its fixed 20-byte header (protocol at offset **0**, IP starts at **20**). Not IPv4 (IPv6, ARP...) -> ignored.
- **Fragment** (IPv4 "more fragments" flag set, or fragment offset > 0) -> counted + skipped.
- **TCP / UDP** only (`TcpPacket` / `UdpPacket`), others ignored. Ports and IPs come from Pcap4J's headers.
- **Payload** = the bytes after the TCP/UDP header, up to the IP total length (cuts off Ethernet padding). It is copied from the
  original raw bytes, not from Pcap4J's decoded payload: Pcap4J decodes some ports as protocols (DNS on 53, GTP) and rebuilding
  those can lose trailing bytes.
- Packets with an empty payload (e.g. pure TCP ACKs) are ignored.

Deliberately **not** handled: IP fragment reassembly, TCP stream reassembly, removing retransmissions/duplicates.
Each captured packet's payload is sent as-is, in capture order.

---

## 9. Threads - the 3 kinds

| Thread | What runs there | Rule |
|---|---|---|
| **Vert.x event loop** | every route handler, `sendJson`, `sendFile`, timers | must never block |
| **Vert.x worker** (via `vertx.executeBlocking`) | S3 calls, reading pcaps (Pcap4J/libpcap), building messages | may block. Jobs are *ordered*: they run one at a time, which keeps metadata changes race-free |
| **Replay context** (the event loop of the running `ReplayVerticle`) | connecting / listening, sending, discarding replies, the timers | must never block. Each replay is deployed as a verticle, so Vert.x gives it its own event-loop context |

The replay context and the HTTP threads share data only through `AtomicReference`, `AtomicBoolean`,
`AtomicInteger`, `AtomicLong` (in `Player` / `Replay`). Inside `ReplayVerticle` plain fields are enough: Vert.x runs
all of its code (start, stop, socket handlers, timers) on that one context. Metadata lives in `ConcurrentHashMap`s
(`MetadataStore`, `UploadRoutes.pending`). No `synchronized`, no `wait/notify`, no `new Thread`, no executors.

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
   - **PcapReader.java -> `read(Path)`**:
     **`checkMagic`** (reads the first 24 bytes: too small / pcapng / unknown magic -> error) ->
     **`open`** (`Pcaps.openOffline(path, NANO)`, fallback `openOffline(path)`) -> checks the link type (`handle.getDlt()`) ->
     **`readPackets`**: loops `handle.getNextRawPacketEx()` until `EOFException`. For each packet: time = **`toNanos(handle.getTimestamp())`**;
     truncated (`raw.length < handle.getOriginalLength()`)? -> count + skip. **`findIpv4Start`** (Pcap4J decodes the link layer,
     or **`sll2Ipv4Start`** for SLL2 -> IPv4 offset or -1) -> `IpV4Packet.newPacket(raw, offset, ...)`; fragment flag/offset -> count + skip.
     **`toPacket`** -> `Packet` or null (not TCP/UDP or empty payload; payload copied from the raw bytes).
     Returns **`ParsedPcap`** (packets, first/last packet time, total, fragments, truncated). The libpcap handle is closed (try-with-resources).
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
     (streams `pcaps/{id}.pcap` into a temp file) -> **`PcapReader.read`** -> deletes the temp file -> `ParsedPcap`;
   - **`RecordRoutes.filterFor(record, category, start, end)`** -> `Filter`;
   - **`MessageBuilder.build`** -> `List<Message>` (ordered, `offsetMillis` from the range start);
   - **Player.java -> `start(recordId, name, mode, host, port, rangeStart, messages)`**:
     reads `current`; if that replay isn't done -> `IllegalStateException` (409). Creates a **`Replay`**
     (state `WAITING` for TCP client, else `PLAYING`), then `current.compareAndSet(previous, replay)` —
     the atomic "only one replay" guard — and `vertx.deployVerticle(new ReplayVerticle(replay))`. Returns immediately.
     When the deployment completes *[HTTP event loop]* the deployment id is stored in `replay.deploymentId`
     (if Stop was pressed in the meantime, it undeploys right away);
   - returns `player.status()` -> JSON. **App.tsx** `setReplay(status)` shows the bar at once.
4. **ReplayVerticle.java** *[replay context: the verticle's own event loop, for everything below]*:
   - **`start()`** — picks by mode:
     - **`connectToService`** (TCP server mode): `vertx.createNetClient` (`TCP_NODELAY` on, 5 s connect timeout) ->
       `client.connect(port, host)` -> **`onConnected`**. A connect error -> `FAILED` (e.g. "Connection refused").
     - **`waitForService`** (TCP client mode): `vertx.createNetServer` (`TCP_NODELAY` on) -> `listen(port)`; state stays `WAITING`.
       The `connectHandler` takes the first connection -> **`onConnected`**; any later connection is closed at once (only one allowed).
       The server stays open until the replay ends, because closing a Vert.x server also closes its connections.
       Listen error (e.g. "Address already in use") -> `FAILED`.
     - **`sendUdp`** (UDP server mode): `vertx.createDatagramSocket()`; each payload is one `udp.send(buffer, port, host)` ->
       **`startTiming`**.
   - **`onConnected(socket)`** (both TCP modes) — `socket.handler(...)` reads and ignores the replies (so they can't fill the buffers);
     `exceptionHandler` -> `FAILED` (e.g. "Connection reset"); `closeHandler` -> `FAILED` "Connection closed by the service";
     state -> `PLAYING`; **`startTiming`**.
   - **`startTiming`** — `startNanos = System.nanoTime()` (also stored in `replay.startNanos` for the status), then **`sendDueMessages`**.
   - **`sendDueMessages`** — the timing: for the next message, `delay = (start + offsetMillis - now)` in ms. If `delay > 0`, sets
     **one** `vertx.setTimer(delay, ...)` that calls `sendDueMessages` again, and returns. Otherwise sends it (one `socket.write` or
     one datagram per message), `replay.sent.incrementAndGet()`, and goes on with the next one. Always measured from the same start,
     so there is no drift. If the TCP write queue is full it waits for the `drainHandler` (like a blocking write would).
     After the last message -> **`end(FINISHED)`**. A failed write/send -> `end(FAILED, message)`.
   - **`end(state, error)`** -> **`finish`** (cancels the pending timer, closes socket / server / client / UDP socket,
     `replay.finish(state, error)` sets the end time, error and final state) -> `vertx.undeploy(deploymentID())`.
5. **Status polling** — **App.tsx** `setInterval` every 1 s -> **`api.replayStatus()`** -> `GET /api/replay/status` ->
   **ReplayRoutes.java -> `status`** -> **Player.java -> `status()`** -> **Replay.java -> `toStatus()`** *[HTTP event loop; only reads atomic fields]*:
   elapsed = now (or end) - start; remaining = last message offset - elapsed; recording clock = range start + elapsed.
   **NowPlaying.tsx** renders name, target, state ("Waiting for connection on port X"), progress bar
   (elapsed / (elapsed + remaining)), elapsed/remaining, recording clock, messages sent/total, Stop button,
   and the error when `FAILED`. After the replay ends the bar shows the final state until you press Close.
6. **Stop** — **NowPlaying.tsx** Stop -> **App.tsx -> `stopReplay()`** -> `POST /api/replay/stop` ->
   **ReplayRoutes.java -> `stop`** -> **Player.java -> `stop()`** *[HTTP event loop]*: sets `replay.stopRequested = true` and
   `vertx.undeploy(deploymentId)`. Vert.x then calls **ReplayVerticle -> `stop()`** *[replay context]* -> **`finish(STOPPED)`**:
   the pending timer is cancelled and the sockets are closed, so the replay ends as `STOPPED` right away
   (also while `WAITING` for a connection).

### 10.3 Edit

1. **RecordCard.tsx** Edit -> **RecordDialogs.tsx -> `EditDialog`** — name, description, start, end (text in `DD/MM/YYYY HH:MM:SS`).
2. **`save()`** -> `parseTime` both times -> **`api.updateRecord`** -> `PUT /api/records/{id}`.
3. **RecordRoutes.java -> `update(ctx)`** *[worker]*: `Validation.recordName(store, name, id)` (unique, except itself).
   If the time range changed: **`loadParsed`** (download + `PcapReader.read`) -> **`PcapSummary.validate`** with the record's filter and the new
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
- Timing precision is about 1 ms (Vert.x timers have millisecond resolution).
- libpcap must be installed where the jar runs (see section 2, "Native library").
