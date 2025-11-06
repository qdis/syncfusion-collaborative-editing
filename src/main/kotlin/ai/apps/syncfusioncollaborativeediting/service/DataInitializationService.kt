// ABOUTME: Initialization service that runs on application startup
// ABOUTME: Creates MinIO bucket and seeds a sample document for testing
package ai.apps.syncfusioncollaborativeediting.service

import com.syncfusion.docio.FormatType
import com.syncfusion.docio.WordDocument
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

@Service
class DataInitializationService(
    private val minioService: MinioService
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(DataInitializationService::class.java)
    private val defaultDocumentName = "sample.docx"

    override fun run(args: ApplicationArguments?) {
        logger.info("Initializing storage and sample data...")

        try {
            // Ensure bucket exists
            minioService.ensureBucketExists()
            logger.info("MinIO bucket verified/created successfully")

            // Create and upload sample document if it doesn't exist
            if (!minioService.documentExists(defaultDocumentName)) {
                logger.info("Sample document not found. Creating and uploading...")
                createAndUploadSampleDocument()
                logger.info("Sample document created and uploaded successfully")
            } else {
                logger.info("Sample document already exists: $defaultDocumentName")
            }

        } catch (e: Exception) {
            logger.error("Error during data initialization", e)
            throw e
        }
    }

    private fun createAndUploadSampleDocument() {
        // Create a new Word document
        val document = WordDocument()

        try {
            // Add a section to the document
            val section = document.addSection()

            // Add a paragraph with title
            val titleParagraph = section.addParagraph()
            val titleText = titleParagraph.appendText("Welcome to Collaborative Editing")
            titleText.characterFormat.fontSize = 24f
            titleText.characterFormat.bold = true

            // Add empty line
            section.addParagraph()

            // Add introduction paragraph
            val introParagraph = section.addParagraph()
            introParagraph.appendText("This is a collaborative document editing demo using Syncfusion DocumentEditor. ")
            introParagraph.appendText("You can share the URL with others to edit this document together in real-time.")

            // Add empty line
            section.addParagraph()

            // Add features section
            val featuresTitleParagraph = section.addParagraph()
            val featuresTitle = featuresTitleParagraph.appendText("Features:")
            featuresTitle.characterFormat.fontSize = 16f
            featuresTitle.characterFormat.bold = true

            // Add feature list
            val features = listOf(
                "Real-time collaborative editing with multiple users",
                "Automatic conflict resolution using Operational Transformation",
                "Auto-save to persistent storage every 150 operations",
                "Redis-based caching for fast operation synchronization",
                "WebSocket communication for instant updates"
            )

            features.forEach { feature ->
                val featurePara = section.addParagraph()
                featurePara.appendText("• $feature")
                featurePara.paragraphFormat.leftIndent = 20f
            }

            // Add empty line
            section.addParagraph()

            // Add instructions
            val instructionsParagraph = section.addParagraph()
            val instructionsTitle = instructionsParagraph.appendText("Try it out:")
            instructionsTitle.characterFormat.fontSize = 16f
            instructionsTitle.characterFormat.bold = true

            section.addParagraph()

            val tryParagraph = section.addParagraph()
            tryParagraph.appendText("1. Copy the current page URL\n")
            tryParagraph.appendText("2. Open it in another browser window or share with a colleague\n")
            tryParagraph.appendText("3. Start typing in either window - watch the changes sync in real-time!\n")

            // Add empty line
            section.addParagraph()

            // Add footer note
            val footerParagraph = section.addParagraph()
            val footerText = footerParagraph.appendText("Feel free to edit this document - it will be saved automatically.")
            footerText.characterFormat.italic = true

            // Save document to byte array
            val outputStream = ByteArrayOutputStream()
            document.save(outputStream, FormatType.Docx)
            val documentBytes = outputStream.toByteArray()

            // Upload to MinIO
            minioService.uploadDocument(defaultDocumentName, documentBytes)

            outputStream.close()
        } finally {
            document.close()
        }
    }
}
