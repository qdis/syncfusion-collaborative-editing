// ABOUTME: Utility functions for Base64 encoding/decoding room names and file names
// ABOUTME: Room names are base64-encoded file names throughout the system
package ai.apps.syncfusioncollaborativeediting.util

import java.util.Base64

object Base64Utils {
    fun decodeRoomNameToFileName(roomName: String): String =
        String(Base64.getDecoder().decode(roomName))

    fun encodeFileNameToRoomName(fileName: String): String =
        Base64.getEncoder().encodeToString(fileName.toByteArray())
}
