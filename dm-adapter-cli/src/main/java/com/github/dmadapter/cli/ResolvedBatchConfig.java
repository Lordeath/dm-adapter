package com.github.dmadapter.cli;

import com.github.dmadapter.core.TargetLengthSemantics;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ResolvedBatchConfig(
        Path configPath,
        Path workspaceDir,
        Path reportDir,
        int reportRetentionDays,
        Credentials credentials,
        GitIdentity gitIdentity,
        List<Repository> repositories
) {
    record Credentials(String username, String password) {
        Credentials {
            username = value(username);
            password = value(password);
        }

        boolean configured() {
            return !username.isBlank() && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=******]";
        }
    }

    record GitIdentity(String authorName, String authorEmail, String commitMessage) {
    }

    record Repository(
            String name,
            String url,
            String branch,
            Path projectSubdir,
            Migration migration
    ) {
    }

    record Migration(
            String dmDriver,
            Path mapperDir,
            Path rewriteConfig,
            boolean sqlScriptsOnly,
            Map<String, List<String>> tableKeyColumns,
            Sql sql
    ) {
        Migration {
            LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
            if (tableKeyColumns != null) {
                tableKeyColumns.forEach((table, columns) -> copied.put(
                        table,
                        List.copyOf(columns == null ? List.of() : columns)
                ));
            }
            tableKeyColumns = Collections.unmodifiableMap(copied);
        }
    }

    record Sql(
            BatchSqlMode mode,
            Path sourceDir,
            Path outputDir,
            TargetLengthSemantics targetLengthSemantics,
            List<Path> preserveSql
    ) {
        Sql {
            preserveSql = List.copyOf(preserveSql == null ? List.of() : preserveSql);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
