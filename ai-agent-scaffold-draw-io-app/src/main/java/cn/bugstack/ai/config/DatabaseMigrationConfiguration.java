package cn.bugstack.ai.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class DatabaseMigrationConfiguration {
    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name="spring.flyway.enabled",havingValue="true",matchIfMissing=true)
    public Flyway agentPlatformFlyway(DataSource dataSource){
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }
}
