// ABOUTME: Spring Boot application entry point for collaborative document editing
// ABOUTME: Configures and launches the application with scheduling support
package ai.apps.syncfusioncollaborativeediting

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SyncfusionCollaborativeEditingApplication

fun main(args: Array<String>) {
    runApplication<SyncfusionCollaborativeEditingApplication>(*args)
}
