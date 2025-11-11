// ABOUTME: JPA repository for File entity operations
// ABOUTME: Provides database access for fileName to UUID mapping
package ai.apps.syncfusioncollaborativeediting.repository

import ai.apps.syncfusioncollaborativeediting.entity.File
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface FileRepository : JpaRepository<File, UUID> {
    fun existsByFileName(fileName: String): Boolean
    fun findByFileName(fileName: String): File?
}
