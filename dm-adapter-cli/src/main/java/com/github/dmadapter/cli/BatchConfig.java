package com.github.dmadapter.cli;

import java.util.List;
import java.util.Map;

record BatchConfig(
        Integer schemaVersion,
        String workspaceDir,
        String reportDir,
        Integer reportRetentionDays,
        GitConfig git,
        MigrationConfig migrationDefaults,
        List<RepositoryConfig> repositories
) {
    record GitConfig(
            String username,
            String password,
            String authorName,
            String authorEmail,
            String commitMessage
    ) {
        @Override
        public String toString() {
            return "GitConfig[username=" + username + ", password=******, authorName=" + authorName
                    + ", authorEmail=" + authorEmail + ", commitMessage=" + commitMessage + "]";
        }
    }

    record RepositoryConfig(
            String name,
            String url,
            String branch,
            String projectSubdir,
            Boolean enabled,
            MigrationConfig migration
    ) {
    }

    record MigrationConfig(
            String dmDriver,
            String mapperDir,
            String rewriteConfig,
            Boolean sqlScriptsOnly,
            UpsertKeysConfig upsertKeys,
            SqlConfig sql
    ) {
    }

    record UpsertKeysConfig(
            Map<String, KeyColumnsConfig> tables,
            Map<String, MethodKeyColumnsConfig> methods
    ) {
    }

    record KeyColumnsConfig(
            List<String> keyColumns
    ) {
    }

    record MethodKeyColumnsConfig(
            List<String> keyColumns,
            List<List<String>> conflictKeyGroups
    ) {
    }

    record SqlConfig(
            BatchSqlMode mode,
            String sourceDir,
            String outputDir,
            List<String> preserveSql
    ) {
    }
}
