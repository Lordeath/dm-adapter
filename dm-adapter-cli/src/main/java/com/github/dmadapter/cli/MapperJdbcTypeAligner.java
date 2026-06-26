package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;

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
    private static final Pattern TRIM_PATTERN = Pattern.compile("(?is)<trim\\b([^>]*)>([\\s\\S]*?)</trim>");
    private static final Pattern IF_PATTERN = Pattern.compile("(?is)<if\\b[^>]*>([\\s\\S]*?)</if>");

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
        StringBuffer output = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            Alignment statement = alignStatement(matcher.group(), columnTypes, javaFieldTypes);
            replacements += statement.replacements();
            matcher.appendReplacement(output, Matcher.quoteReplacement(statement.text()));
        }
        matcher.appendTail(output);
        return new Alignment(output.toString(), replacements);
    }

    private Alignment alignStatement(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        String parameterType = statementAttribute(statement, "parameterType");
        Alignment current = alignUpdatePlaceholders(statement, columnTypes, parameterType, javaFieldTypes);
        Alignment simpleInsert = alignSimpleInsertPlaceholders(current.text(), columnTypes, parameterType, javaFieldTypes);
        Alignment structuredInsert = alignStructuredInsertPlaceholders(simpleInsert.text(), columnTypes, parameterType, javaFieldTypes);
        return new Alignment(
                structuredInsert.text(),
                current.replacements() + simpleInsert.replacements() + structuredInsert.replacements()
        );
    }

    private Alignment alignUpdatePlaceholders(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            String parameterType,
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
                    isStringParameterExpression(matcher.group(expressionGroup), parameterType, javaFieldTypes)
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
        return alignPlaceholdersByExpression(statement, expressionColumnTypes, parameterType, javaFieldTypes);
    }

    private Alignment alignStructuredInsertPlaceholders(
            String statement,
            Map<String, Map<String, String>> columnTypes,
            String parameterType,
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
        return alignPlaceholdersByExpression(statement, expressionColumnTypes, parameterType, javaFieldTypes);
    }

    private Alignment alignPlaceholdersByExpression(
            String text,
            Map<String, String> expressionColumnTypes,
            String parameterType,
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
                    isStringParameterExpression(expression, parameterType, javaFieldTypes)
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
            JavaFieldTypeMetadata javaFieldTypes
    ) {
        if (javaFieldTypes == null || parameterType == null || parameterType.isBlank()) {
            return false;
        }
        List<String> parts = Pattern.compile("\\.")
                .splitAsStream(expression == null ? "" : expression.trim())
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() != 1) {
            return false;
        }
        return javaFieldTypes.isStringField(parameterType, parts.get(0));
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

        private final Map<String, Map<String, String>> fieldsByType;

        private JavaFieldTypeMetadata(Map<String, Map<String, String>> fieldsByType) {
            this.fieldsByType = fieldsByType;
        }

        private static JavaFieldTypeMetadata empty() {
            return new JavaFieldTypeMetadata(Map.of());
        }

        private static JavaFieldTypeMetadata load(Path projectRoot) throws IOException {
            if (projectRoot == null || !Files.isDirectory(projectRoot)) {
                return empty();
            }
            Map<String, Map<String, String>> fields = new LinkedHashMap<>();
            try (Stream<Path> paths = Files.walk(projectRoot)) {
                List<Path> javaFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(JavaFieldTypeMetadata::isMainJavaSource)
                        .toList();
                for (Path javaFile : javaFiles) {
                    try {
                        addJavaFile(fields, javaFile);
                    } catch (IOException ignored) {
                        // Keep usable source metadata when one project file is unreadable or not UTF-8.
                    }
                }
            }
            return new JavaFieldTypeMetadata(fields);
        }

        private static boolean isMainJavaSource(Path path) {
            String normalized = path.toString().replace('\\', '/');
            return normalized.contains("/src/main/java/");
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
    }
}
