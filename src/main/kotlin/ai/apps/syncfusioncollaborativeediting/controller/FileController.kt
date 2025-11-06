// ABOUTME: REST controller for file management operations
// ABOUTME: Provides endpoints for listing, uploading, and downloading files from MinIO
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.service.MinioService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.security.Principal

@RestController
@RequestMapping("/api/files")
class FileController(
    private val minioService: MinioService
) {

    @GetMapping("/currentUser")
    fun getCurrentUser(principal: Principal): Map<String, String> {
        return mapOf("username" to principal.name)
    }

    @GetMapping
    fun listFiles(): List<String> {
        return minioService.listDocuments()
    }

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, String>> {
        val fileName = file.originalFilename ?: return ResponseEntity.badRequest()
            .body(mapOf("error" to "File name is required"))

        minioService.uploadDocument(fileName, file.inputStream, file.size)

        return ResponseEntity.ok(mapOf(
            "message" to "File uploaded successfully",
            "fileName" to fileName
        ))
    }

    @GetMapping("/download/{fileName}")
    fun downloadFile(@PathVariable fileName: String): ResponseEntity<InputStreamResource> {
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
