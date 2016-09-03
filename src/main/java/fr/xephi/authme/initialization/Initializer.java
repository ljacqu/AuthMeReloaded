package fr.xephi.authme.initialization;

import fr.xephi.authme.AuthMe;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.cache.auth.PlayerAuth;
import fr.xephi.authme.datasource.CacheDataSource;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.datasource.DataSourceType;
import fr.xephi.authme.datasource.FlatFile;
import fr.xephi.authme.datasource.MySQL;
import fr.xephi.authme.datasource.SQLite;
import fr.xephi.authme.output.ConsoleFilter;
import fr.xephi.authme.output.Log4JFilter;
import fr.xephi.authme.output.LogLevel;
import fr.xephi.authme.output.MessageKey;
import fr.xephi.authme.output.Messages;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.SettingsMigrationService;
import fr.xephi.authme.settings.properties.DatabaseSettings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.settings.properties.SettingsFieldRetriever;
import fr.xephi.authme.settings.propertymap.PropertyMap;
import fr.xephi.authme.util.BukkitService;
import fr.xephi.authme.util.FileUtils;
import fr.xephi.authme.util.MigrationService;
import fr.xephi.authme.util.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static fr.xephi.authme.settings.properties.EmailSettings.RECALL_PLAYERS;
import static fr.xephi.authme.util.BukkitService.TICKS_PER_MINUTE;

/**
 * Initializes various services.
 */
public class Initializer {

    private static final String FLATFILE_FILENAME = "auths.db";
    private static final int SQLITE_MAX_SIZE = 4000;

    private AuthMe authMe;
    private BukkitService bukkitService;

    public Initializer(AuthMe authMe, BukkitService bukkitService) {
        this.authMe = authMe;
        this.bukkitService = bukkitService;
    }

    /**
     * Loads the plugin's settings.
     *
     * @return The settings instance, or null if it could not be constructed
     */
    public Settings createSettings() throws Exception {
        File configFile = new File(authMe.getDataFolder(), "config.yml");
        PropertyMap properties = SettingsFieldRetriever.getAllPropertyFields();
        SettingsMigrationService migrationService = new SettingsMigrationService();
        if (FileUtils.copyFileFromResource(configFile, "config.yml")) {
            return new Settings(configFile, authMe.getDataFolder(), properties, migrationService);
        }
        throw new Exception("Could not copy config.yml from JAR to plugin folder");
    }

    /**
     * Sets up the data source.
     *
     * @param settings the settings
     * @throws ClassNotFoundException if no driver could be found for the datasource
     * @throws SQLException           when initialization of a SQL datasource failed
     * @throws IOException            if flat file cannot be read
     */
    public DataSource setupDatabase(Settings settings) throws ClassNotFoundException, SQLException, IOException {
        DataSourceType dataSourceType = settings.getProperty(DatabaseSettings.BACKEND);
        DataSource dataSource;
        switch (dataSourceType) {
            case FILE:
                File source = new File(authMe.getDataFolder(), FLATFILE_FILENAME);
                dataSource = new FlatFile(source);
                break;
            case MYSQL:
                dataSource = new MySQL(settings);
                break;
            case SQLITE:
                dataSource = new SQLite(settings);
                break;
            default:
                throw new UnsupportedOperationException("Unknown data source type '" + dataSourceType + "'");
        }

        DataSource convertedSource = MigrationService.convertFlatfileToSqlite(settings, dataSource);
        dataSource = convertedSource == null ? dataSource : convertedSource;

        if (settings.getProperty(DatabaseSettings.USE_CACHING)) {
            dataSource = new CacheDataSource(dataSource);
        }
        if (DataSourceType.SQLITE.equals(dataSourceType)) {
            checkDataSourceSize(dataSource);
        }
        return proxyDatasource(dataSource, settings);
    }

    private DataSource proxyDatasource(final DataSource dataSource, final Settings settings) {
        if (!LogLevel.DEBUG.includes(settings.getProperty(PluginSettings.LOG_LEVEL))) {
            return dataSource;
        }
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
                long start = System.currentTimeMillis();
                Object result = method.invoke(dataSource, objects);
                long diff = System.currentTimeMillis() - start;
                List<Object> args = objects == null ? null : Arrays.asList(objects);
                ConsoleLogger.debug("Call DataSource#" + method.getName() + " took " + diff + "ms with "
                    + args + " returning " + result);
                return result;
            }
        };
        return (DataSource) Proxy.newProxyInstance(
            AuthMe.class.getClassLoader(), new Class<?>[]{DataSource.class}, handler);
    }

    private void checkDataSourceSize(final DataSource dataSource) {
        bukkitService.runTaskAsynchronously(new Runnable() {
            @Override
            public void run() {
                int accounts = dataSource.getAccountsRegistered();
                if (accounts >= SQLITE_MAX_SIZE) {
                    ConsoleLogger.warning("YOU'RE USING THE SQLITE DATABASE WITH "
                        + accounts + "+ ACCOUNTS; FOR BETTER PERFORMANCE, PLEASE UPGRADE TO MYSQL!!");
                }
            }
        });
    }

    /**
     * Sets up the console filter if enabled.
     *
     * @param settings the settings
     * @param logger the plugin logger
     */
    public void setupConsoleFilter(Settings settings, Logger logger) {
        if (!settings.getProperty(SecuritySettings.REMOVE_PASSWORD_FROM_CONSOLE)) {
            return;
        }
        // Try to set the log4j filter
        try {
            Class.forName("org.apache.logging.log4j.core.filter.AbstractFilter");
            setLog4JFilter();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // log4j is not available
            ConsoleLogger.info("You're using Minecraft 1.6.x or older, Log4J support will be disabled");
            ConsoleFilter filter = new ConsoleFilter();
            logger.setFilter(filter);
            Bukkit.getLogger().setFilter(filter);
            Logger.getLogger("Minecraft").setFilter(filter);
        }
    }

    // Set the console filter to remove the passwords
    private static void setLog4JFilter() {
        org.apache.logging.log4j.core.Logger logger;
        logger = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        logger.addFilter(new Log4JFilter());
    }

    public void scheduleRecallEmailTask(Settings settings, final DataSource dataSource, final Messages messages) {
        if (!settings.getProperty(RECALL_PLAYERS)) {
            return;
        }
        bukkitService.runTaskTimerAsynchronously(new Runnable() {
            @Override
            public void run() {
                for (PlayerAuth auth : dataSource.getLoggedPlayers()) {
                    String email = auth.getEmail();
                    if (StringUtils.isEmpty(email) || "your@email.com".equalsIgnoreCase(email)) {
                        Player player = bukkitService.getPlayerExact(auth.getRealName());
                        if (player != null) {
                            messages.send(player, MessageKey.ADD_EMAIL_MESSAGE);
                        }
                    }
                }
            }
        }, 1, TICKS_PER_MINUTE * settings.getProperty(EmailSettings.DELAY_RECALL));
    }
}
