// ABOUTME: REST controller for file management operations
// ABOUTME: Provides endpoints for listing, uploading, and downloading files with UUID identifiers
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.entity.File
import ai.apps.syncfusioncollaborativeediting.model.ApiResponse
import ai.apps.syncfusioncollaborativeediting.model.FileInfo
import ai.apps.syncfusioncollaborativeediting.model.FileUploadResponse
import ai.apps.syncfusioncollaborativeediting.model.UserInfoResponse
import ai.apps.syncfusioncollaborativeediting.repository.FileRepository
import ai.apps.syncfusioncollaborativeediting.service.MinioService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api/files")
class FileController(
    private val minioService: MinioService,
    private val fileRepository: FileRepository
) {

    @GetMapping("/currentUser")
    fun getCurrentUser(principal: Principal): UserInfoResponse {
        return UserInfoResponse(username = principal.name)
    }

    @GetMapping
    fun listFiles(): List<FileInfo> {
        return fileRepository.findAll().map { file ->
            FileInfo(fileId = file.id, fileName = file.fileName)
        }
    }

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<*> {
        val fileName = file.originalFilename ?: return ResponseEntity.badRequest()
            .body(ApiResponse<Unit>(error = "File name is required"))

        // Check if file with same name already exists
        if (fileRepository.existsByFileName(fileName)) {
            return ResponseEntity.badRequest()
                .body(ApiResponse<Unit>(error = "File with name '$fileName' already exists"))
        }

        // Upload to MinIO
        minioService.uploadDocument(fileName, file.inputStream, file.size)

        // Create database record with generated UUID
        val fileRecord = fileRepository.save(File(fileName = fileName))

        return ResponseEntity.ok(
            FileUploadResponse(
                message = "File uploaded successfully",
                fileName = fileName,
                fileId = fileRecord.id
            )
        )
    }

    @GetMapping("/download/{fileId}")
    fun downloadFile(@PathVariable fileId: UUID): ResponseEntity<InputStreamResource> {
        val fileRecord = fileRepository.findById(fileId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val fileName = fileRecord.fileName

        if (!minioService.documentExists(fileName)) {
            return ResponseEntity.notFound().build()
        }

        val fileStream = minioService.downloadDocument(fileName)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(InputStreamResource(fileStream))
    }
}
