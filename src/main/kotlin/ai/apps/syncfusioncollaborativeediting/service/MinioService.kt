// ABOUTME: Service for managing document storage in MinIO
// ABOUTME: Handles document upload, download, and bucket operations
package ai.apps.syncfusioncollaborativeediting.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.InputStream

@Service
class MinioService(
    private val s3Client: S3Client
) {

    @Value("\${minio.bucket-name}")
    private lateinit var bucketName: String

    fun ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build())
        } catch (e: NoSuchBucketException) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build())
        }
    }

    fun uploadDocument(fileName: String, content: InputStream, contentLength: Long) {
        ensureBucketExists()

        val putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(fileName)
            .build()

        s3Client.putObject(putRequest, RequestBody.fromInputStream(content, contentLength))
    }

    fun uploadDocument(fileName: String, content: ByteArray) {
        ensureBucketExists()

        val putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(fileName)
            .build()

        s3Client.putObject(putRequest, RequestBody.fromBytes(content))
    }

    fun downloadDocument(fileName: String): ResponseInputStream<GetObjectResponse> {
        val getRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(fileName)
            .build()

        return s3Client.getObject(getRequest)
    }

    fun documentExists(fileName: String): Boolean {
        return try {
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build())
            true
        } catch (e: NoSuchKeyException) {
            false
        }
    }

    fun listDocuments(): List<String> {
        ensureBucketExists()

        val listRequest = ListObjectsV2Request.builder()
            .bucket(bucketName)
            .build()

        val response = s3Client.listObjectsV2(listRequest)
        return response.contents().map { it.key() }
    }
}
