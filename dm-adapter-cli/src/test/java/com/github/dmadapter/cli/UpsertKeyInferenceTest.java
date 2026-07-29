package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpsertKeyInferenceTest {
    private final UpsertKeyInference inference = new UpsertKeyInference();

    @Test
    void prefersPrimaryKeyWhenPresentInInsertColumns() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.upsert",
                "user_extend",
                List.of("user_id", "key_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("user_id")),
                new TableConstraint("UK_USER_EXTEND_KEY", TableConstraint.ConstraintType.UNIQUE_KEY, List.of("key_name"))
        ));

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isTrue();
        assertThat(result.keyColumns()).containsExactly("user_id");
        assertThat(result.source()).contains("primary key");
    }

    @Test
    void usesSingleUniqueKeyWhenPrimaryKeyIsNotInserted() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.upsert",
                "user_extend",
                List.of("tenant_id", "user_account", "key_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("id")),
                new TableConstraint("UK_USER_ACCOUNT", TableConstraint.ConstraintType.UNIQUE_KEY, List.of("tenant_id", "user_account"))
        ));

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isTrue();
        assertThat(result.keyColumns()).containsExactly("tenant_id", "user_account");
        assertThat(result.source()).contains("unique key");
    }

    @Test
    void keepsInsertColumnSpellingWhenConstraintCaseDiffers() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.PrecinctMapper.updateExtend",
                "ns_project_management_extend",
                List.of("foreignKeyId")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("ns_project_management_extend", List.of(
                new TableConstraint(
                        "ns_project_management_extend_foreignerKeyId_Index",
                        TableConstraint.ConstraintType.UNIQUE_KEY,
                        List.of("foreignkeyId")
                )
        ));

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isTrue();
        assertThat(result.keyColumns()).containsExactly("foreignKeyId");
    }

    @Test
    void leavesAmbiguousUniqueKeysUnresolved() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.upsert",
                "user_extend",
                List.of("tenant_id", "user_account", "phone")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("UK_USER_ACCOUNT", TableConstraint.ConstraintType.UNIQUE_KEY, List.of("tenant_id", "user_account")),
                new TableConstraint("UK_USER_PHONE", TableConstraint.ConstraintType.UNIQUE_KEY, List.of("tenant_id", "phone"))
        ));

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isFalse();
        assertThat(result.resolutionCode())
                .isEqualTo(UpsertKeyInference.RESOLUTION_MANUAL_KEY_COLUMNS_REQUIRED);
        assertThat(result.reason()).contains("Multiple unique keys");
    }

    @Test
    void marksMissingConflictConstraintAsOriginalSqlIssue() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.BankFileMapper.insertIgnore",
                "ns_bank_file",
                List.of("file_id", "file_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("ns_bank_file", List.of(
                new TableConstraint(
                        "PK_NS_BANK_FILE",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )
        ));

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isFalse();
        assertThat(result.resolutionCode())
                .isEqualTo(UpsertKeyInference.RESOLUTION_ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY);
        assertThat(result.reason()).contains("No primary key or unique key columns are fully present");
    }

    @Test
    void convertsInsertIgnoreToPlainInsertWhenOnlyUninsertedKeyIsGenerated() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.BankFileMapper.insertIgnore",
                "ns_bank_file",
                List.of("file_id", "file_name"),
                RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
        );
        TableKeyMetadata metadata = new TableKeyMetadata(
                "ns_bank_file",
                List.of(new TableConstraint(
                        "PK_NS_BANK_FILE",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )),
                true,
                java.util.Set.of("ID")
        );

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.inferred()).isFalse();
        assertThat(result.resolutionCode())
                .isEqualTo(UpsertKeyInference.RESOLUTION_INSERT_IGNORE_AS_PLAIN_INSERT);
        assertThat(result.reason()).contains("plain INSERT");
    }

    @Test
    void keepsInsertIgnoreUnresolvedWhenOmittedUniqueColumnIsNotGenerated() {
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.EventMapper.insertIgnore",
                "event",
                List.of("payload"),
                RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
        );
        TableKeyMetadata metadata = new TableKeyMetadata(
                "event",
                List.of(new TableConstraint(
                        "UK_EVENT_CODE",
                        TableConstraint.ConstraintType.UNIQUE_KEY,
                        List.of("event_code")
                )),
                true,
                java.util.Set.of()
        );

        UpsertKeyInference.InferenceResult result = inference.infer(candidate, metadata).orElseThrow();

        assertThat(result.resolutionCode())
                .isEqualTo(UpsertKeyInference.RESOLUTION_ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY);
    }
}
