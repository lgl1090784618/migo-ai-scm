package cn.emigo.mossdoom;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class DatabaseConfig {
    static String secret(String path) {
        return secret(path,16);
    }
    static String secret(String path,int minimumLength) {
        try { String value=Files.readString(Path.of(path)).trim(); if(value.length()<minimumLength) throw new IllegalArgumentException(); return value; }
        catch(Exception e) { throw new IllegalStateException("Missing or invalid mounted credential: "+Path.of(path).getFileName()); }
    }
    private DataSource pool(String host,String database,String user,String file) {
        HikariConfig c=new HikariConfig(); c.setJdbcUrl("jdbc:postgresql://"+host+":5432/"+database);
        c.setUsername(user); c.setPassword(secret(file)); c.setMaximumPoolSize(5); c.setMinimumIdle(1);
        c.setConnectionTimeout(5000); c.setPoolName(database); c.addDataSourceProperty("ApplicationName","mossdoom-framework");
        return new HikariDataSource(c);
    }
    @Bean @Primary DataSource ingestionDataSource(@Value("${app.database-host}") String host,@Value("${MOSS_INGESTION_PASSWORD_FILE:/run/secrets/pg_ingestion}") String file){return pool(host,"moss_ingestion","moss_ingestion_app",file);}
    @Bean DataSource businessDataSource(@Value("${app.database-host}") String host,@Value("${MOSS_BUSINESS_PASSWORD_FILE:/run/secrets/pg_business}") String file){return pool(host,"moss_business","moss_business_app",file);}
    @Bean(initMethod="migrate") Flyway ingestionFlyway(@Qualifier("ingestionDataSource") DataSource ds){return Flyway.configure().dataSource(ds).locations("classpath:db/ingestion").baselineOnMigrate(true).baselineVersion("0").load();}
    @Bean(initMethod="migrate") Flyway businessFlyway(@Qualifier("businessDataSource") DataSource ds){return Flyway.configure().dataSource(ds).locations("classpath:db/business").baselineOnMigrate(true).baselineVersion("0").load();}
    @Bean @Primary JdbcTemplate ingestionJdbc(@Qualifier("ingestionDataSource") DataSource ds){return new JdbcTemplate(ds);}
    @Bean JdbcTemplate businessJdbc(@Qualifier("businessDataSource") DataSource ds){return new JdbcTemplate(ds);}
    @Bean @Primary TransactionTemplate ingestionTx(@Qualifier("ingestionDataSource") DataSource ds){return new TransactionTemplate(new DataSourceTransactionManager(ds));}
    @Bean TransactionTemplate businessTx(@Qualifier("businessDataSource") DataSource ds){return new TransactionTemplate(new DataSourceTransactionManager(ds));}
}
