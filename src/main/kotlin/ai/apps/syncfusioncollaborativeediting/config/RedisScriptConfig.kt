// ABOUTME: Redis Lua script configuration with typed bean definitions
// ABOUTME: Loads scripts from classpath and provides reusable RedisScript instances
package ai.apps.syncfusioncollaborativeediting.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.RedisScript

@Configuration
class RedisScriptConfig {

    @Bean
    fun initVersionCountersScript(): RedisScript<Long> {
        return RedisScript.of(
            ClassPathResource("redis/scripts/init-version-counters.lua"),
            Long::class.java
        )
    }

    @Bean
    fun appendOperationScript(): RedisScript<Long> {
        return RedisScript.of(
            ClassPathResource("redis/scripts/append-operation.lua"),
            Long::class.java
        )
    }

    @Bean
    fun getPendingOperationsScript(): RedisScript<List<*>> {
        return RedisScript.of(
            ClassPathResource("redis/scripts/get-pending-operations.lua"),
            List::class.java
        )
    }

    @Bean
    fun uiSaveCleanupScript(): RedisScript<String> {
        return RedisScript.of(
            ClassPathResource("redis/scripts/ui-save-cleanup.lua"),
            String::class.java
        )
    }

    @Bean
    fun ensureVersionMinScript(): RedisScript<Long> {
        return RedisScript.of(
            ClassPathResource("redis/scripts/ensure-version-min.lua"),
            Long::class.java
        )
    }
}
