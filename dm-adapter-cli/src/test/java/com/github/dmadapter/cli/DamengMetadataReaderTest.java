package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengMetadataReaderTest {
    @Test
    void metadataJdbcUrlAddsBoundedNetworkTimeoutsWithoutOverridingConfiguredValues() {
        assertThat(DamengMetadataReader.metadataJdbcUrl("jdbc:dm://db.example:5236"))
                .isEqualTo("jdbc:dm://db.example:5236?connectTimeout=5000&socketTimeout=20000");
        assertThat(DamengMetadataReader.metadataJdbcUrl(
                "jdbc:dm://db.example:5236?schema=sample&socketTimeout=45000&CONNECTTIMEOUT=9000"
        )).isEqualTo(
                "jdbc:dm://db.example:5236?schema=sample&socketTimeout=45000&CONNECTTIMEOUT=9000"
        );
    }

    @Test
    void splitSchemaListTrimsSkipsBlanksAndKeepsOrder() {
        assertThat(DamengMetadataReader.splitSchemaList(" sample-charge-10, sample-bill-10,,sample-owner,sample-bill-10 "))
                .containsExactly("sample-charge-10", "sample-bill-10", "sample-owner");
    }

    @Test
    void columnTypeQueryReadsAllRequestedTablesFromCurrentSchemaInOnePass() {
        assertThat(DamengMetadataReader.tableObjectQuerySql(3))
                .contains("SYS.SYSOBJECTS")
                .contains("o.SCHID = CURRENT_SCHID()")
                .contains("SUBSTRING(o.INFO6, 50, 1) AS AUTO_INCREMENT_KIND")
                .contains("o.NAME IN (?, ?, ?)")
                .doesNotContain("ALL_TAB_COLUMNS");
        assertThat(DamengMetadataReader.columnTypeQuerySql(3))
                .contains("SYS.SYSCOLUMNS")
                .contains("c.ID IN (?, ?, ?)")
                .doesNotContain("SYS.SYSOBJECTS")
                .doesNotContain("ALL_TAB_COLUMNS");
    }

    @Test
    void constraintQueryReadsPrimaryAndUniqueKeysForAllRequestedTables() {
        assertThat(DamengMetadataReader.constraintQuerySql(2))
                .contains("SYS.SYSCONS")
                .contains("SYS.SYSINDEXES")
                .contains("SF_GET_INDEX_KEY_SEQ")
                .contains("cons.TABLEID IN (?, ?)")
                .contains("cons.TYPE$ IN ('P', 'U')")
                .doesNotContain("ALL_CONSTRAINTS");
    }

    @Test
    void distinguishesIdentityAndMysqlCompatibleAutoIncrementCatalogValues() {
        assertThat(DamengMetadataReader.AutoIncrementKind.fromCatalogValue("1"))
                .isEqualTo(DamengMetadataReader.AutoIncrementKind.IDENTITY);
        assertThat(DamengMetadataReader.AutoIncrementKind.fromCatalogValue("2"))
                .isEqualTo(DamengMetadataReader.AutoIncrementKind.AUTO_INCREMENT);
        assertThat(DamengMetadataReader.AutoIncrementKind.fromCatalogValue("0"))
                .isEqualTo(DamengMetadataReader.AutoIncrementKind.NONE);
        assertThat(DamengMetadataReader.AutoIncrementKind.fromCatalogValue(null))
                .isEqualTo(DamengMetadataReader.AutoIncrementKind.NONE);
    }
}
