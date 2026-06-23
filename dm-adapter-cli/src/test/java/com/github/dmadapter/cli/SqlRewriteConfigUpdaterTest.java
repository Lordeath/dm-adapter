package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRewriteConfigUpdaterTest {
    @TempDir
    Path tempDir;

    private final SqlRewriteConfigUpdater updater = new SqlRewriteConfigUpdater();

    @Test
    void writesInferredMethodAndTableKeysFromPrimaryKeyMetadata() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateExtend",
                "user_extend",
                List.of("user_id", "key_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("user_id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("user_extend", metadata),
                true
        );

        assertThat(update.fileChange()).isPresent();
        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName()))
                .containsExactly("user_id");
        assertThat(Files.readString(config))
                .contains("\"user_extend\":")
                .contains("\"com.example.UserMapper.updateExtend\":")
                .contains("keyColumns: [\"user_id\"]")
                .doesNotContain("DM_DB_PASSWORD")
                .doesNotContain("DM_JDBC_URL");
    }

    @Test
    void preservesExistingMethodKeyColumnsWhenMetadataDiffers() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables:
                    "user_extend":
                      keyColumns: []
                  methods:
                    "com.example.UserMapper.updateExtend":
                      keyColumns: [tenant_id, user_account]
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfig(
                Map.of(),
                Map.of("com.example.UserMapper.updateExtend", List.of("tenant_id", "user_account"))
        );
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateExtend",
                "user_extend",
                List.of("id", "tenant_id", "user_account")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(candidate),
                Map.of("user_extend", metadata),
                true
        );

        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName()))
                .containsExactly("tenant_id", "user_account");
        assertThat(Files.readString(config))
                .contains("keyColumns: [\"tenant_id\", \"user_account\"]")
                .doesNotContain("keyColumns: [\"id\"]");
    }

    @Test
    void keepsEmptyKeyColumnsWhenMetadataIsUnavailable() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.insertIgnore",
                "role_perm",
                List.of("role_id", "perm_id")
        );

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of(),
                false
        );

        assertThat(update.warnings()).isEmpty();
        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName())).isEmpty();
        assertThat(Files.readString(config))
                .contains("\"role_perm\":")
                .contains("\"com.example.UserMapper.insertIgnore\":")
                .contains("keyColumns: []");
    }

    @Test
    void doesNotInferKeyColumnsWhenInsertColumnsCannotBeParsed() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.RecentlyUsedMapper.insertOrUpdate",
                "ns_recently_used",
                List.of()
        );
        TableKeyMetadata metadata = new TableKeyMetadata("ns_recently_used", List.of(
                new TableConstraint("PK_RECENTLY_USED", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("ns_recently_used", metadata),
                true
        );

        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName())).isEmpty();
        assertThat(update.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("Could not determine INSERT columns")
                        .contains("com.example.RecentlyUsedMapper.insertOrUpdate"));
        assertThat(Files.readString(config))
                .contains("\"ns_recently_used\":")
                .contains("\"com.example.RecentlyUsedMapper.insertOrUpdate\":")
                .contains("keyColumns: []");
    }
}
