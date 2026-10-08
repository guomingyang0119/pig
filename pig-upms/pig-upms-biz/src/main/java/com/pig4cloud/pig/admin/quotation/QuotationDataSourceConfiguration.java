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
 * 配置报价模块独立的MySQL连接池、JDBC入口和事务管理器。
 * pig账号权限数据继续使用主数据源；报价事务通过具名管理器绑定到报价库，连接信息从环境变量读取。
 */
@Configuration(proxyBeanMethods = false)
public class QuotationDataSourceConfiguration {

	/** 显式保留pig主连接池：新增报价连接池后，Druid默认数据源自动配置会退让。 */
	@Bean(name = "dataSource", destroyMethod = "close")
	@Primary
	public DruidDataSourceWrapper systemDataSource() {
		return new DruidDataSourceWrapper();
	}

	/**
	 * 保留默认JDBC入口，使报价模块之外的系统业务继续使用pig主库。
	 */
	@Bean(name = "jdbcTemplate")
	@Primary
	public JdbcTemplate systemJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	/**
	 * 保留主事务管理器；普通系统业务使用默认事务，报价服务显式选择报价事务。
	 */
	@Bean(name = "transactionManager")
	@Primary
	public PlatformTransactionManager systemTransactionManager(@Qualifier("dataSource") DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	/**
	 * 创建报价业务连接池，默认连接QuotationSystem库，允许环境变量覆盖地址和账号。
	 * 连接池保持少量空闲连接，并用SELECT 1校验空闲连接可用性。
	 */
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

	/**
	 * 将报价仓储的SQL操作绑定到独立报价连接池。
	 */
	@Bean(name = "quotationJdbcTemplate")
	public JdbcTemplate quotationJdbcTemplate(@Qualifier("quotationDataSource") DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	/**
	 * 提供报价专用事务，使正文、计算结果、导出快照和审计能够一起提交或回滚。
	 */
	@Bean(name = "quotationTransactionManager")
	public PlatformTransactionManager quotationTransactionManager(
			@Qualifier("quotationDataSource") DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

}
