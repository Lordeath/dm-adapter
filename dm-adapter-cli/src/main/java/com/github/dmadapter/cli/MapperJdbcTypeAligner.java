package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class MapperJdbcTypeAligner {
    private static final String IDENTIFIER = "(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final String QUALIFIED_IDENTIFIER = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern STATEMENT_PATTERN = Pattern.compile(
            "(?is)<(select|insert|update|delete)\\b[^>]*>[\\s\\S]*?</\\1>"
    );
    private static final Pattern UPDATE_TABLE_PATTERN = Pattern.compile(
            "(?is)(?<!<)\\bupdate\\s+(" + QUALIFIED_IDENTIFIER + ")"
    );
    private static final Pattern INSERT_TABLE_PATTERN = Pattern.compile(
            "(?is)(?<!<)\\binsert\\s+(?:ignore\\s+)?into\\s+(" + QUALIFIED_IDENTIFIER + ")"
    );
    private static final Pattern COLUMN_PLACEHOLDER_PATTERN = Pattern.compile(
            "(?is)(" + QUALIFIED_IDENTIFIER + ")\\s*(=|<>|!=|>=|<=|>|<)\\s*"
                    + "(#\\{\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)})"
    );
    private static final Pattern PLACEHOLDER_COLUMN_PATTERN = Pattern.compile(
            "(?is)(#\\{\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)})\\s*"
                    + "(=|<>|!=|>=|<=|>|<)\\s*(" + QUALIFIED_IDENTIFIER + ")"
    );
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "#\\{\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)}"
    );
    private static final Pattern JDBC_TYPE_PATTERN = Pattern.compile("(?i)(jdbcType\\s*=\\s*)([A-Za-z0-9_]+)");
    private static final Pattern MAPPER_NAMESPACE_PATTERN = Pattern.compile(
            "(?is)<mapper\\b[^>]*\\bnamespace\\s*=\\s*([\"'])(.*?)\\1"
    );
    private static final Pattern TRIM_PATTERN = Pattern.compile("(?is)<trim\\b([^>]*)>([\\s\\S]*?)</trim>");
    private static final Pattern IF_PATTERN = Pattern.compile("(?is)<if\\b[^>]*>([\\s\\S]*?)</if>");
    private static final Pattern FOREACH_ITEM_PATTERN = Pattern.compile(
            "(?is)<foreach\\b[^>]*\\bitem\\s*=\\s*([\"'])(.*?)\\1"
    );

    Set<String> referencedTables(ProjectScanResult scanResult, AdapterContext context) {
        Set<String> tables = new LinkedHashSet<>();
        for (Path mapperPath : mapperTargetPaths(scanResult, context)) {
            if (!Files.isRegularFile(mapperPath)) {
                continue;
            }
            try {
                String text = Files.readString(mapperPath, StandardCharsets.UTF_8);
                addReferencedTables(text, tables);
            } catch (IOException ignored) {
                // The alignment step is optional; unreadable mapper files will be reported during validation.
            }
        }
        return tables;
    }

    MapperJdbcTypeAlignmentResult align(
            ProjectScanResult scanResult,
            AdapterContext context,
            Map<String, Map<String, String>> columnTypes
    ) {
        if (context.dryRun() || columnTypes == null || columnTypes.isEmpty()) {
            return MapperJdbcTypeAlignmentResult.empty();
        }
        List<FileChange> fileChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JavaFieldTypeMetadata javaFieldTypes;
        try {
            javaFieldTypes = JavaFieldTypeMetadata.load(context.projectRoot());
        } catch (IOException e) {
            javaFieldTypes = JavaFieldTypeMetadata.empty();
            warnings.add("Could not read Java source field metadata for MyBatis jdbcType alignment: " + e.getMessage());
        }
        for (Path mapperPath : mapperTargetPaths(scanResult, context)) {
            if (!Files.isRegularFile(mapperPath)) {
                continue;
            }
            try {
                String original = Files.readString(mapperPath, StandardCharsets.UTF_8);
                Alignment alignment = alignText(original, columnTypes, javaFieldTypes);
                if (alignment.replacements() > 0 && !alignment.text().equals(original)) {
                    Files.writeString(mapperPath, alignment.text(), StandardCharsets.UTF_8);
                    fileChanges.add(FileChange.applied(
                            mapperPath.toString(),
                            "UPDATE",
                            "Aligned " + alignment.replacements()
                                    + " MyBatis jdbcType declaration(s) with Dameng column metadata."
                    ));
                }
            } catch (IOException e) {
                warnings.add("Could not align MyBatis jdbcType declarations in " + mapperPath + ": " + e.getMessage());
            }
        }
        return new MapperJdbcTypeAlignmentResult(fileChanges, warnings);
    }

    private List<Path> mapperTargetPaths(ProjectScanResult scanResult, AdapterContext context) {
        List<Path> paths = new ArrayList<>();
        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            Path target = mapperTargetDir(context, mapperXmlFile)
                    .resolve(toMapperDmRelativePath(mapperXmlFile.resourcesRelativePath()));
            paths.add(target);
        }
        return paths;
    }

    private Path mapperTargetDir(AdapterContext context, MapperXmlFile mapperXmlFile) {
        if (!mapperXmlFile.resourcesRoot().isBlank()
                && context.mapperTargetDir().equals(context.defaultMapperTargetDir())) {
            return Paths.get(mapperXmlFile.resourcesRoot()).resolve("mapper-dm");
        }
        return context.mapperTargetDir();
    }

    private Path toMapperDmRelativePath(String resourcesRelativePath) {
        String normalized = resourcesRelativePath.replace('\\', '/');
        if (normalized.startsWith("mapper/")) {
            return Paths.get(normalized.substring("mapper/".length()));
        }
        if (normalized.startsWith("mappers/")) {
            return Paths.get(normalized.substring("mappers/".length()));
        }
        return Paths.get(normalized);
    }

    private void addReferencedTables(String text, Set<String> tables) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher updateMatcher = UPDATE_TABLE_PATTERN.matcher(text);
        while (updateMatcher.find()) {
            addTable(tables, updateMatcher.group(1));
        }
        Matcher insertMatcher = INSERT_TABLE_PATTERN.matcher(text);
        while (insertMatcher.find()) {
            addTable(tables, insertMatcher.group(1));
        }
    }

    private void addTable(Set<String> tables, String tableExpression) {
        String table = lastIdentifierPart(tableExpression);
        if (!table.isBlank()) {
            tables.add(table);
        }
    }

    private Alignment alignText(
            String text,
            Map<String, Map<String, String>> columnTypes,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        Matcher matcher = STATEMENT_PATTERN.matcher(text);
        String mapperType = mapperNamespace(text);
        StringBuffer output = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            Alignment statement = alignStatement(matcher.group(), mapperType, columnTypes, javaFieldTypes);
            replacements += statement.replacements();
            matcher.appendReplacement(output, Matcher.quoteReplacement(statement.text()));
        }
        matcher.appendTail(output);
        return new Alignment(output.toString(), replacements);
    }

    private Alignment alignStatement(
            String statement,
            String mapperType,
            Map<String, Map<String, String>> columnTypes,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        String statementId = statementAttribute(statement, "id");
        String parameterType = statementAttribute(statement, "parameterType");
        Alignment current = alignUpdatePlaceholders(statement, columnTypes, parameterType, mapperType, statementId, javaFieldTypes);
        Alignment simpleInsert = alignSimpleInsertPlaceholders(current.text(), columnTypes, parameterType, mapperType, statementId, javaFieldTypes);
        Alignment structuredInsert = alignStructuredInsertPlaceholders(simpleInsert.text(), columnTypes, parameterType, mapperType, statementId, javaFieldTypes);
        return new Alignment(
                structuredInsert.text(),
                current.replacements() + simpleInsert.replacements() + structuredInsert.replacements()
        );
    }

    private Alignment alignUpdatePlaceholders(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            String parameterType,
            String mapperType,
            String statementId,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        String table = firstTable(statement, UPDATE_TABLE_PATTERN);
        if (table.isBlank()) {
            return new Alignment(statement, 0);
        }
        Alignment left = alignColumnPlaceholderPattern(
                statement,
                COLUMN_PLACEHOLDER_PATTERN,
                table,
                1,
                3,
                4,
                parameterType,
                mapperType,
                statementId,
                javaFieldTypes,
                columnTypes
        );
        Alignment right = alignColumnPlaceholderPattern(
                left.text(),
                PLACEHOLDER_COLUMN_PATTERN,
                table,
                5,
                1,
                2,
                parameterType,
                mapperType,
                statementId,
                javaFieldTypes,
                columnTypes
        );
        return new Alignment(right.text(), left.replacements() + right.replacements());
    }

    private Alignment alignColumnPlaceholderPattern(
            String text,
            Pattern pattern,
            String defaultTable,
            int columnGroup,
            int placeholderGroup,
            int expressionGroup,
            String parameterType,
            String mapperType,
            String statementId,
            JavaFieldTypeMetadata javaFieldTypes,
            Map<String, Map<String, String>> columnTypes
    ) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer output = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            String column = lastIdentifierPart(matcher.group(columnGroup));
            String columnType = columnType(defaultTable, column, columnTypes);
            String placeholder = matcher.group(placeholderGroup);
            String alignedPlaceholder = alignPlaceholderJdbcType(
                    placeholder,
                    columnType,
                    isStringParameterExpression(
                            matcher.group(expressionGroup),
                            parameterType,
                            mapperType,
                            statementId,
                            text,
                            javaFieldTypes
                    )
            );
            if (!placeholder.equals(alignedPlaceholder)) {
                replacements++;
            }
            String replacement = matcher.group().replace(placeholder, alignedPlaceholder);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return new Alignment(output.toString(), replacements);
    }

    private Alignment alignSimpleInsertPlaceholders(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            String parameterType,
            String mapperType,
            String statementId,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        Matcher insertMatcher = INSERT_TABLE_PATTERN.matcher(statement);
        if (!insertMatcher.find()) {
            return new Alignment(statement, 0);
        }
        String table = lastIdentifierPart(insertMatcher.group(1));
        int columnsOpen = statement.indexOf('(', insertMatcher.end());
        if (columnsOpen < 0) {
            return new Alignment(statement, 0);
        }
        int columnsClose = matchingParen(statement, columnsOpen);
        if (columnsClose < 0) {
            return new Alignment(statement, 0);
        }
        Matcher valuesMatcher = Pattern.compile("(?is)\\bvalues\\b").matcher(statement);
        if (!valuesMatcher.find(columnsClose)) {
            return new Alignment(statement, 0);
        }
        int valuesOpen = statement.indexOf('(', valuesMatcher.end());
        if (valuesOpen < 0) {
            return new Alignment(statement, 0);
        }
        int valuesClose = matchingParen(statement, valuesOpen);
        if (valuesClose < 0) {
            return new Alignment(statement, 0);
        }
        List<String> columns = splitTopLevelComma(statement.substring(columnsOpen + 1, columnsClose));
        List<String> values = splitTopLevelComma(statement.substring(valuesOpen + 1, valuesClose));
        if (columns.isEmpty() || values.isEmpty()) {
            return new Alignment(statement, 0);
        }
        Map<String, String> expressionColumnTypes = new LinkedHashMap<>();
        int count = Math.min(columns.size(), values.size());
        for (int i = 0; i < count; i++) {
            Matcher placeholder = PLACEHOLDER_PATTERN.matcher(values.get(i));
            if (placeholder.find()) {
                String column = cleanSqlIdentifier(columns.get(i));
                String columnType = columnType(table, column, columnTypes);
                if (!columnType.isBlank()) {
                    expressionColumnTypes.put(placeholder.group(1), columnType);
                }
            }
        }
        return alignPlaceholdersByExpression(
                statement,
                expressionColumnTypes,
                parameterType,
                mapperType,
                statementId,
                javaFieldTypes
        );
    }

    private Alignment alignStructuredInsertPlaceholders(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            String parameterType,
            String mapperType,
            String statementId,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        Matcher insertMatcher = INSERT_TABLE_PATTERN.matcher(statement);
        if (!insertMatcher.find()) {
            return new Alignment(statement, 0);
        }
        String table = lastIdentifierPart(insertMatcher.group(1));
        List<TrimBlock> trims = trimBlocks(statement);
        List<String> columns = List.of();
        List<String> expressions = List.of();
        for (TrimBlock trim : trims) {
            if (columns.isEmpty() && !trim.body().contains("#{") && !trim.body().contains("${")) {
                columns = columnEntries(trim.body());
                continue;
            }
            if (expressions.isEmpty()
                    && trim.body().contains("#{")
                    && (trim.attributes().toLowerCase(Locale.ROOT).contains("values")
                    || !columns.isEmpty())) {
                expressions = placeholderEntries(trim.body());
            }
        }
        if (columns.isEmpty() || expressions.isEmpty()) {
            return new Alignment(statement, 0);
        }
        Map<String, String> expressionColumnTypes = new LinkedHashMap<>();
        int count = Math.min(columns.size(), expressions.size());
        for (int i = 0; i < count; i++) {
            String expression = expressions.get(i);
            if (expression.isBlank()) {
                continue;
            }
            String columnType = columnType(table, columns.get(i), columnTypes);
            if (!columnType.isBlank()) {
                expressionColumnTypes.put(expression, columnType);
            }
        }
        return alignPlaceholdersByExpression(
                statement,
                expressionColumnTypes,
                parameterType,
                mapperType,
                statementId,
                javaFieldTypes
        );
    }

    private Alignment alignPlaceholdersByExpression(
            String text,
            Map<String, String> expressionColumnTypes,
            String parameterType,
            String mapperType,
            String statementId,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        if (expressionColumnTypes.isEmpty()) {
            return new Alignment(text, 0);
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer output = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            String expression = matcher.group(1);
            String columnType = expressionColumnTypes.get(expression);
            String placeholder = matcher.group();
            String alignedPlaceholder = alignPlaceholderJdbcType(
                    placeholder,
                    columnType,
                    isStringParameterExpression(expression, parameterType, mapperType, statementId, text, javaFieldTypes)
            );
            if (!placeholder.equals(alignedPlaceholder)) {
                replacements++;
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(alignedPlaceholder));
        }
        matcher.appendTail(output);
        return new Alignment(output.toString(), replacements);
    }

    private String alignPlaceholderJdbcType(String placeholder, String columnType, boolean stringParameter) {
        String targetJdbcType = jdbcTypeForColumnType(columnType);
        if (targetJdbcType.isBlank()) {
            return placeholder;
        }
        if (stringParameter && isNumericColumnType(columnType)) {
            return "CAST(" + replaceOrAddJdbcType(placeholder, "VARCHAR") + " AS " + targetJdbcType + ")";
        }
        return replaceOrAddJdbcType(placeholder, targetJdbcType);
    }

    private String replaceOrAddJdbcType(String placeholder, String targetJdbcType) {
        Matcher matcher = JDBC_TYPE_PATTERN.matcher(placeholder);
        if (!matcher.find()) {
            int close = placeholder.lastIndexOf('}');
            if (close < 0) {
                return placeholder;
            }
            return placeholder.substring(0, close) + ",jdbcType=" + targetJdbcType + placeholder.substring(close);
        }
        String current = matcher.group(2);
        if (targetJdbcType.equalsIgnoreCase(current)) {
            return placeholder;
        }
        return placeholder.substring(0, matcher.start(2))
                + targetJdbcType
                + placeholder.substring(matcher.end(2));
    }

    private boolean isStringParameterExpression(
            String expression,
            String parameterType,
            String mapperType,
            String statementId,
            String statement,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        if (javaFieldTypes == null) {
            return false;
        }
        List<String> parts = Pattern.compile("\\.")
                .splitAsStream(expression == null ? "" : expression.trim())
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() == 1 && parameterType != null && !parameterType.isBlank()) {
            return javaFieldTypes.isStringField(parameterType, parts.get(0));
        }
        if (parts.size() == 2
                && !mapperType.isBlank()
                && !statementId.isBlank()
                && foreachItemNames(statement).contains(parts.get(0))) {
            return javaFieldTypes.isStringCollectionItemField(mapperType, statementId, parts.get(1));
        }
        return false;
    }

    private boolean isNumericColumnType(String columnType) {
        String type = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
        return type.contains("BIGINT")
                || type.contains("TINYINT")
                || type.contains("SMALLINT")
                || type.contains("INT")
                || type.contains("NUMBER")
                || type.contains("DECIMAL")
                || type.contains("NUMERIC")
                || type.contains("DOUBLE")
                || type.contains("FLOAT")
                || type.contains("REAL");
    }

    private String jdbcTypeForColumnType(String columnType) {
        String type = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
        if (type.isBlank()) {
            return "";
        }
        if (type.contains("CLOB")
                || type.contains("TEXT")
                || type.contains("CHAR")
                || type.contains("VARCHAR")
                || type.contains("JSON")) {
            return "VARCHAR";
        }
        if (type.contains("BIGINT")) {
            return "BIGINT";
        }
        if (type.contains("TINYINT")) {
            return "TINYINT";
        }
        if (type.contains("SMALLINT")) {
            return "SMALLINT";
        }
        if (type.contains("INT")) {
            return "INTEGER";
        }
        if (type.contains("NUMBER")
                || type.contains("DECIMAL")
                || type.contains("NUMERIC")) {
            return "DECIMAL";
        }
        if (type.contains("DOUBLE")) {
            return "DOUBLE";
        }
        if (type.contains("FLOAT")) {
            return "FLOAT";
        }
        if (type.contains("REAL")) {
            return "REAL";
        }
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return "TIMESTAMP";
        }
        if (type.contains("DATE")) {
            return "DATE";
        }
        if (type.contains("TIME")) {
            return "TIME";
        }
        if (type.contains("BOOLEAN")) {
            return "BOOLEAN";
        }
        if (type.contains("BIT")) {
            return "BIT";
        }
        return "";
    }

    private String columnType(String table, String column, Map<String, Map<String, String>> columnTypes) {
        if (table == null || table.isBlank() || column == null || column.isBlank()) {
            return "";
        }
        Map<String, String> tableColumns = columnTypes.get(normalizeIdentifier(table));
        if (tableColumns == null) {
            return "";
        }
        return tableColumns.getOrDefault(normalizeIdentifier(column), "");
    }

    private String firstTable(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? lastIdentifierPart(matcher.group(1)) : "";
    }

    private String statementAttribute(String statement, String attributeName) {
        Matcher matcher = Pattern.compile(
                "(?is)<(?:select|insert|update|delete)\\b[^>]*\\b"
                        + Pattern.quote(attributeName)
                        + "\\s*=\\s*([\"'])(.*?)\\1"
        ).matcher(statement == null ? "" : statement);
        return matcher.find() ? matcher.group(2).trim() : "";
    }

    private String mapperNamespace(String text) {
        Matcher matcher = MAPPER_NAMESPACE_PATTERN.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(2).trim() : "";
    }

    private Set<String> foreachItemNames(String statement) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = FOREACH_ITEM_PATTERN.matcher(statement == null ? "" : statement);
        while (matcher.find()) {
            String item = matcher.group(2).trim();
            if (!item.isBlank()) {
                names.add(item);
            }
        }
        if (names.isEmpty() && statement != null && statement.toLowerCase(Locale.ROOT).contains("<foreach")) {
            names.add("item");
        }
        return names;
    }

    private List<TrimBlock> trimBlocks(String text) {
        List<TrimBlock> trims = new ArrayList<>();
        Matcher matcher = TRIM_PATTERN.matcher(text);
        while (matcher.find()) {
            trims.add(new TrimBlock(matcher.group(1), matcher.group(2)));
        }
        return trims;
    }

    private List<String> columnEntries(String text) {
        return entryBodies(text).stream()
                .map(this::cleanSqlIdentifier)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> placeholderEntries(String text) {
        List<String> expressions = new ArrayList<>();
        for (String entry : entryBodies(text)) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(entry);
            expressions.add(matcher.find() ? matcher.group(1) : "");
        }
        return expressions;
    }

    private List<String> entryBodies(String text) {
        Matcher ifMatcher = IF_PATTERN.matcher(text);
        List<String> entries = new ArrayList<>();
        while (ifMatcher.find()) {
            entries.add(ifMatcher.group(1));
        }
        if (!entries.isEmpty()) {
            return entries;
        }
        return splitTopLevelComma(text);
    }

    private List<String> splitTopLevelComma(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote && startsMyBatisPlaceholder(text, i)) {
                int close = text.indexOf('}', i + 2);
                if (close > i) {
                    i = close;
                }
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    parts.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        String last = text.substring(start).trim();
        if (!last.isBlank()) {
            parts.add(last);
        }
        return parts;
    }

    private boolean startsMyBatisPlaceholder(String text, int index) {
        return index + 1 < text.length()
                && text.charAt(index) == '#'
                && text.charAt(index + 1) == '{';
    }

    private int matchingParen(String text, int openIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private String lastIdentifierPart(String expression) {
        List<String> parts = Pattern.compile("\\.")
                .splitAsStream(expression == null ? "" : expression.trim())
                .map(this::cleanSqlIdentifier)
                .filter(value -> !value.isBlank())
                .toList();
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }

    private String cleanSqlIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String cleaned = identifier.replaceAll("(?is)<[^>]+>", "")
                .replace("`", "")
                .replace("\"", "")
                .replace(",", "")
                .trim();
        if (!cleaned.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return "";
        }
        return cleaned;
    }

    private String normalizeIdentifier(String identifier) {
        return cleanSqlIdentifier(identifier).toLowerCase(Locale.ROOT);
    }

    private record Alignment(String text, int replacements) {
    }

    private record TrimBlock(String attributes, String body) {
    }

    private static final class JavaFieldTypeMetadata {
        private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");
        private static final Pattern FIELD_PATTERN = Pattern.compile(
                "(?m)^\\s*(?:private|protected|public)\\s+"
                        + "(?:(?:static|final|transient|volatile)\\s+)*"
                        + "([A-Za-z_][A-Za-z0-9_.$]*(?:\\s*<[^;=(){}]+>)?(?:\\s*\\[\\])?)\\s+"
                        + "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)"
        );
        private static final Pattern COLLECTION_ELEMENT_SIGNATURE_PATTERN = Pattern.compile(
                "Ljava/util/(?:List|Collection|Set)<L([^;]+);>;"
        );

        private final Map<String, Map<String, String>> fieldsByType;
        private final Map<String, Map<String, MethodCollectionMetadata>> collectionMethodsByType;

        private JavaFieldTypeMetadata(
                Map<String, Map<String, String>> fieldsByType,
                Map<String, Map<String, MethodCollectionMetadata>> collectionMethodsByType
        ) {
            this.fieldsByType = fieldsByType;
            this.collectionMethodsByType = collectionMethodsByType;
        }

        private static JavaFieldTypeMetadata empty() {
            return new JavaFieldTypeMetadata(Map.of(), Map.of());
        }

        private static JavaFieldTypeMetadata load(Path projectRoot) throws IOException {
            if (projectRoot == null || !Files.isDirectory(projectRoot)) {
                return empty();
            }
            Map<String, Map<String, String>> fields = new LinkedHashMap<>();
            Map<String, Map<String, MethodCollectionMetadata>> collectionMethods = new LinkedHashMap<>();
            try (Stream<Path> paths = Files.walk(projectRoot)) {
                List<Path> regularFiles = paths
                        .filter(Files::isRegularFile)
                        .toList();
                for (Path file : regularFiles) {
                    if (!file.toString().endsWith(".java") || !isMainJavaSource(file)) {
                        continue;
                    }
                    try {
                        addJavaFile(fields, file);
                    } catch (IOException ignored) {
                        // Keep usable source metadata when one project file is unreadable or not UTF-8.
                    }
                }
                for (Path file : regularFiles) {
                    if (!file.toString().endsWith(".class") || !isMainClassFile(file)) {
                        continue;
                    }
                    try {
                        addClassFile(fields, collectionMethods, file);
                    } catch (IOException ignored) {
                        // Keep usable compiled metadata when one class file is unreadable.
                    }
                }
            }
            return new JavaFieldTypeMetadata(fields, collectionMethods);
        }

        private static boolean isMainJavaSource(Path path) {
            String normalized = path.toString().replace('\\', '/');
            return normalized.contains("/src/main/java/");
        }

        private static boolean isMainClassFile(Path path) {
            String normalized = path.toString().replace('\\', '/');
            return normalized.contains("/target/classes/");
        }

        private static void addJavaFile(Map<String, Map<String, String>> fields, Path javaFile) throws IOException {
            String text = Files.readString(javaFile, StandardCharsets.UTF_8);
            String simpleName = javaFile.getFileName().toString().replaceFirst("\\.java$", "");
            String packageName = "";
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(text);
            if (packageMatcher.find()) {
                packageName = packageMatcher.group(1);
            }
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            Matcher fieldMatcher = FIELD_PATTERN.matcher(text);
            while (fieldMatcher.find()) {
                fieldTypes.putIfAbsent(fieldMatcher.group(2), normalizeType(fieldMatcher.group(1)));
            }
            if (fieldTypes.isEmpty()) {
                return;
            }
            fields.putIfAbsent(simpleName, fieldTypes);
            if (!packageName.isBlank()) {
                fields.putIfAbsent(packageName + "." + simpleName, fieldTypes);
            }
        }

        private static void addClassFile(
                Map<String, Map<String, String>> fields,
                Map<String, Map<String, MethodCollectionMetadata>> collectionMethods,
                Path classFile
        ) throws IOException {
            ClassMetadata metadata = readClassFile(classFile);
            if (!metadata.fieldTypes().isEmpty()) {
                fields.putIfAbsent(metadata.simpleName(), metadata.fieldTypes());
                fields.putIfAbsent(metadata.typeName(), metadata.fieldTypes());
            }
            if (!metadata.collectionMethods().isEmpty()) {
                collectionMethods.putIfAbsent(metadata.simpleName(), metadata.collectionMethods());
                collectionMethods.putIfAbsent(metadata.typeName(), metadata.collectionMethods());
            }
        }

        private static ClassMetadata readClassFile(Path classFile) throws IOException {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(classFile))) {
                if (input.readInt() != 0xCAFEBABE) {
                    throw new IOException("Invalid Java class file");
                }
                input.readUnsignedShort();
                input.readUnsignedShort();
                Object[] constantPool = readConstantPool(input);
                input.readUnsignedShort();
                String typeName = className(constantPool, input.readUnsignedShort()).replace('/', '.');
                input.readUnsignedShort();
                int interfacesCount = input.readUnsignedShort();
                for (int i = 0; i < interfacesCount; i++) {
                    input.readUnsignedShort();
                }
                int fieldsCount = input.readUnsignedShort();
                Map<String, String> fieldTypes = new LinkedHashMap<>();
                for (int i = 0; i < fieldsCount; i++) {
                    input.readUnsignedShort();
                    String fieldName = utf8(constantPool, input.readUnsignedShort());
                    String descriptor = utf8(constantPool, input.readUnsignedShort());
                    String fieldType = descriptorType(descriptor);
                    if (!fieldName.isBlank() && !fieldName.contains("$") && !fieldType.isBlank()) {
                        fieldTypes.putIfAbsent(fieldName, fieldType);
                    }
                    skipAttributes(input);
                }
                Map<String, MethodCollectionMetadata> collectionMethods = readCollectionMethods(input, constantPool);
                return new ClassMetadata(typeName, simpleTypeName(typeName), fieldTypes, collectionMethods);
            }
        }

        private static Map<String, MethodCollectionMetadata> readCollectionMethods(
                DataInputStream input,
                Object[] constantPool
        ) throws IOException {
            int methodsCount = input.readUnsignedShort();
            Map<String, MethodCollectionMetadata> methods = new LinkedHashMap<>();
            for (int i = 0; i < methodsCount; i++) {
                input.readUnsignedShort();
                String methodName = utf8(constantPool, input.readUnsignedShort());
                input.readUnsignedShort();
                MethodAttributes attributes = readMethodAttributes(input, constantPool);
                List<String> elementTypes = collectionElementTypes(attributes.signature());
                if (!elementTypes.isEmpty()) {
                    methods.putIfAbsent(
                            methodName,
                            new MethodCollectionMetadata(elementTypes, attributes.parameterNames())
                    );
                }
            }
            return methods;
        }

        private static MethodAttributes readMethodAttributes(
                DataInputStream input,
                Object[] constantPool
        ) throws IOException {
            int attributesCount = input.readUnsignedShort();
            String signature = "";
            List<String> parameterNames = List.of();
            for (int i = 0; i < attributesCount; i++) {
                String attributeName = utf8(constantPool, input.readUnsignedShort());
                long length = Integer.toUnsignedLong(input.readInt());
                if ("Signature".equals(attributeName) && length >= 2) {
                    signature = utf8(constantPool, input.readUnsignedShort());
                    skipFully(input, length - 2);
                } else if ("MethodParameters".equals(attributeName) && length >= 1) {
                    MethodParameters methodParameters = readMethodParameters(input, constantPool, length);
                    parameterNames = methodParameters.parameterNames();
                } else {
                    skipFully(input, length);
                }
            }
            return new MethodAttributes(signature, parameterNames);
        }

        private static MethodParameters readMethodParameters(
                DataInputStream input,
                Object[] constantPool,
                long length
        ) throws IOException {
            int parametersCount = input.readUnsignedByte();
            long consumed = 1;
            List<String> parameterNames = new ArrayList<>();
            for (int i = 0; i < parametersCount; i++) {
                int nameIndex = input.readUnsignedShort();
                input.readUnsignedShort();
                consumed += 4;
                if (nameIndex > 0) {
                    parameterNames.add(utf8(constantPool, nameIndex));
                } else {
                    parameterNames.add("");
                }
            }
            skipFully(input, Math.max(0, length - consumed));
            return new MethodParameters(parameterNames);
        }

        private static List<String> collectionElementTypes(String signature) {
            if (signature == null || signature.isBlank()) {
                return List.of();
            }
            Matcher matcher = COLLECTION_ELEMENT_SIGNATURE_PATTERN.matcher(signature);
            List<String> elementTypes = new ArrayList<>();
            while (matcher.find()) {
                elementTypes.add(matcher.group(1).replace('/', '.'));
            }
            return elementTypes;
        }

        private static Object[] readConstantPool(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            Object[] constantPool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> constantPool[i] = input.readUTF();
                    case 3, 4 -> skipFully(input, 4);
                    case 5, 6 -> {
                        skipFully(input, 8);
                        i++;
                    }
                    case 7 -> constantPool[i] = new ClassInfo(input.readUnsignedShort());
                    case 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> skipFully(input, 4);
                    case 15 -> skipFully(input, 3);
                    default -> throw new IOException("Unsupported Java class constant pool tag: " + tag);
                }
            }
            return constantPool;
        }

        private static void skipAttributes(DataInputStream input) throws IOException {
            int attributesCount = input.readUnsignedShort();
            for (int i = 0; i < attributesCount; i++) {
                input.readUnsignedShort();
                skipFully(input, Integer.toUnsignedLong(input.readInt()));
            }
        }

        private static void skipFully(DataInputStream input, long bytes) throws IOException {
            long remaining = bytes;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    input.readUnsignedByte();
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }

        private static String className(Object[] constantPool, int classIndex) throws IOException {
            if (classIndex <= 0 || classIndex >= constantPool.length
                    || !(constantPool[classIndex] instanceof ClassInfo classInfo)) {
                throw new IOException("Invalid Java class name reference");
            }
            return utf8(constantPool, classInfo.nameIndex());
        }

        private static String utf8(Object[] constantPool, int index) throws IOException {
            if (index <= 0 || index >= constantPool.length || !(constantPool[index] instanceof String value)) {
                throw new IOException("Invalid Java class UTF-8 reference");
            }
            return value;
        }

        private static String descriptorType(String descriptor) {
            String normalized = descriptor == null ? "" : descriptor.trim();
            while (normalized.startsWith("[")) {
                normalized = normalized.substring(1);
            }
            if (normalized.isBlank()) {
                return "";
            }
            return switch (normalized.charAt(0)) {
                case 'B' -> "Byte";
                case 'C' -> "Character";
                case 'D' -> "Double";
                case 'F' -> "Float";
                case 'I' -> "Integer";
                case 'J' -> "Long";
                case 'S' -> "Short";
                case 'Z' -> "Boolean";
                case 'L' -> objectDescriptorType(normalized);
                default -> "";
            };
        }

        private static String objectDescriptorType(String descriptor) {
            if (!descriptor.endsWith(";") || descriptor.length() < 3) {
                return "";
            }
            return simpleTypeName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }

        private static String normalizeType(String type) {
            String normalized = type == null ? "" : type.trim();
            int genericStart = normalized.indexOf('<');
            if (genericStart >= 0) {
                normalized = normalized.substring(0, genericStart);
            }
            normalized = normalized.replace("[]", "").trim();
            int packageStart = normalized.lastIndexOf('.');
            if (packageStart >= 0) {
                normalized = normalized.substring(packageStart + 1);
            }
            return normalized;
        }

        private static String simpleTypeName(String typeName) {
            int packageStart = typeName.lastIndexOf('.');
            if (packageStart >= 0) {
                return typeName.substring(packageStart + 1);
            }
            return typeName;
        }

        private boolean isStringField(String typeName, String fieldName) {
            Map<String, String> fields = fieldsByType.get(typeName);
            if (fields == null && typeName != null && typeName.contains(".")) {
                fields = fieldsByType.get(typeName.substring(typeName.lastIndexOf('.') + 1));
            }
            if (fields == null) {
                return false;
            }
            return "String".equals(fields.get(fieldName));
        }

        private boolean isStringCollectionItemField(String mapperType, String methodName, String fieldName) {
            Map<String, MethodCollectionMetadata> methods = collectionMethodsByType.get(mapperType);
            if (methods == null && mapperType != null && mapperType.contains(".")) {
                methods = collectionMethodsByType.get(mapperType.substring(mapperType.lastIndexOf('.') + 1));
            }
            if (methods == null) {
                return false;
            }
            MethodCollectionMetadata method = methods.get(methodName);
            if (method == null || method.elementTypes().size() != 1) {
                return false;
            }
            return isStringField(method.elementTypes().get(0), fieldName);
        }

        private record ClassInfo(int nameIndex) {
        }

        private record ClassMetadata(
                String typeName,
                String simpleName,
                Map<String, String> fieldTypes,
                Map<String, MethodCollectionMetadata> collectionMethods
        ) {
        }

        private record MethodAttributes(String signature, List<String> parameterNames) {
        }

        private record MethodParameters(List<String> parameterNames) {
        }

        private record MethodCollectionMetadata(List<String> elementTypes, List<String> parameterNames) {
        }
    }
}
