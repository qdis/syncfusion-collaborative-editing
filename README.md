# Syncfusion Collaborative Editing Demo

A real-time collaborative document editing application built with Spring Boot, Kotlin, Redis, MinIO, and Vue.js, using Syncfusion DocumentEditor.

## Architecture

- **Backend**: Spring Boot 3.5.7 with Kotlin
- **Storage**: MinIO (S3-compatible)
- **Cache**: Redis with pub/sub for real-time synchronization
- **WebSocket**: STOMP over SockJS for bidirectional communication
- **Frontend**: Vue 3 (CDN) with Syncfusion DocumentEditor
- **Conflict Resolution**: Operational Transformation via Syncfusion

## Prerequisites

- Java 21
- Docker & Docker Compose

## Setup & Running

### 1. Start Infrastructure Services

```bash
docker-compose up -d
```

This starts:
- Redis on port 6379
- MinIO on port 9000 (API) and 9001 (Console)

### 2. Build and Run the Application

```bash
./gradlew bootRun
```

The application will start on http://localhost:8098

**On first startup, the application will automatically:**
- Create the MinIO bucket `syncfusion-documents`
- Generate and upload a sample Word document (`sample.docx`)
- This happens via the `DataInitializationService` component

### 3. Test Collaborative Editing

1. Open http://localhost:8098 in your browser
2. Log in with one of the demo users:
   - Username: `bob`, Password: `bob`
   - Username: `joe`, Password: `joe`
   - Username: `alice`, Password: `alice`
3. Select a document from the file list
4. Open the same document in another browser window (use a different user account or incognito mode)
5. Start editing in one window - you should see changes appear in real-time in the other window!

## How It Works

### Real-Time Synchronization

1. **Edit Operation**: User makes a change → `contentChange` event fires
2. **Send to Server**: Operation sent to `/api/collaborativeediting/UpdateAction`
3. **Redis Cache**: Operation stored in Redis with atomic version increment (via Lua script)
4. **Conflict Resolution**: If concurrent edits exist, Operational Transformation adjusts positions
5. **Broadcast**: Operation published to Redis pub/sub and WebSocket topic
6. **Apply Remote**: Other clients receive and apply the transformed operation

### Auto-Save Mechanism

- Operations accumulate in Redis (threshold: 150 operations)
- When cache reaches 300 operations (2× threshold):
  - Oldest 150 operations queued for background save
  - Background service (runs every 5 seconds) applies them to source document
  - Updated document saved back to MinIO
  - Processed operations cleared from Redis

### Key Components

#### Backend

- **CollaborativeEditingController**: REST endpoints for document operations
- **DocumentEditorHub**: WebSocket message handler for room join/leave
- **FileController**: Document listing and management
- **RedisSubscriber**: Redis pub/sub listener for cross-server sync
- **BackgroundService**: Scheduled task for persisting operations to storage
- **MinioService**: S3-compatible document storage operations
- **DataInitializationService**: Creates bucket and seeds sample document on startup
- **CollaborativeEditingHelper**: Lua scripts for atomic Redis operations
- **SecurityConfig**: Form-based authentication with in-memory users

#### Frontend

- **login.html**: User authentication page
- **files.html**: Document selection interface
- **editor.html**: Collaborative editor with real-time sync
- **Vue 3 App**: Single-page application with reactive UI
- **Syncfusion DocumentEditor**: Rich text editor with collaborative editing support
- **SockJS + STOMP**: WebSocket connection for real-time updates

## Configuration

Edit `src/main/resources/application.yml` to customize:

```yaml
server:
  port: 8098

spring:
  data:
    redis:
      host: localhost
      port: 6379

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket-name: syncfusion-documents
  region: us-east-1

collaborative:
  save-threshold: 150
  redis-pub-sub-channel: collaborativeediting
```

## API Endpoints

### Authentication

- `GET /login.html` - Login page
- `POST /login` - Authentication endpoint
- `GET /logout` - Logout

### REST Endpoints

- `GET /api/files` - List available documents
- `POST /api/collaborativeediting/ImportFile` - Load document with pending operations
- `POST /api/collaborativeediting/UpdateAction` - Submit edit operation
- `POST /api/collaborativeediting/GetActionsFromServer` - Get missed operations (recovery)

### WebSocket Endpoints

- `CONNECT /ws` - SockJS connection endpoint
- `SUBSCRIBE /topic/public/{roomName}` - Subscribe to room updates
- `SEND /app/join/{documentName}` - Join collaborative session

## Testing Scenarios

### Basic Collaboration
1. Log in as `bob` in one browser window and `joe` in another
2. Open the same document in both windows
3. Type in one window → text appears in both
4. Type simultaneously in both → both edits preserved (OT resolution)

### Auto-Save
1. Make 150+ edits to trigger auto-save
2. Check MinIO console at http://localhost:9001 - document should be updated
3. Reload page - edits should persist

### Multi-User
1. Log in as `bob`, `joe`, and `alice` in three different browser windows
2. Open the same document in all windows
3. Verify all users see each other's edits in real-time
4. Check the user presence indicator in the editor

### Conflict Resolution
1. Place cursor at same position in two windows
2. Type different text simultaneously
3. Verify both insertions are preserved (OT handles position adjustment)

## Troubleshooting

### Login Fails
- Verify you're using the correct credentials: bob/bob, joe/joe, or alice/alice
- Check application logs for authentication errors

### Document Load Fails
- Verify `sample.docx` exists in MinIO bucket `syncfusion-documents`
- Check MinIO console at http://localhost:9001 (credentials: minioadmin/minioadmin)
- Verify DataInitializationService ran successfully on startup

### WebSocket Connection Fails
- Check browser console for errors
- Verify Redis is running: `docker ps | grep redis`
- Check application logs for connection errors
- Ensure you're authenticated before accessing the editor

### Edits Not Syncing
- Verify Redis pub/sub channel configuration matches in both subscriber and publisher
- Check browser console for WebSocket messages
- Verify both windows are viewing the same document
- Check that both users are authenticated

## Development Notes

- Lua scripts ensure atomic Redis operations for version management
- Spring Data Redis used instead of Jedis for cleaner Kotlin integration
- MinIO provides S3-compatible storage without AWS costs
- Vue 3 CDN approach avoids build step complexity

## License

Demo application for educational purposes.
