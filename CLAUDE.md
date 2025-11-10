# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

### Building
```bash
./gradlew build           # Full build with tests
./gradlew compileKotlin   # Compile Kotlin sources only
./gradlew bootJar         # Create executable JAR
```

### Running
```bash
# Start infrastructure (Redis, MinIO)
docker-compose up -d

# Run the application
./gradlew bootRun         # Development mode on port 8098
```

### Testing
```bash
./gradlew test            # Run all tests
./gradlew test --tests ClassName  # Run specific test class
```

## Architecture Overview

### Real-Time Collaborative Editing Flow

This application implements real-time collaborative document editing using Operational Transformation (OT). The system synchronizes edits across multiple users editing the same document simultaneously.

**Key Flow:**
1. User makes edit → `contentChange` event fires in DocumentEditor
2. Operation sent to `CollaborativeEditingController.updateAction()`
3. **Atomic version management** via Lua scripts in Redis ensures no race conditions
4. **Operational Transformation** resolves conflicts when concurrent edits exist
5. Operation broadcast via WebSocket (local clients) and Redis pub/sub (cross-server)
6. Remote clients receive and apply transformed operations

### Redis Cache Architecture

Redis uses a **ZSET+HASH structure** for gapless, ordered operation storage:

1. **Operation Storage**: HASH stores operation JSON by version (`roomName:ops_hash`)
2. **Operation Index**: ZSET maintains order by score=version (`roomName:ops_index`)
3. **Version Counter**: STRING tracks current version (`roomName:version`)
4. **Persisted Version**: STRING tracks highest version saved to MinIO (`roomName:persisted_version`)
5. **User Presence**: LIST tracks connected users (`roomName:user_info`)

**Autosave with CAS Pattern:**
- `BackgroundService` runs every 3 seconds (configurable)
- Phase 1: Fast snapshot of pending operations (no locks held)
- Phase 2: Apply operations to document in MinIO (slow I/O, no Redis locks)
- Phase 3: Cleanup with CAS check on `persisted_version` (atomic)
- If `persisted_version` changed during save, another process won - skip cleanup
- Operations accumulate until persisted, then pruned atomically

### Lua Scripts for CAS-Based Atomicity

All Redis operations use Lua scripts (`CollaborativeEditingHelper`) with Compare-And-Swap semantics:

- `RESERVE_VERSION_SCRIPT`: Atomically allocate version slot with `__PENDING__` placeholder, return contiguous committed ops for transformation
- `COMMIT_TRANSFORMED_SCRIPT`: CAS commit - only succeeds if slot still pending and all prior versions are contiguous and committed
- `GET_PENDING_SCRIPT`: Fetch contiguous committed operations since client version, return resync flag if client is stale
- `AUTOSAVE_SNAPSHOT_SCRIPT`: Fast read-only snapshot of pending versions for background save
- `AUTOSAVE_CLEANUP_SCRIPT`: CAS cleanup - advance `persisted_version` and prune ops only if no other process advanced it first

**CAS Retry Flow:**
1. Reserve version slot (atomically get version + placeholder)
2. Transform operation locally (no Redis locks held)
3. Commit with CAS check (verify slot still pending, all priors committed)
4. On conflict (`GAP_BEFORE`, `PENDING_BEFORE`, `VERSION_CONFLICT`), retry from step 1
5. Max 5 retries before failing

### Broadcasting Architecture

- **WebSocket** (via `DocumentEditorHub`): Broadcasts to clients connected to same server instance
- **Single-Node Deployment**: Current architecture uses local WebSocket broadcasting only (Redis pub/sub listener exists but not used for production traffic)
- **Horizontal Scaling**: Redis pub/sub infrastructure (`RedisSubscriber`) present for future multi-instance deployments

### Component Responsibilities

**Controllers:**
- `CollaborativeEditingController`: REST endpoints for document import, operation submission, recovery
- `DocumentEditorHub`: WebSocket hub for join/leave and broadcasting
- `FileController`: File management and listing

**Services:**
- `MinioService`: S3-compatible document storage (upload/download)
- `BackgroundService`: Two scheduled tasks:
  - `periodicAutosave()`: Every 3s, applies pending operations to MinIO using snapshot+CAS pattern
  - `cleanupInactiveRooms()`: Every 30s, removes Redis keys for rooms with no users or pending ops
- `RedisSubscriber`: Listens to Redis pub/sub for cross-server synchronization (infrastructure present, not actively used)
- `DataInitializationService`: Startup service to create bucket and seed sample document

**Configuration:**
- `RedisConfig`: Redis client and pub/sub listener setup
- `MinioConfig`: MinIO S3 client configuration
- `WebSocketConfig`: STOMP/SockJS endpoint configuration
- `SecurityConfig`: HTTP Basic authentication with in-memory users (bob, joe, alice)

**Models:**
- `ActionInfo` (Syncfusion class): Represents a single edit operation with OT metadata
- `SaveInfo`: Message format for background save queue
- `FilesPathInfo`: Document reference for import

## Syncfusion License

The Syncfusion license must be registered early in the application lifecycle. This happens in `SyncfusionCollaborativeEditingApplication` (main class). The license key should be set via environment variable or property.

## Configuration

Configuration is in `src/main/resources/application.yml`:

- `server.port`: Application port (default: 8098)
- `spring.data.redis`: Redis connection settings (host, port, timeout)
- `minio.*`: MinIO S3-compatible storage settings (endpoint, credentials, bucket, region)
- `collaborative.save-threshold`: Number of operations before triggering save (default: 150)
- `collaborative.autosave-interval-ms`: Background autosave interval in milliseconds (default: 3000)
- `collaborative.room-cleanup-interval-ms`: Interval for cleaning inactive room keys (default: 30000)

## Frontend

The frontend uses Vue 3 (CDN) with Syncfusion DocumentEditor component. Three main pages:

- `login.html`: HTTP Basic authentication (browser prompt)
- `files.html`: Document list/selection
- `editor.html`: Collaborative editor with WebSocket connection

The editor establishes WebSocket (STOMP/SockJS) and connects to Syncfusion collaborative editing service on load.

## Development Notes

- All Kotlin files must start with 2-line ABOUTME comments explaining the file's purpose
- Redis operations must use Lua scripts with CAS semantics for atomicity (see `CollaborativeEditingHelper`)
- The ZSET+HASH structure ensures gapless operation sequences - no version number is ever reused or skipped
- `__PENDING__` placeholders reserve version slots during transformation (prevents gaps during concurrent operations)
- CAS retry logic in `addOperationsToCache()` handles race conditions when multiple clients submit simultaneously
- Document operations flow through Syncfusion's `WordProcessorHelper` and `CollaborativeEditingHandler`
- MinIO provides local S3-compatible storage without AWS dependencies
- Room names are base64-encoded file names throughout the system