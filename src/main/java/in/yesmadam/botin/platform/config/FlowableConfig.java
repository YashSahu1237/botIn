package in.yesmadam.botin.platform.config;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Pins the process engine to the PRIMARY datasource explicitly.
 *
 * Flowable would pick the @Primary bean anyway. Saying it out loud means that if
 * someone later moves @Primary, this fails loudly instead of quietly creating
 * 25 ACT_* tables somewhere they should never appear.
 */
@Configuration
public class FlowableConfig {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> pinEngineToPrimaryDataSource(
            @Qualifier("dataSource") DataSource primary) {
        return engineConfiguration -> engineConfiguration.setDataSource(primary);
    }
}
