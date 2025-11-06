// ABOUTME: Syncfusion DocumentEditor service endpoints
// ABOUTME: Handles standard operations like import, spell check, and system clipboard
package ai.apps.syncfusioncollaborativeediting.controller

import com.syncfusion.ej2.wordprocessor.FormatType
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
@RequestMapping("/api/wordeditor")
class DocumentEditorController {

    private val logger = LoggerFactory.getLogger(DocumentEditorController::class.java)

    @PostMapping("/Import")
    fun importDocument(@RequestParam("file") file: MultipartFile): String {
        return try {
            val stream = file.inputStream
            val formatType = getFormatType(file.originalFilename ?: "")
            // WordProcessorHelper.load returns the SFDT JSON directly
            WordProcessorHelper.load(stream, formatType)
        } catch (e: Exception) {
            logger.error("Error importing document", e)
            """{"sections":[{"blocks":[{"inlines":[{"text":"Error: ${e.message}"}]}]}]}"""
        }
    }

    @PostMapping("/SystemClipboard")
    fun systemClipboard(@RequestBody content: Map<String, Any>): String {
        return try {
            val sfdt = content["content"] as? String ?: ""
            // For clipboard operations, just return the SFDT content
            sfdt
        } catch (e: Exception) {
            logger.error("Error processing system clipboard", e)
            ""
        }
    }

    @PostMapping("/RestrictEditing")
    fun restrictEditing(@RequestBody content: Map<String, Any>): String {
        return try {
            val sfdt = content["content"] as? String ?: ""
            // Return the same content for basic editing
            sfdt
        } catch (e: Exception) {
            logger.error("Error processing restrict editing", e)
            """{"sections":[{"blocks":[{"inlines":[{"text":"Error: ${e.message}"}]}]}]}"""
        }
    }

    @PostMapping("/SpellCheck")
    fun spellCheck(@RequestBody content: Map<String, Any>): Map<String, Any> {
        // Basic spell check response - returns empty suggestions
        // In production, integrate with a spell check library
        return mapOf(
            "HasSpellingError" to false,
            "Suggestions" to emptyList<String>()
        )
    }

    @PostMapping("/SpellCheckByPage")
    fun spellCheckByPage(@RequestBody content: Map<String, Any>): Map<String, Any> {
        // Basic spell check response - returns empty suggestions
        return mapOf(
            "HasSpellingError" to false,
            "Suggestions" to emptyList<String>()
        )
    }

    private fun getFormatType(fileName: String): FormatType {
        return when (fileName.substringAfterLast('.').lowercase()) {
            "docx" -> FormatType.Docx
            "doc" -> FormatType.Doc
            "rtf" -> FormatType.Rtf
            "txt" -> FormatType.Txt
            "html" -> FormatType.Html
            else -> FormatType.Docx
        }
    }
}
