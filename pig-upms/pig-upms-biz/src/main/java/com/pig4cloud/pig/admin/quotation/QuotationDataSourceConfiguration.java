package com.pig4cloud.pig.admin.quotation;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceWrapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Keeps quotation data in its own MySQL database instead of the pig system database.
 * Credentials are supplied through environment variables and are never committed.
 */
@Configuration(proxyBeanMethods = false)
public class QuotationDataSourceConfiguration {

	/** The additional quotation pool makes Druid's default datasource back off. */
	@Bean(name = "dataSource", destroyMethod = "close")
	@Primary
	public DruidDataSourceWrapper systemDataSource() {
		return new DruidDataSourceWrapper();
	}

	@Bean(name = "jdbcTemplate")
	@Primary
	public JdbcTemplate systemJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean(name = "transactionManager")
	@Primary
	public PlatformTransactionManager systemTransactionManager(@Qualifier("dataSource") DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	@Bean(name = "quotationDataSource", destroyMethod = "close")
	public DruidDataSource quotationDataSource(
			@Value("${QUOTATION_DB_URL:jdbc:mysql://127.0.0.1:3306/QuotationSystem?characterEncoding=utf8&useUnicode=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true}") String url,
			@Value("${QUOTATION_DB_USERNAME:root}") String username,
			@Value("${QUOTATION_DB_PASSWORD:}") String password) {
		DruidDataSource dataSource = new DruidDataSource();
		dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
		dataSource.setUrl(url);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		dataSource.setInitialSize(1);
		dataSource.setMinIdle(1);
		dataSource.setMaxActive(10);
		dataSource.setValidationQuery("SELECT 1");
		dataSource.setTestWhileIdle(true);
		return dataSource;
	}

	@Bean(name = "quotationJdbcTemplate")
	public JdbcTemplate quotationJdbcTemplate(@Qualifier("quotationDataSource") DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean(name = "quotationTransactionManager")
	public PlatformTransactionManager quotationTransactionManager(
			@Qualifier("quotationDataSource") DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

}
