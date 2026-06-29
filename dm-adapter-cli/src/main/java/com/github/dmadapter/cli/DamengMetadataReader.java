package com.github.dmadapter.cli;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class DamengMetadataReader {
    private static final int METADATA_QUERY_TIMEOUT_SECONDS = 8;

    Map<String, TableKeyMetadata> readTableKeys(
            DmValidationEnvironment environment,
            Optional<String> configuredSchema,
            Collection<String> tableNames
    ) {
        if (tableNames == null || tableNames.isEmpty()) {
            return Map.of();
        }
        try {
            Class.forName("dm.jdbc.driver.DmDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Dameng JDBC driver was not found on the CLI classpath.", e);
        }

        try (Connection connection = DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.username(),
                environment.password()
        )) {
            List<String> schemaCandidates = schemaCandidates(connection, environment, configuredSchema);
            Map<String, TableKeyMetadata> metadata = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                QualifiedTable qualifiedTable = QualifiedTable.parse(tableName);
                List<String> tableSchemas = qualifiedTable.schema().isBlank()
                        ? schemaCandidates
                        : List.of(qualifiedTable.schema());
                TableKeyMetadata tableMetadata = readTableKeys(connection.getMetaData(), tableSchemas, qualifiedTable.table());
                metadata.put(normalizeTableName(tableName), tableMetadata);
            }
            return metadata;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read Dameng table metadata: " + e.getMessage(), e);
        }
    }

    Map<String, Map<String, String>> readColumnTypes(
            DmValidationEnvironment environment,
            Optional<String> configuredSchema,
            Collection<String> tableNames
    ) {
        if (tableNames == null || tableNames.isEmpty()) {
            return Map.of();
        }
        try {
            Class.forName("dm.jdbc.driver.DmDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Dameng JDBC driver was not found on the CLI classpath.", e);
        }

        try (Connection connection = DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.username(),
                environment.password()
        )) {
            List<String> schemaCandidates = schemaCandidates(connection, environment, configuredSchema);
            Map<String, Map<String, String>> metadata = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                QualifiedTable qualifiedTable = QualifiedTable.parse(tableName);
                List<String> tableSchemas = qualifiedTable.schema().isBlank()
                        ? schemaCandidates
                        : List.of(qualifiedTable.schema());
                Map<String, String> columns = readColumnTypes(connection, tableSchemas, qualifiedTable.table());
                metadata.put(normalizeTableName(tableName), columns);
            }
            return metadata;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read Dameng column metadata: " + e.getMessage(), e);
        }
    }

    private List<String> schemaCandidates(
            Connection connection,
            DmValidationEnvironment environment,
            Optional<String> configuredSchema
    ) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        configuredSchema.filter(schema -> !schema.isBlank())
                .ifPresent(schema -> addSchemaCandidates(candidates, schema));
        schemaFromJdbcUrl(environment.jdbcUrl())
                .ifPresent(schema -> addSchemaCandidates(candidates, schema));
        try {
            String currentSchema = connection.getSchema();
            if (currentSchema != null && !currentSchema.isBlank()) {
                candidates.add(currentSchema);
            }
        } catch (SQLException | AbstractMethodError ignored) {
            // Older drivers may not implement getSchema; username is still a useful fallback.
        }
        if (!environment.username().isBlank()) {
            candidates.add(environment.username());
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().flatMap(schema -> nameVariants(schema).stream()).distinct().toList();
    }

    private void addSchemaCandidates(LinkedHashSet<String> candidates, String schemaValue) {
        candidates.addAll(splitSchemaList(schemaValue));
    }

    static List<String> splitSchemaList(String schemaValue) {
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        if (schemaValue == null || schemaValue.isBlank()) {
            return List.of();
        }
        for (String schema : schemaValue.split(",")) {
            String trimmed = schema.trim();
            if (!trimmed.isBlank()) {
                schemas.add(trimmed);
            }
        }
        return List.copyOf(schemas);
    }

    private Optional<String> schemaFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Optional.empty();
        }
        int question = jdbcUrl.indexOf('?');
        if (question < 0 || question == jdbcUrl.length() - 1) {
            return Optional.empty();
        }
        String query = jdbcUrl.substring(question + 1);
        for (String part : query.split("[;&]")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if ("schema".equalsIgnoreCase(name) && !value.isBlank()) {
                return Optional.of(decodeUrlValue(value));
            }
        }
        return Optional.empty();
    }

    private String decodeUrlValue(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private TableKeyMetadata readTableKeys(DatabaseMetaData databaseMetaData, List<String> schemaCandidates, String tableName)
            throws SQLException {
        for (String schema : schemaCandidates.isEmpty() ? List.of("") : schemaCandidates) {
            for (String tableVariant : nameVariants(tableName)) {
                TableKeyMetadata metadata = readTableKeys(databaseMetaData, blankToNull(schema), tableVariant);
                if (!metadata.constraints().isEmpty()) {
                    return metadata;
                }
            }
        }
        return new TableKeyMetadata(tableName, List.of());
    }

    private TableKeyMetadata readTableKeys(DatabaseMetaData databaseMetaData, String schema, String tableName)
            throws SQLException {
        List<TableConstraint> constraints = new ArrayList<>();
        Optional<TableConstraint> primaryKey = primaryKey(databaseMetaData, schema, tableName);
        primaryKey.ifPresent(constraints::add);
        constraints.addAll(uniqueKeys(databaseMetaData, schema, tableName, primaryKey));
        return new TableKeyMetadata(tableName, constraints);
    }

    private Map<String, String> readColumnTypes(
            Connection connection,
            List<String> schemaCandidates,
            String tableName
    ) throws SQLException {
        for (String schema : schemaCandidates.isEmpty() ? List.of("") : schemaCandidates) {
            for (String tableVariant : nameVariants(tableName)) {
                Map<String, String> columns = readColumnTypes(connection, blankToNull(schema), tableVariant);
                if (!columns.isEmpty()) {
                    return columns;
                }
            }
        }
        return Map.of();
    }

    private Map<String, String> readColumnTypes(Connection connection, String schema, String tableName)
            throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        boolean schemaQualified = schema != null && !schema.isBlank();
        try (PreparedStatement statement = connection.prepareStatement(columnTypeQuerySql(schemaQualified))) {
            configureQueryTimeout(statement);
            if (schemaQualified) {
                statement.setString(1, schema);
                statement.setString(2, tableName);
            } else {
                statement.setString(1, tableName);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    String typeName = resultSet.getString("DATA_TYPE");
                    if (columnName == null || columnName.isBlank() || typeName == null || typeName.isBlank()) {
                        continue;
                    }
                    columns.putIfAbsent(normalizeIdentifier(columnName), typeName.toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    static String columnTypeQuerySql(boolean schemaQualified) {
        if (schemaQualified) {
            return "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
        }
        return "SELECT COLUMN_NAME, DATA_TYPE FROM USER_TAB_COLUMNS WHERE TABLE_NAME = ? ORDER BY COLUMN_ID";
    }

    private void configureQueryTimeout(Statement statement) {
        try {
            statement.setQueryTimeout(METADATA_QUERY_TIMEOUT_SECONDS);
        } catch (SQLException ignored) {
            // Metadata inference is an optimization; drivers that do not support timeouts can still query normally.
        }
    }

    private Optional<TableConstraint> primaryKey(DatabaseMetaData databaseMetaData, String schema, String tableName)
            throws SQLException {
        Map<Short, String> columnsByPosition = new LinkedHashMap<>();
        String keyName = "";
        try (ResultSet resultSet = databaseMetaData.getPrimaryKeys(null, schema, tableName)) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                if (columnName == null || columnName.isBlank()) {
                    continue;
                }
                short position = resultSet.getShort("KEY_SEQ");
                columnsByPosition.put(position, columnName);
                String pkName = resultSet.getString("PK_NAME");
                if (keyName.isBlank() && pkName != null) {
                    keyName = pkName;
                }
            }
        }
        List<String> columns = orderedColumns(columnsByPosition);
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TableConstraint(keyName, TableConstraint.ConstraintType.PRIMARY_KEY, columns));
    }

    private List<TableConstraint> uniqueKeys(
            DatabaseMetaData databaseMetaData,
            String schema,
            String tableName,
            Optional<TableConstraint> primaryKey
    ) throws SQLException {
        Map<String, Map<Short, String>> columnsByIndex = new LinkedHashMap<>();
        try (ResultSet resultSet = databaseMetaData.getIndexInfo(null, schema, tableName, true, false)) {
            while (resultSet.next()) {
                boolean nonUnique = resultSet.getBoolean("NON_UNIQUE");
                short type = resultSet.getShort("TYPE");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (nonUnique
                        || type == DatabaseMetaData.tableIndexStatistic
                        || columnName == null
                        || columnName.isBlank()) {
                    continue;
                }
                String indexName = resultSet.getString("INDEX_NAME");
                if (indexName == null || indexName.isBlank()) {
                    indexName = "(unique)";
                }
                short position = resultSet.getShort("ORDINAL_POSITION");
                columnsByIndex.computeIfAbsent(indexName, ignored -> new LinkedHashMap<>())
                        .put(position, columnName);
            }
        }

        Set<String> primaryKeyColumns = primaryKey
                .map(key -> normalizedColumns(key.columns()))
                .orElse(Set.of());
        List<TableConstraint> uniqueKeys = new ArrayList<>();
        for (Map.Entry<String, Map<Short, String>> entry : columnsByIndex.entrySet()) {
            List<String> columns = orderedColumns(entry.getValue());
            if (columns.isEmpty() || normalizedColumns(columns).equals(primaryKeyColumns)) {
                continue;
            }
            uniqueKeys.add(new TableConstraint(entry.getKey(), TableConstraint.ConstraintType.UNIQUE_KEY, columns));
        }
        return uniqueKeys;
    }

    private List<String> orderedColumns(Map<Short, String> columnsByPosition) {
        return columnsByPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private Set<String> normalizedColumns(List<String> columns) {
        return columns.stream()
                .map(DamengMetadataReader::normalizeIdentifier)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    private static List<String> nameVariants(String name) {
        String normalized = stripQuotes(name);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(normalized);
        variants.add(normalized.toUpperCase(Locale.ROOT));
        variants.add(normalized.toLowerCase(Locale.ROOT));
        return List.copyOf(variants);
    }

    static String normalizeTableName(String tableName) {
        QualifiedTable table = QualifiedTable.parse(tableName);
        return table.table().toLowerCase(Locale.ROOT);
    }

    static String normalizeIdentifier(String identifier) {
        return stripQuotes(identifier).toLowerCase(Locale.ROOT);
    }

    private static String stripQuotes(String value) {
        return value == null ? "" : value.trim()
                .replace("`", "")
                .replace("\"", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record QualifiedTable(String schema, String table) {
        static QualifiedTable parse(String tableName) {
            String normalized = stripQuotes(tableName);
            int dot = normalized.lastIndexOf('.');
            if (dot > 0 && dot < normalized.length() - 1) {
                return new QualifiedTable(normalized.substring(0, dot).trim(), normalized.substring(dot + 1).trim());
            }
            return new QualifiedTable("", normalized.trim());
        }
    }
}
