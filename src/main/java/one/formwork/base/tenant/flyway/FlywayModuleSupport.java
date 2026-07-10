package one.formwork.base.tenant.flyway;

import org.flywaydb.core.Flyway;
import javax.sql.DataSource;

public class FlywayModuleSupport {

    public static Flyway create(DataSource dataSource, String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/" + schema)
                .load();
    }
}