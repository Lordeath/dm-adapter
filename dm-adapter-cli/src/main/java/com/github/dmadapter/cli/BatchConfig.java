package com.github.dmadapter.cli;

import com.github.dmadapter.core.TargetLengthSemantics;

import java.util.List;

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
            SqlConfig sql
    ) {
    }

    record SqlConfig(
            BatchSqlMode mode,
            String sourceDir,
            String outputDir,
            TargetLengthSemantics targetLengthSemantics,
            List<String> preserveSql
    ) {
    }
}
