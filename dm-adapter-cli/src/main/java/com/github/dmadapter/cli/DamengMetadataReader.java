package com.github.dmadapter.cli;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
    private static final int METADATA_QUERY_TIMEOUT_SECONDS = 60;

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
            Map<String, CatalogTable> catalogTables = readCatalogTables(
                    connection,
                    schemaCandidates,
                    tableNames,
                    true
            );
            Map<String, TableKeyMetadata> metadata = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                String normalizedTable = normalizeTableName(tableName);
                CatalogTable catalogTable = catalogTables.get(normalizedTable);
                metadata.put(normalizedTable, catalogTable == null
                        ? new TableKeyMetadata(tableName, List.of(), false, Set.of())
                        : catalogTable.toTableKeyMetadata());
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
            Map<String, CatalogTable> catalogTables = readCatalogTables(
                    connection,
                    schemaCandidates,
                    tableNames,
                    false
            );
            Map<String, Map<String, String>> metadata = new LinkedHashMap<>();
            for (String tableName : tableNames) {
                CatalogTable catalogTable = catalogTables.get(normalizeTableName(tableName));
                metadata.put(normalizeTableName(tableName), catalogTable == null
                        ? Map.of()
                        : Map.copyOf(catalogTable.columnTypes()));
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

    private Map<String, CatalogTable> readCatalogTables(
            Connection connection,
            List<String> schemaCandidates,
            Collection<String> tableNames,
            boolean includeConstraints
    ) throws SQLException {
        List<QualifiedTable> unqualifiedTables = new ArrayList<>();
        Map<String, List<QualifiedTable>> qualifiedTables = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            QualifiedTable table = QualifiedTable.parse(tableName);
            if (table.table().isBlank()) {
                continue;
            }
            if (table.schema().isBlank()) {
                unqualifiedTables.add(table);
            } else {
                qualifiedTables.computeIfAbsent(table.schema(), ignored -> new ArrayList<>()).add(table);
            }
        }

        Map<String, CatalogTable> result = new LinkedHashMap<>();
        readCatalogTablesAcrossSchemas(connection, schemaCandidates, unqualifiedTables, result, includeConstraints);
        for (Map.Entry<String, List<QualifiedTable>> entry : qualifiedTables.entrySet()) {
            readCatalogTablesAcrossSchemas(
                    connection,
                    nameVariants(entry.getKey()),
                    entry.getValue(),
                    result,
                    includeConstraints
            );
        }
        return result;
    }

    private void readCatalogTablesAcrossSchemas(
            Connection connection,
            List<String> schemaCandidates,
            List<QualifiedTable> requestedTables,
            Map<String, CatalogTable> result,
            boolean includeConstraints
    ) throws SQLException {
        if (requestedTables.isEmpty()) {
            return;
        }
        List<String> candidates = schemaCandidates.isEmpty() ? List.of("") : schemaCandidates;
        for (String schema : candidates) {
            List<QualifiedTable> unresolved = requestedTables.stream()
                    .filter(table -> !result.containsKey(normalizeTableName(table.table())))
                    .toList();
            if (unresolved.isEmpty()) {
                return;
            }
            if (!schema.isBlank() && !setCurrentSchema(connection, schema)) {
                continue;
            }
            Map<String, CatalogTable> found = readCatalogColumns(connection, unresolved);
            if (found.isEmpty()) {
                continue;
            }
            if (includeConstraints) {
                readConstraints(connection, found);
            }
            found.forEach(result::putIfAbsent);
        }
    }

    private boolean setCurrentSchema(Connection connection, String schema) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA " + quoteIdentifier(schema));
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Map<String, CatalogTable> readCatalogColumns(
            Connection connection,
            List<QualifiedTable> requestedTables
    ) throws SQLException {
        List<String> catalogNames = catalogTableNames(requestedTables.stream()
                .map(QualifiedTable::table)
                .toList());
        if (catalogNames.isEmpty()) {
            return Map.of();
        }
        Map<String, CatalogTable> tables = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(tableObjectQuerySql(catalogNames.size()))) {
            configureQueryTimeout(statement);
            bindStrings(statement, catalogNames);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) {
                        continue;
                    }
                    long tableId = resultSet.getLong("TABLE_ID");
                    tables.computeIfAbsent(
                            normalizeTableName(tableName),
                            ignored -> new CatalogTable(tableId, tableName)
                    );
                }
            }
        }
        readCatalogColumnDetails(connection, tables);
        return tables;
    }

    private void readCatalogColumnDetails(Connection connection, Map<String, CatalogTable> tables) throws SQLException {
        List<Long> tableIds = tables.values().stream().map(CatalogTable::tableId).distinct().toList();
        if (tableIds.isEmpty()) {
            return;
        }
        Map<Long, CatalogTable> tablesById = new LinkedHashMap<>();
        tables.values().forEach(table -> tablesById.put(table.tableId(), table));
        try (PreparedStatement statement = connection.prepareStatement(columnTypeQuerySql(tableIds.size()))) {
            configureQueryTimeout(statement);
            bindLongs(statement, tableIds);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    CatalogTable table = tablesById.get(resultSet.getLong("TABLE_ID"));
                    String columnName = resultSet.getString("COLUMN_NAME");
                    String typeName = resultSet.getString("DATA_TYPE");
                    if (table == null || columnName == null || columnName.isBlank()
                            || typeName == null || typeName.isBlank()) {
                        continue;
                    }
                    String normalizedColumn = normalizeIdentifier(columnName);
                    table.columnTypes().putIfAbsent(normalizedColumn, typeName.toUpperCase(Locale.ROOT));
                    long columnInfo = resultSet.getLong("COLUMN_INFO");
                    if (!resultSet.wasNull() && columnInfo % 2L == 1L) {
                        table.autoGeneratedColumns().add(normalizedColumn);
                    }
                }
            }
        }
    }

    private void readConstraints(Connection connection, Map<String, CatalogTable> tables) throws SQLException {
        List<Long> tableIds = tables.values().stream().map(CatalogTable::tableId).distinct().toList();
        if (tableIds.isEmpty()) {
            return;
        }
        Map<String, ConstraintColumns> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(constraintQuerySql(tableIds.size()))) {
            configureQueryTimeout(statement);
            bindLongs(statement, tableIds);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    String constraintName = resultSet.getString("CONSTRAINT_NAME");
                    String constraintType = resultSet.getString("CONSTRAINT_TYPE");
                    String columnName = resultSet.getString("COLUMN_NAME");
                    if (tableName == null || constraintName == null || constraintType == null || columnName == null) {
                        continue;
                    }
                    String key = normalizeTableName(tableName) + "\u0000" + constraintName;
                    ConstraintColumns constraint = constraints.computeIfAbsent(
                            key,
                            ignored -> new ConstraintColumns(tableName, constraintName, constraintType)
                    );
                    constraint.columns().put(resultSet.getShort("COLUMN_POSITION"), columnName);
                }
            }
        }
        for (ConstraintColumns constraint : constraints.values()) {
            CatalogTable table = tables.get(normalizeTableName(constraint.tableName()));
            if (table == null) {
                continue;
            }
            List<String> columns = orderedColumns(constraint.columns());
            if (columns.isEmpty()) {
                continue;
            }
            TableConstraint.ConstraintType type = "P".equalsIgnoreCase(constraint.type())
                    ? TableConstraint.ConstraintType.PRIMARY_KEY
                    : TableConstraint.ConstraintType.UNIQUE_KEY;
            table.constraints().add(new TableConstraint(constraint.name(), type, columns));
        }
    }

    static String tableObjectQuerySql(int tableCount) {
        return "SELECT o.ID AS TABLE_ID, o.NAME AS TABLE_NAME FROM SYS.SYSOBJECTS o "
                + "WHERE o.SCHID = CURRENT_SCHID() "
                + "AND o.SUBTYPE$ IN ('UTAB', 'STAB', 'VIEW') "
                + "AND o.NAME IN (" + placeholders(tableCount) + ") "
                + "ORDER BY o.NAME";
    }

    static String columnTypeQuerySql(int tableCount) {
        return "SELECT c.ID AS TABLE_ID, c.NAME AS COLUMN_NAME, c.TYPE$ AS DATA_TYPE, "
                + "c.INFO2 AS COLUMN_INFO FROM SYS.SYSCOLUMNS c "
                + "WHERE c.ID IN (" + placeholders(tableCount) + ") "
                + "ORDER BY c.ID, c.COLID";
    }

    static String constraintQuerySql(int tableCount) {
        String keyPosition = "SF_GET_INDEX_KEY_SEQ(idx.KEYNUM, idx.KEYINFO, col.COLID)";
        return "SELECT tab.NAME AS TABLE_NAME, conobj.NAME AS CONSTRAINT_NAME, "
                + "cons.TYPE$ AS CONSTRAINT_TYPE, col.NAME AS COLUMN_NAME, "
                + keyPosition + " AS COLUMN_POSITION "
                + "FROM SYS.SYSCONS cons "
                + "JOIN SYS.SYSOBJECTS tab ON tab.ID = cons.TABLEID "
                + "JOIN SYS.SYSOBJECTS conobj ON conobj.ID = cons.ID "
                + "JOIN SYS.SYSINDEXES idx ON idx.ID = cons.INDEXID "
                + "JOIN SYS.SYSCOLUMNS col ON col.ID = cons.TABLEID "
                + "WHERE cons.TABLEID IN (" + placeholders(tableCount) + ") "
                + "AND cons.TYPE$ IN ('P', 'U') AND cons.VALID = 'Y' "
                + "AND " + keyPosition + " > 0 "
                + "ORDER BY tab.NAME, CASE cons.TYPE$ WHEN 'P' THEN 0 ELSE 1 END, "
                + "conobj.NAME, " + keyPosition;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, count), "?"));
    }

    private static List<String> catalogTableNames(Collection<String> tableNames) {
        LinkedHashSet<String> catalogNames = new LinkedHashSet<>();
        for (String tableName : tableNames) {
            String normalized = stripQuotes(tableName);
            if (normalized.isBlank()) {
                continue;
            }
            catalogNames.add(normalized);
            catalogNames.add(normalized.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(catalogNames);
    }

    private void bindStrings(PreparedStatement statement, List<String> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setString(index + 1, values.get(index));
        }
    }

    private void bindLongs(PreparedStatement statement, List<Long> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setLong(index + 1, values.get(index));
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + stripQuotes(identifier).replace("\"", "\"\"") + "\"";
    }

    private void configureQueryTimeout(Statement statement) {
        try {
            statement.setQueryTimeout(METADATA_QUERY_TIMEOUT_SECONDS);
        } catch (SQLException ignored) {
            // Metadata inference is an optimization; drivers that do not support timeouts can still query normally.
        }
    }

    private List<String> orderedColumns(Map<Short, String> columnsByPosition) {
        return columnsByPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
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

    private record CatalogTable(
            long tableId,
            String tableName,
            Map<String, String> columnTypes,
            Set<String> autoGeneratedColumns,
            List<TableConstraint> constraints
    ) {
        private CatalogTable(long tableId, String tableName) {
            this(tableId, tableName, new LinkedHashMap<>(), new LinkedHashSet<>(), new ArrayList<>());
        }

        private TableKeyMetadata toTableKeyMetadata() {
            return new TableKeyMetadata(tableName, List.copyOf(constraints), true, Set.copyOf(autoGeneratedColumns));
        }
    }

    private record ConstraintColumns(
            String tableName,
            String name,
            String type,
            Map<Short, String> columns
    ) {
        private ConstraintColumns(String tableName, String name, String type) {
            this(tableName, name, type, new LinkedHashMap<>());
        }
    }
}
