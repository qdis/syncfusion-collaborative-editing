package ai.apps.syncfusioncollaborativeediting.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

@Configuration
class GeneralConfig(val objectMapper: ObjectMapper) {



    @PostConstruct
    protected fun init() {
        objectMapper.findAndRegisterModules().registerKotlinModule();
    }
}
