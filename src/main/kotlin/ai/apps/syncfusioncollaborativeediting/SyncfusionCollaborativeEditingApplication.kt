// ABOUTME: Spring Boot application entry point for collaborative document editing
// ABOUTME: Configures and launches the application with scheduling support
package ai.apps.syncfusioncollaborativeediting

import com.syncfusion.licensing.SyncfusionLicenseProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SyncfusionCollaborativeEditingApplication

fun main(args: Array<String>) {
    SyncfusionLicenseProvider.registerLicense("GTIlMmhgYn1ifWJkaGBifGJhfGpqampzYWBpZmppZmpoJzo+PBM3NiUjPzI9J30hPGhgYg==")
    runApplication<SyncfusionCollaborativeEditingApplication>(*args)
}
