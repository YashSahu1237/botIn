package in.yesmadam.botin.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * TWO DATASOURCES, AND THEY MUST NEVER MIX.
 *
 * PRIMARY — ours. The 7 BOTIn tables and Flowable's ~25 ACT_* tables. Everything
 * this service creates, migrates or writes happens here and only here. It is
 * marked @Primary, so Spring Boot's JPA, Flyway and Flowable auto-configuration
 * all bind to it without being told.
 *
 * UAT — read only. Fact providers read production-shaped tables through it.
 *
 * WHY IT CANNOT CREATE TABLES IN UAT, structurally rather than by discipline:
 *   - it is not @Primary, so no auto-configuration selects it
 *   - no EntityManagerFactory is built on it -> no Hibernate, no ddl-auto
 *   - Flyway is bound to the primary and has no second instance
 *   - Flowable receives the primary DataSource explicitly (FlowableConfig)
 *   - the only thing exposed on it is a JdbcTemplate, held by fact providers
 *
 * None of those components knows this datasource exists. There is no path
 * through which DDL could reach UAT.
 *
 * Belt and braces, and the one that holds if the above is ever wrong:
 * the UAT user must be SELECT-only by GRANT.
 */
@Configuration
public class DataSourceConfig {

    // ------------------------------------------------------------- PRIMARY

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    // ----------------------------------------------------------------- UAT

    @Bean(name = "uatDataSource")
    @ConditionalOnUatEnabled
    public DataSource uatDataSource(UatDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setMaximumPoolSize(props.getPoolSize());
        ds.setPoolName("botin-uat-readonly");

        // Asks the driver to refuse writes on this connection. It is the third
        // line of defence, not the first — the GRANT is what actually holds.
        ds.setReadOnly(true);
        return ds;
    }

    /**
     * The ONLY handle fact providers get on UAT. No repository, no entity
     * manager, no transaction manager — nothing that could emit DDL.
     */
    @Bean(name = "uatJdbcTemplate")
    @ConditionalOnUatEnabled
    public JdbcTemplate uatJdbcTemplate(@Qualifier("uatDataSource") DataSource uatDataSource) {
        JdbcTemplate t = new JdbcTemplate(uatDataSource);
        t.setQueryTimeout(5);   // a slow UAT must not hold a partner's request open
        return t;
    }
}
