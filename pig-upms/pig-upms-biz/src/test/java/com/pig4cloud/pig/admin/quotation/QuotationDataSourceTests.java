package com.pig4cloud.pig.admin.quotation;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class QuotationDataSourceTests {

	@Test
	void systemAndQuotationUseSeparateDatabasesAndTransactionManagers() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
					DruidDataSourceAutoConfigure.class, DataSourceAutoConfiguration.class,
					DataSourceTransactionManagerAutoConfiguration.class))
			.withUserConfiguration(QuotationDataSourceConfiguration.class)
			.withPropertyValues("spring.datasource.druid.url=jdbc:h2:mem:pig_datasource_test",
					"spring.datasource.druid.driver-class-name=org.h2.Driver", "spring.datasource.druid.username=sa",
					"spring.datasource.druid.password=", "QUOTATION_DB_URL=jdbc:h2:mem:quotation_datasource_test",
					"QUOTATION_DB_USERNAME=sa", "QUOTATION_DB_PASSWORD=")
			.run(context -> {
				assertThat(context).hasNotFailed().hasBean("dataSource");
				DataSource system = context.getBean(DataSource.class);
				DruidDataSource quotation = context.getBean("quotationDataSource", DruidDataSource.class);
				// Production uses MySQL; the test substitutes an isolated in-memory
				// driver.
				quotation.setDriverClassName("org.h2.Driver");
				assertThat(system).isNotSameAs(quotation);
				JdbcTemplate systemJdbc = context.getBean(JdbcTemplate.class);
				JdbcTemplate quotationJdbc = context.getBean("quotationJdbcTemplate", JdbcTemplate.class);
				assertThat(systemJdbc.getDataSource()).isSameAs(system);
				assertThat(quotationJdbc.getDataSource()).isSameAs(quotation);
				systemJdbc.execute("CREATE TABLE isolated_probe (id INT)");
				quotationJdbc.execute("CREATE TABLE isolated_probe (id INT)");
				PlatformTransactionManager systemManager = context.getBean(PlatformTransactionManager.class);
				PlatformTransactionManager quotationManager = context.getBean("quotationTransactionManager",
						PlatformTransactionManager.class);
				assertThat(((DataSourceTransactionManager) systemManager).getDataSource()).isSameAs(system);
				assertThat(((DataSourceTransactionManager) quotationManager).getDataSource()).isSameAs(quotation);
				new TransactionTemplate(systemManager).execute(status -> {
					systemJdbc.update("INSERT INTO isolated_probe VALUES (1)");
					quotationJdbc.update("INSERT INTO isolated_probe VALUES (2)");
					status.setRollbackOnly();
					return null;
				});
				assertThat(systemJdbc.queryForObject("SELECT COUNT(*) FROM isolated_probe", Integer.class)).isZero();
				assertThat(quotationJdbc.queryForList("SELECT id FROM isolated_probe", Integer.class))
					.containsExactly(2);
				new TransactionTemplate(quotationManager).execute(status -> {
					quotationJdbc.update("INSERT INTO isolated_probe VALUES (3)");
					status.setRollbackOnly();
					return null;
				});
				assertThat(quotationJdbc.queryForList("SELECT id FROM isolated_probe", Integer.class))
					.containsExactly(2);
			});
	}

}
