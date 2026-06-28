package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.sql.SqlConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MapperAnnotationMigrator {
    public static final String MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML_RULE =
            "MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML";

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;"
    );
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b(?:public\\s+)?(?:interface|class)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b"
    );
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+((?:static\\s+)?[A-Za-z_][A-Za-z0-9_.*]*)\\s*;"
    );
    private static final Pattern SQL_ANNOTATION_PATTERN = Pattern.compile(
            "@(Select|Insert|Update|Delete)\\b"
    );
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*$"
    );
    private static final Pattern RESULT_MAPPING_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\b(?:resultType|resultMap)\\s*="
    );

    private final MapperXmlRewriter mapperXmlRewriter;

    public MapperAnnotationMigrator() {
        this(new MapperXmlRewriter());
    }

    MapperAnnotationMigrator(MapperXmlRewriter mapperXmlRewriter) {
        this.mapperXmlRewriter = mapperXmlRewriter;
    }

    public MapperMigrationResult migrate(
            ProjectScanResult scanResult,
            AdapterContext context,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        List<AnnotationStatement> annotationStatements = scanAnnotationStatements(context.projectRoot());
        if (annotationStatements.isEmpty()) {
            return new MapperMigrationResult(List.of(), List.of(), List.of(), List.of());
        }

        Map<String, Path> targetByNamespace = mapperTargetByNamespace(scanResult, context);
        Set<String> sourceStatementKeys = sourceStatementKeys(scanResult);
        Set<String> seenAnnotationKeys = new LinkedHashSet<>();
        Map<Path, List<AnnotationStatement>> statementsByTarget = new LinkedHashMap<>();
        for (AnnotationStatement statement : annotationStatements) {
            if (sourceStatementKeys.contains(statement.key()) || !seenAnnotationKeys.add(statement.key())) {
                continue;
            }
            Path target = targetByNamespace.getOrDefault(statement.namespace(), defaultTarget(context, statement));
            if (xmlHasStatement(target, statement.namespace(), statement.id())) {
                if (statementNeedsResultType(target, statement)) {
                    statementsByTarget.computeIfAbsent(target, ignored -> new ArrayList<>()).add(statement);
                }
                continue;
            }
            statementsByTarget.computeIfAbsent(target, ignored -> new ArrayList<>()).add(statement);
        }
        if (statementsByTarget.isEmpty()) {
            return new MapperMigrationResult(List.of(), List.of(), List.of(), List.of());
        }

        if (context.dryRun()) {
            return dryRunMigration(statementsByTarget, sqlConverter, rewriteConfig);
        }
        return applyMigration(statementsByTarget, sqlConverter, rewriteConfig);
    }

    private MapperMigrationResult dryRunMigration(
            Map<Path, List<AnnotationStatement>> statementsByTarget,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        List<FileChange> fileChanges = new ArrayList<>();
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        for (Map.Entry<Path, List<AnnotationStatement>> entry : statementsByTarget.entrySet()) {
            Path target = entry.getKey();
            fileChanges.add(FileChange.planned(
                    target.toString(),
                    Files.exists(target) ? "UPDATE" : "CREATE",
                    "Extract MyBatis annotation SQL to mapper-dm XML"
            ));
            for (AnnotationStatement statement : entry.getValue()) {
                SqlConversionResult conversion = sqlConverter.convert(
                        statement.sql(),
                        rewriteConfig.keyColumnsFor(statement.key(), "")
                );
                if (conversion.changed()) {
                    automaticConversions.add(new SqlChange(
                            target.toString(),
                            statement.key(),
                            statement.sql(),
                            conversion.convertedSql(),
                            withAnnotationRule(conversion.appliedRules()),
                            false,
                            ""
                    ));
                }
                if (conversion.manualReviewRequired()) {
                    manualReviewItems.add(new SqlChange(
                            target.toString(),
                            statement.key(),
                            statement.sql(),
                            conversion.convertedSql(),
                            withAnnotationRule(conversion.appliedRules()),
                            true,
                            conversion.reason()
                    ));
                }
            }
        }
        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, List.of());
    }

    private MapperMigrationResult applyMigration(
            Map<Path, List<AnnotationStatement>> statementsByTarget,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        List<FileChange> fileChanges = new ArrayList<>();
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<Path, List<AnnotationStatement>> entry : statementsByTarget.entrySet()) {
            Path target = entry.getKey();
            List<AnnotationStatement> statements = entry.getValue();
            boolean existed = Files.exists(target);
            try {
                writeAnnotationStatements(target, statements);
                fileChanges.add(FileChange.applied(
                        target.toString(),
                        existed ? "UPDATE" : "CREATE",
                        "Extracted MyBatis annotation SQL to mapper-dm XML"
                ));
                MapperRewriteResult rewriteResult = mapperXmlRewriter.rewrite(
                        target,
                        target.toString(),
                        true,
                        sqlConverter,
                        rewriteConfig
                );
                automaticConversions.addAll(annotationConversions(rewriteResult.automaticConversions(), statements));
                manualReviewItems.addAll(annotationConversions(rewriteResult.manualReviewItems(), statements));
                warnings.addAll(rewriteResult.warnings());
            } catch (Exception e) {
                warnings.add("Failed to extract MyBatis annotation SQL into " + target + ": " + e.getMessage());
            }
        }

        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private List<SqlChange> annotationConversions(List<SqlChange> changes, List<AnnotationStatement> statements) {
        Set<String> annotationKeys = new LinkedHashSet<>();
        for (AnnotationStatement statement : statements) {
            annotationKeys.add(statement.key());
        }
        List<SqlChange> result = new ArrayList<>();
        for (SqlChange change : changes) {
            if (!annotationKeys.contains(change.statementId())) {
                continue;
            }
            result.add(new SqlChange(
                    change.file(),
                    change.statementId(),
                    change.originalSql(),
                    change.convertedSql(),
                    withAnnotationRule(change.appliedRules()),
                    change.manualReviewRequired(),
                    change.reason()
            ));
        }
        return result;
    }

    private List<String> withAnnotationRule(List<String> rules) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add(MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML_RULE);
        if (rules != null) {
            merged.addAll(rules);
        }
        return new ArrayList<>(merged);
    }

    private void writeAnnotationStatements(Path target, List<AnnotationStatement> statements) throws IOException {
        Files.createDirectories(target.getParent());
        String namespace = statements.get(0).namespace();
        if (!Files.exists(target)) {
            Files.writeString(target, newMapperXml(namespace), StandardCharsets.UTF_8);
        }
        String xml = Files.readString(target, StandardCharsets.UTF_8);
        int mapperEnd = xml.lastIndexOf("</mapper>");
        if (mapperEnd < 0) {
            throw new IllegalStateException("Target mapper XML is missing </mapper>.");
        }
        StringBuilder additions = new StringBuilder();
        for (AnnotationStatement statement : statements) {
            StatementXmlUpdate update = updateExistingStatementMetadata(xml, statement);
            if (update.found()) {
                xml = update.xml();
                continue;
            }
            additions.append(statementXml(statement));
        }
        if (!additions.isEmpty()) {
            mapperEnd = xml.lastIndexOf("</mapper>");
            if (mapperEnd < 0) {
                throw new IllegalStateException("Target mapper XML is missing </mapper>.");
            }
            xml = xml.substring(0, mapperEnd) + additions + xml.substring(mapperEnd);
        }
        Files.writeString(target, xml, StandardCharsets.UTF_8);
    }

    private String newMapperXml(String namespace) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                </mapper>
                """.formatted(namespace);
    }

    private String statementXml(AnnotationStatement statement) {
        String sql = statement.sql().trim();
        String body;
        if (isScriptSql(sql)) {
            body = indent(stripScript(sql), "        ");
        } else {
            body = "        <![CDATA[\n"
                    + indent(sql, "            ")
                    + "\n        ]]>";
        }
        String resultType = "";
        if ("select".equals(statement.tagName()) && !statement.resultType().isBlank()) {
            resultType = " resultType=\"" + escapeXmlAttribute(statement.resultType()) + "\"";
        }
        return "\n    <" + statement.tagName() + " id=\"" + escapeXmlAttribute(statement.id()) + "\"" + resultType + ">\n"
                + body
                + "\n    </" + statement.tagName() + ">\n";
    }

    private boolean isScriptSql(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        return Pattern.compile("(?is)^<\\s*script\\b").matcher(trimmed).find();
    }

    private String stripScript(String sql) {
        String stripped = Pattern.compile("(?is)^\\s*<\\s*script\\b[^>]*>").matcher(sql).replaceFirst("");
        stripped = Pattern.compile("(?is)</\\s*script\\s*>\\s*$").matcher(stripped).replaceFirst("");
        return stripped.trim();
    }

    private String indent(String value, String indentation) {
        return Pattern.compile("\\R").splitAsStream(value == null ? "" : value)
                .map(line -> indentation + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(indentation);
    }

    private String escapeXmlAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private Map<String, Path> mapperTargetByNamespace(ProjectScanResult scanResult, AdapterContext context) {
        Map<String, Path> targetByNamespace = new LinkedHashMap<>();
        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            String namespace = namespace(Paths.get(mapperXmlFile.path()));
            if (namespace.isBlank()) {
                continue;
            }
            targetByNamespace.put(namespace, mapperTarget(context, mapperXmlFile));
        }
        return targetByNamespace;
    }

    private Set<String> sourceStatementKeys(ProjectScanResult scanResult) {
        Set<String> keys = new LinkedHashSet<>();
        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            keys.addAll(statementKeys(Paths.get(mapperXmlFile.path())));
        }
        return keys;
    }

    private Path mapperTarget(AdapterContext context, MapperXmlFile mapperXmlFile) {
        Path targetDir;
        if (!mapperXmlFile.resourcesRoot().isBlank() && context.mapperTargetDir().equals(context.defaultMapperTargetDir())) {
            targetDir = Paths.get(mapperXmlFile.resourcesRoot()).resolve("mapper-dm");
        } else {
            targetDir = context.mapperTargetDir();
        }
        return targetDir.resolve(toMapperDmRelativePath(mapperXmlFile.resourcesRelativePath()));
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

    private Path defaultTarget(AdapterContext context, AnnotationStatement statement) {
        Path moduleRoot = statement.moduleRoot();
        if (context.mapperTargetDir().equals(context.defaultMapperTargetDir())) {
            return moduleRoot.resolve("src/main/resources/mapper-dm/" + statement.simpleName() + ".xml");
        }
        return context.mapperTargetDir().resolve(statement.simpleName() + ".xml");
    }

    private boolean xmlHasStatement(Path mapperXml, String namespace, String statementId) {
        if (!Files.isRegularFile(mapperXml)) {
            return false;
        }
        return statementKeys(mapperXml).contains(namespace + "." + statementId);
    }

    private boolean statementNeedsResultType(Path mapperXml, AnnotationStatement statement) {
        if (!"select".equals(statement.tagName()) || statement.resultType().isBlank() || !Files.isRegularFile(mapperXml)) {
            return false;
        }
        try {
            String xml = Files.readString(mapperXml, StandardCharsets.UTF_8);
            return startTag(xml, statement)
                    .map(tag -> !RESULT_MAPPING_ATTRIBUTE_PATTERN.matcher(tag).find())
                    .orElse(false);
        } catch (IOException e) {
            return false;
        }
    }

    private StatementXmlUpdate updateExistingStatementMetadata(String xml, AnnotationStatement statement) {
        if (!"select".equals(statement.tagName()) || statement.resultType().isBlank()) {
            return new StatementXmlUpdate(xml, false);
        }
        Matcher matcher = startTagPattern(statement).matcher(xml);
        if (!matcher.find()) {
            return new StatementXmlUpdate(xml, false);
        }
        String startTag = matcher.group();
        if (RESULT_MAPPING_ATTRIBUTE_PATTERN.matcher(startTag).find()) {
            return new StatementXmlUpdate(xml, true);
        }
        int insertAt = startTag.endsWith("/>") ? startTag.length() - 2 : startTag.length() - 1;
        String updatedTag = startTag.substring(0, insertAt)
                + " resultType=\"" + escapeXmlAttribute(statement.resultType()) + "\""
                + startTag.substring(insertAt);
        return new StatementXmlUpdate(xml.substring(0, matcher.start()) + updatedTag + xml.substring(matcher.end()), true);
    }

    private java.util.Optional<String> startTag(String xml, AnnotationStatement statement) {
        Matcher matcher = startTagPattern(statement).matcher(xml);
        return matcher.find() ? java.util.Optional.of(matcher.group()) : java.util.Optional.empty();
    }

    private Pattern startTagPattern(AnnotationStatement statement) {
        String idPattern = "(?:\"" + Pattern.quote(statement.id()) + "\"|'" + Pattern.quote(statement.id()) + "')";
        return Pattern.compile(
                "<\\s*" + Pattern.quote(statement.tagName()) + "\\b(?=[^>]*\\bid\\s*=\\s*" + idPattern + ")[^>]*>",
                Pattern.DOTALL
        );
    }

    private Set<String> statementKeys(Path mapperXml) {
        Set<String> keys = new LinkedHashSet<>();
        if (!Files.isRegularFile(mapperXml)) {
            return keys;
        }
        try {
            Document document = XmlSupport.parse(mapperXml);
            Element root = document.getDocumentElement();
            if (root == null || !"mapper".equals(root.getTagName())) {
                return keys;
            }
            String namespace = root.getAttribute("namespace");
            if (namespace == null || namespace.isBlank()) {
                return keys;
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && isStatementElement(element)) {
                    String id = element.getAttribute("id");
                    if (id != null && !id.isBlank()) {
                        keys.add(namespace + "." + id);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return keys;
    }

    private String namespace(Path mapperXml) {
        if (!Files.isRegularFile(mapperXml)) {
            return "";
        }
        try {
            Document document = XmlSupport.parse(mapperXml);
            Element root = document.getDocumentElement();
            return root == null || !"mapper".equals(root.getTagName()) ? "" : root.getAttribute("namespace");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isStatementElement(Element element) {
        String tagName = element.getTagName();
        return "select".equals(tagName)
                || "insert".equals(tagName)
                || "update".equals(tagName)
                || "delete".equals(tagName);
    }

    private List<AnnotationStatement> scanAnnotationStatements(Path projectRoot) {
        List<AnnotationStatement> statements = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(this::isMainJavaSource)
                    .filter(path -> !isIgnoredPath(projectRoot, path))
                    .sorted()
                    .forEach(path -> statements.addAll(scanJavaFile(path)));
        } catch (IOException ignored) {
        }
        statements.sort(Comparator.comparing(AnnotationStatement::key));
        return statements;
    }

    private boolean isMainJavaSource(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/main/java/");
    }

    private boolean isIgnoredPath(Path projectRoot, Path path) {
        String normalized = projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return normalized.contains("/target/")
                || normalized.contains("/.git/")
                || normalized.contains("/.idea/")
                || normalized.contains("/mapper-dm/");
    }

    private List<AnnotationStatement> scanJavaFile(Path javaFile) {
        String source;
        try {
            source = Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        if (!source.contains("@Select")
                && !source.contains("@Insert")
                && !source.contains("@Update")
                && !source.contains("@Delete")) {
            return List.of();
        }
        String packageName = firstGroup(PACKAGE_PATTERN.matcher(source));
        String simpleName = firstGroup(TYPE_PATTERN.matcher(source));
        if (packageName.isBlank() || simpleName.isBlank()) {
            return List.of();
        }
        Map<String, String> imports = imports(source);
        Path moduleRoot = moduleRoot(javaFile);
        String namespace = packageName + "." + simpleName;
        List<AnnotationStatement> statements = new ArrayList<>();
        Matcher matcher = SQL_ANNOTATION_PATTERN.matcher(source);
        while (matcher.find()) {
            String annotationName = matcher.group(1);
            int expressionStart = skipWhitespace(source, matcher.end());
            if (expressionStart >= source.length() || source.charAt(expressionStart) != '(') {
                continue;
            }
            int expressionEnd = matchingParenthesis(source, expressionStart);
            if (expressionEnd < 0) {
                continue;
            }
            String expression = source.substring(expressionStart + 1, expressionEnd);
            String sql = annotationSql(expression);
            if (sql.isBlank()) {
                continue;
            }
            int methodStart = skipAnnotationsAndWhitespace(source, expressionEnd + 1);
            int methodParen = source.indexOf('(', methodStart);
            if (methodParen < 0) {
                continue;
            }
            String signaturePrefix = source.substring(methodStart, methodParen);
            String methodName = methodName(signaturePrefix);
            if (methodName.isBlank()) {
                continue;
            }
            String resultType = "Select".equals(annotationName)
                    ? resultType(signaturePrefix, methodName, packageName, imports)
                    : "";
            statements.add(new AnnotationStatement(
                    namespace,
                    simpleName,
                    methodName,
                    tagName(annotationName),
                    sql,
                    resultType,
                    moduleRoot
            ));
        }
        return statements;
    }

    private Map<String, String> imports(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.startsWith("static ") || name.endsWith(".*")) {
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot + 1 < name.length()) {
                imports.put(name.substring(dot + 1), name);
            }
        }
        return imports;
    }

    private String firstGroup(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : "";
    }

    private Path moduleRoot(Path javaFile) {
        String normalized = javaFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        int marker = normalized.indexOf("/src/main/java/");
        if (marker < 0) {
            return javaFile.toAbsolutePath().normalize().getParent();
        }
        return Paths.get(normalized.substring(0, marker));
    }

    private int skipAnnotationsAndWhitespace(String source, int index) {
        int current = skipWhitespaceAndComments(source, index);
        while (current < source.length() && source.charAt(current) == '@') {
            Matcher matcher = Pattern.compile("@[A-Za-z_][A-Za-z0-9_.]*").matcher(source);
            matcher.region(current, source.length());
            if (!matcher.find()) {
                return current;
            }
            current = skipWhitespaceAndComments(source, matcher.end());
            if (current < source.length() && source.charAt(current) == '(') {
                int end = matchingParenthesis(source, current);
                if (end < 0) {
                    return current;
                }
                current = end + 1;
            }
            current = skipWhitespaceAndComments(source, current);
        }
        return current;
    }

    private int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length() && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private int skipWhitespaceAndComments(String source, int index) {
        int current = skipWhitespace(source, index);
        while (current + 1 < source.length()) {
            if (source.charAt(current) == '/' && source.charAt(current + 1) == '/') {
                int end = source.indexOf('\n', current + 2);
                current = skipWhitespace(source, end < 0 ? source.length() : end + 1);
                continue;
            }
            if (source.charAt(current) == '/' && source.charAt(current + 1) == '*') {
                int end = source.indexOf("*/", current + 2);
                current = skipWhitespace(source, end < 0 ? source.length() : end + 2);
                continue;
            }
            break;
        }
        return current;
    }

    private int matchingParenthesis(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '"') {
                if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                    i = textBlockEnd(source, i + 3);
                } else {
                    i = stringEnd(source, i + 1);
                }
                continue;
            }
            if (current == '\'') {
                i = charEnd(source, i + 1);
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int stringEnd(String source, int index) {
        boolean escaped = false;
        for (int i = index; i < source.length(); i++) {
            char current = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                return i;
            }
        }
        return source.length() - 1;
    }

    private int charEnd(String source, int index) {
        boolean escaped = false;
        for (int i = index; i < source.length(); i++) {
            char current = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '\'') {
                return i;
            }
        }
        return source.length() - 1;
    }

    private int textBlockEnd(String source, int index) {
        int end = source.indexOf("\"\"\"", index);
        return end < 0 ? source.length() - 1 : end + 2;
    }

    private String annotationSql(String expression) {
        StringBuilder sql = new StringBuilder();
        int lastEnd = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) != '"') {
                continue;
            }
            int literalStart = i;
            String value;
            int literalEnd;
            if (i + 2 < expression.length()
                    && expression.charAt(i + 1) == '"'
                    && expression.charAt(i + 2) == '"') {
                literalEnd = textBlockEnd(expression, i + 3);
                value = expression.substring(i + 3, Math.max(i + 3, literalEnd - 2));
            } else {
                literalEnd = stringEnd(expression, i + 1);
                value = unescapeJavaString(expression.substring(i + 1, literalEnd));
            }
            if (sql.length() > 0 && expression.substring(lastEnd, literalStart).contains(",")) {
                sql.append('\n');
            }
            sql.append(value);
            lastEnd = literalEnd + 1;
            i = literalEnd;
        }
        return sql.toString().trim();
    }

    private String unescapeJavaString(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    result.append(current);
                }
                continue;
            }
            switch (current) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case '"' -> result.append('"');
                case '\'' -> result.append('\'');
                case '\\' -> result.append('\\');
                default -> result.append(current);
            }
            escaped = false;
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    private String methodName(String signaturePrefix) {
        Matcher matcher = METHOD_NAME_PATTERN.matcher(signaturePrefix);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String resultType(
            String signaturePrefix,
            String methodName,
            String packageName,
            Map<String, String> imports
    ) {
        String prefix = signaturePrefix.substring(0, signaturePrefix.length() - methodName.length()).trim();
        String declaredType = stripMethodModifiers(prefix);
        if (declaredType.isBlank() || "void".equals(declaredType)) {
            return "";
        }
        String rowType = rowResultType(declaredType);
        if (rowType.isBlank() || isTypeVariable(rowType)) {
            return "java.lang.Object";
        }
        return resolveType(rowType, packageName, imports);
    }

    private String stripMethodModifiers(String value) {
        String result = value.trim();
        result = Pattern.compile("^(?:(?:public|protected|private|abstract|default|static|final|synchronized|native|strictfp)\\s+)+")
                .matcher(result)
                .replaceAll("");
        while (result.startsWith("<")) {
            int end = matchingGenericEnd(result, 0);
            if (end < 0) {
                break;
            }
            result = result.substring(end + 1).trim();
        }
        return result;
    }

    private String rowResultType(String declaredType) {
        String type = normalizeType(declaredType);
        String raw = rawType(type);
        if (isCollectionType(raw) || "java.util.Optional".equals(raw) || "Optional".equals(raw)) {
            String inner = firstGenericArgument(type);
            return inner.isBlank() ? "java.lang.Object" : rowResultType(inner);
        }
        if ("Map".equals(raw) || "java.util.Map".equals(raw)) {
            return "java.util.Map";
        }
        return type;
    }

    private String normalizeType(String value) {
        String type = value.trim();
        type = Pattern.compile("^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)]*\\))?\\s+)+")
                .matcher(type)
                .replaceAll("");
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2).trim();
        }
        if (type.startsWith("? extends ")) {
            type = type.substring("? extends ".length()).trim();
        } else if (type.startsWith("? super ")) {
            type = type.substring("? super ".length()).trim();
        }
        return type;
    }

    private String rawType(String type) {
        int generic = type.indexOf('<');
        return generic < 0 ? type : type.substring(0, generic).trim();
    }

    private boolean isCollectionType(String rawType) {
        return Set.of(
                "List", "java.util.List",
                "Collection", "java.util.Collection",
                "Set", "java.util.Set",
                "Iterable", "java.lang.Iterable",
                "Iterator", "java.util.Iterator",
                "Cursor", "org.apache.ibatis.cursor.Cursor"
        ).contains(rawType);
    }

    private String firstGenericArgument(String type) {
        int open = type.indexOf('<');
        if (open < 0) {
            return "";
        }
        int close = matchingGenericEnd(type, open);
        if (close < 0) {
            return "";
        }
        String content = type.substring(open + 1, close).trim();
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
            } else if (current == ',' && depth == 0) {
                return content.substring(0, i).trim();
            }
        }
        return content;
    }

    private int matchingGenericEnd(String value, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean isTypeVariable(String type) {
        return !type.contains(".")
                && type.length() == 1
                && Character.isUpperCase(type.charAt(0));
    }

    private String resolveType(String type, String packageName, Map<String, String> imports) {
        String raw = rawType(type);
        return switch (raw) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "char" -> "java.lang.Character";
            case "String" -> "java.lang.String";
            case "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character", "Object" ->
                    "java.lang." + raw;
            case "BigDecimal" -> "java.math.BigDecimal";
            case "BigInteger" -> "java.math.BigInteger";
            case "Date" -> imports.getOrDefault("Date", "java.util.Date");
            case "LocalDate" -> "java.time.LocalDate";
            case "LocalDateTime" -> "java.time.LocalDateTime";
            case "Map" -> "java.util.Map";
            default -> {
                if (raw.contains(".")) {
                    yield raw;
                }
                yield imports.getOrDefault(raw, packageName + "." + raw);
            }
        };
    }

    private String tagName(String annotationName) {
        return annotationName.toLowerCase(Locale.ROOT);
    }

    private record AnnotationStatement(
            String namespace,
            String simpleName,
            String id,
            String tagName,
            String sql,
            String resultType,
            Path moduleRoot
    ) {
        private String key() {
            return namespace + "." + id;
        }
    }

    private record StatementXmlUpdate(String xml, boolean found) {
    }
}
