package clawx.backup.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One operator-configured native database dump/restore command pair. */
public final class ExternalDatabaseJob {

    private final String name;
    private final List<String> backupCommand;
    private final List<String> restoreCommand;
    private final Map<String, String> environment;
    private final int timeoutSeconds;

    public ExternalDatabaseJob(String name, List<String> backupCommand, List<String> restoreCommand,
                               Map<String, String> environment, int timeoutSeconds) {
        this.name = name;
        this.backupCommand = Collections.unmodifiableList(new ArrayList<>(backupCommand));
        this.restoreCommand = Collections.unmodifiableList(new ArrayList<>(restoreCommand));
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getName() { return name; }
    public List<String> getBackupCommand() { return backupCommand; }
    public List<String> getRestoreCommand() { return restoreCommand; }
    public Map<String, String> getEnvironment() { return environment; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
}
