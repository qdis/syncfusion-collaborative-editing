// ABOUTME: JPA entity representing a document file with UUID identifier
// ABOUTME: Maps fileName to UUID for consistent identification across Redis, MinIO, and WebSocket
package ai.apps.syncfusioncollaborativeediting.entity

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "files")
data class File(
    @Id
    @Column(columnDefinition = "UUID")
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true, length = 255)
    val fileName: String = ""
)
