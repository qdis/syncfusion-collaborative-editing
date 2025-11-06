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

Redis serves three critical functions:

1. **Operation Queue**: Stores pending operations per room (`roomName` key)
2. **Version Management**: Atomic version increments via `roomName_version_info`
3. **Revision Tracking**: Tracks save cycles via `roomName_revision_info`

**Cache Lifecycle:**
- Operations accumulate in Redis up to `SAVE_THRESHOLD` (150 ops)
- At 2× threshold (300 ops), oldest 150 ops move to background save queue
- `BackgroundService` (runs every 5s) applies queued ops to source document in MinIO
- After successful save, processed operations are cleared from Redis

### Lua Scripts for Atomicity

All Redis operations use Lua scripts (`CollaborativeEditingHelper`) to guarantee atomicity:

- `INSERT_SCRIPT`: Atomically increment version, append operation, check cache limit
- `UPDATE_RECORD`: Update operation after transformation
- `EFFECTIVE_PENDING_OPERATIONS`: Fetch operations since client's last known version
- `PENDING_OPERATIONS`: Fetch all pending operations including those being processed

These scripts prevent race conditions when multiple clients send operations simultaneously.

### WebSocket vs Redis Pub/Sub

- **WebSocket** (via `DocumentEditorHub`): Broadcasts to clients connected to same server instance
- **Redis pub/sub** (via `RedisSubscriber`): Synchronizes across multiple server instances (horizontal scaling)

Both channels receive the same messages to ensure all clients stay in sync.

### Component Responsibilities

**Controllers:**
- `CollaborativeEditingController`: REST endpoints for document import, operation submission, recovery
- `DocumentEditorHub`: WebSocket hub for join/leave and broadcasting
- `FileController`: File management and listing

**Services:**
- `MinioService`: S3-compatible document storage (upload/download)
- `BackgroundService`: Scheduled task (5s interval) to persist operations to MinIO
- `RedisSubscriber`: Listens to Redis pub/sub for cross-server synchronization
- `DataInitializationService`: Startup service to create bucket and seed sample document

**Configuration:**
- `RedisConfig`: Redis client and pub/sub listener setup
- `MinioConfig`: MinIO S3 client configuration
- `WebSocketConfig`: STOMP/SockJS endpoint configuration
- `SecurityConfig`: Form-based authentication with in-memory users (bob, joe, alice)

**Models:**
- `ActionInfo` (Syncfusion class): Represents a single edit operation with OT metadata
- `SaveInfo`: Message format for background save queue
- `FilesPathInfo`: Document reference for import

## Syncfusion License

The Syncfusion license must be registered early in the application lifecycle. This happens in `SyncfusionCollaborativeEditingApplication` (main class). The license key should be set via environment variable or property.

## Configuration

Configuration is in `src/main/resources/application.yml`:

- `server.port`: Application port (default: 8098)
- `spring.data.redis`: Redis connection settings
- `minio.*`: MinIO S3-compatible storage settings
- `collaborative.save-threshold`: Number of operations before triggering save (default: 150)
- `collaborative.redis-pub-sub-channel`: Redis channel name for cross-server sync

## Frontend

The frontend uses Vue 3 (CDN) with Syncfusion DocumentEditor component. Three main pages:

- `login.html`: Form-based authentication
- `files.html`: Document list/selection
- `editor.html`: Collaborative editor with WebSocket connection

The editor establishes both WebSocket and Syncfusion collaborative editing connections on load.

## Development Notes

- All Kotlin files must start with 2-line ABOUTME comments explaining the file's purpose
- Redis operations must use Lua scripts for atomicity (see `CollaborativeEditingHelper`)
- Document operations flow through Syncfusion's `WordProcessorHelper` and `CollaborativeEditingHandler`
- MinIO provides local S3-compatible storage without AWS dependencies