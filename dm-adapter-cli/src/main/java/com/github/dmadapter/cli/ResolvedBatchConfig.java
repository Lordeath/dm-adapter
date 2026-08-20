package com.github.dmadapter.cli;

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
            Map<String, List<String>> methodKeyColumns,
            Map<String, List<List<String>>> methodConflictKeyGroups,
            Sql sql
    ) {
        Migration {
            tableKeyColumns = copyColumns(tableKeyColumns);
            methodKeyColumns = copyColumns(methodKeyColumns);
            LinkedHashMap<String, List<List<String>>> copiedGroups = new LinkedHashMap<>();
            if (methodConflictKeyGroups != null) {
                methodConflictKeyGroups.forEach((method, groups) -> copiedGroups.put(
                        method,
                        (groups == null ? List.<List<String>>of() : groups).stream()
                                .map(group -> List.copyOf(group == null ? List.of() : group))
                                .toList()
                ));
            }
            methodConflictKeyGroups = Collections.unmodifiableMap(copiedGroups);
        }

        private static Map<String, List<String>> copyColumns(Map<String, List<String>> source) {
            LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
            if (source != null) {
                source.forEach((key, columns) -> copied.put(
                        key,
                        List.copyOf(columns == null ? List.of() : columns)
                ));
            }
            return Collections.unmodifiableMap(copied);
        }
    }

    record Sql(
            BatchSqlMode mode,
            Path sourceDir,
            Path outputDir,
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
