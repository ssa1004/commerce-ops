package io.minishop.inventory.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
@EnableConfigurationProperties(InventoryProperties::class)
class InventoryConfig {

    @Bean
    fun transactionTemplate(txManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(txManager)
}
