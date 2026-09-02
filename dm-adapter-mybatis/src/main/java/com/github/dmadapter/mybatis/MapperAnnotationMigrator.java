package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.sql.SqlConverter;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MapperAnnotationMigrator {
    public static final String MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML_RULE =
            "MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML";

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+((?:static\\s+)?[A-Za-z_][A-Za-z0-9_.*]*)\\s*;"
    );
    private static final Pattern RESULT_MAPPING_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\b(?:resultType|resultMap)\\s*="
    );
    private static final Pattern UPDATE_SET_PATTERN = Pattern.compile(
            "(?is)\\bupdate\\b[\\s\\S]*?\\bset\\b"
    );
    private static final Pattern WHERE_PATTERN = Pattern.compile("(?is)\\bwhere\\b");
    private static final Pattern MISSING_UPDATE_SET_COMMA_PATTERN = Pattern.compile(
            "([#$]\\{[^}]+})\\s+(?=(?:[`\"]?[A-Za-z_][A-Za-z0-9_.$]*[`\"]?\\s*=))"
    );
    private static final Map<String, String> SQL_ANNOTATION_TAGS = Map.of(
            "Lorg/apache/ibatis/annotations/Select;", "select",
            "Lorg/apache/ibatis/annotations/Insert;", "insert",
            "Lorg/apache/ibatis/annotations/Update;", "update",
            "Lorg/apache/ibatis/annotations/Delete;", "delete"
    );
    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private final MapperXmlRewriter mapperXmlRewriter;
    private final Consumer<String> progressLogger;

    public MapperAnnotationMigrator() {
        this(new MapperXmlRewriter(), message -> {
        });
    }

    public MapperAnnotationMigrator(Consumer<String> progressLogger) {
        this(new MapperXmlRewriter(), progressLogger);
    }

    MapperAnnotationMigrator(MapperXmlRewriter mapperXmlRewriter) {
        this(mapperXmlRewriter, message -> {
        });
    }

    MapperAnnotationMigrator(MapperXmlRewriter mapperXmlRewriter, Consumer<String> progressLogger) {
        this.mapperXmlRewriter = mapperXmlRewriter;
        this.progressLogger = progressLogger == null ? message -> {
        } : progressLogger;
    }

    public MapperMigrationResult migrate(
            ProjectScanResult scanResult,
            AdapterContext context,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        progress("Scanning MyBatis annotation SQL under " + context.projectRoot() + "...");
        List<AnnotationStatement> annotationStatements = scanAnnotationStatements(context.projectRoot());
        progress("MyBatis annotation SQL statements discovered: " + annotationStatements.size());
        if (annotationStatements.isEmpty()) {
            progress("Annotation SQL migration skipped: no annotation SQL statements found.");
            return new MapperMigrationResult(List.of(), List.of(), List.of(), List.of());
        }

        Map<String, Path> sourceByNamespace = mapperSourceByNamespace(scanResult);
        Map<String, Path> targetByNamespace = mapperTargetByNamespace(scanResult, context);
        Set<String> sourceStatementKeys = sourceStatementKeys(scanResult);
        Map<Path, List<AnnotationStatement>> statementsBySource = new LinkedHashMap<>();
        Map<Path, List<AnnotationStatement>> existingStatementsBySource = new LinkedHashMap<>();
        Set<String> seenSourceAnnotationKeys = new LinkedHashSet<>();
        for (AnnotationStatement statement : annotationStatements) {
            if (!seenSourceAnnotationKeys.add(statement.key())) {
                continue;
            }
            Path source = sourceByNamespace.getOrDefault(statement.namespace(), defaultSourceTarget(statement));
            if (xmlHasStatement(source, statement.namespace(), statement.id())) {
                existingStatementsBySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(statement);
                continue;
            }
            statementsBySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(statement);
        }

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
        progress("Annotation SQL migration planning completed. Source mapper files: " + statementsBySource.size()
                + ", existing source mapper files: " + existingStatementsBySource.size()
                + ", target mapper-dm files: " + statementsByTarget.size());
        if (statementsBySource.isEmpty() && existingStatementsBySource.isEmpty() && statementsByTarget.isEmpty()) {
            progress("Annotation SQL migration skipped: no mapper updates required.");
            return new MapperMigrationResult(List.of(), List.of(), List.of(), List.of());
        }

        MapperMigrationResult result;
        if (context.dryRun()) {
            result = combine(
                    dryRunSourceMigration(statementsBySource, existingStatementsBySource),
                    dryRunMigration(statementsByTarget, sqlConverter, rewriteConfig)
            );
        } else {
            result = combine(
                    applySourceMigration(statementsBySource, existingStatementsBySource),
                    applyMigration(statementsByTarget, sqlConverter, rewriteConfig)
            );
        }
        progress("Annotation SQL migration finished. File changes: " + result.fileChanges().size()
                + ", automatic conversions: " + result.automaticConversions().size()
                + ", manual review: " + result.manualReviewItems().size());
        return result;
    }

    private void progress(String message) {
        progressLogger.accept(message);
    }

    private MapperMigrationResult combine(MapperMigrationResult left, MapperMigrationResult right) {
        List<FileChange> fileChanges = new ArrayList<>();
        fileChanges.addAll(left.fileChanges());
        fileChanges.addAll(right.fileChanges());
        List<SqlChange> automaticConversions = new ArrayList<>();
        automaticConversions.addAll(left.automaticConversions());
        automaticConversions.addAll(right.automaticConversions());
        List<SqlChange> manualReviewItems = new ArrayList<>();
        manualReviewItems.addAll(left.manualReviewItems());
        manualReviewItems.addAll(right.manualReviewItems());
        List<String> warnings = new ArrayList<>();
        warnings.addAll(left.warnings());
        warnings.addAll(right.warnings());
        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private MapperMigrationResult dryRunSourceMigration(
            Map<Path, List<AnnotationStatement>> statementsBySource,
            Map<Path, List<AnnotationStatement>> existingStatementsBySource
    ) {
        List<FileChange> fileChanges = new ArrayList<>();
        for (Path source : statementsBySource.keySet()) {
            fileChanges.add(FileChange.planned(
                    source.toString(),
                    Files.exists(source) ? "UPDATE" : "CREATE",
                    "计划将 MyBatis 注解 SQL 提取到 Mapper XML"
            ));
        }
        for (List<AnnotationStatement> statements : existingStatementsBySource.values()) {
            for (Path javaSource : javaSources(statements)) {
                fileChanges.add(FileChange.planned(
                        javaSource.toString(),
                        "UPDATE",
                        "计划从 Java Mapper 中移除已提取的 MyBatis 注解 SQL"
                ));
            }
        }
        return new MapperMigrationResult(fileChanges, List.of(), List.of(), List.of());
    }

    private MapperMigrationResult applySourceMigration(
            Map<Path, List<AnnotationStatement>> statementsBySource,
            Map<Path, List<AnnotationStatement>> existingStatementsBySource
    ) {
        List<FileChange> fileChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Path, List<AnnotationStatement>> statementsToClean = new LinkedHashMap<>(existingStatementsBySource);
        for (Map.Entry<Path, List<AnnotationStatement>> entry : existingStatementsBySource.entrySet()) {
            Path source = entry.getKey();
            progress("Repairing extracted annotation SQL in mapper XML: " + source);
            try {
                if (repairExistingAnnotationSql(source, entry.getValue())) {
                    fileChanges.add(FileChange.applied(
                            source.toString(),
                            "UPDATE",
                            "已修复 Mapper XML 中提取出的 MyBatis 注解 SQL"
                    ));
                }
            } catch (Exception e) {
                warnings.add("修复 Mapper XML 中提取出的 MyBatis 注解 SQL 失败：" + source + "：" + e.getMessage());
            }
        }
        for (Map.Entry<Path, List<AnnotationStatement>> entry : statementsBySource.entrySet()) {
            Path source = entry.getKey();
            progress("Extracting annotation SQL to source mapper XML: " + source
                    + " (" + entry.getValue().size() + " statements)");
            boolean existed = Files.exists(source);
            try {
                writeAnnotationStatements(source, entry.getValue());
                fileChanges.add(FileChange.applied(
                        source.toString(),
                        existed ? "UPDATE" : "CREATE",
                        "已将 MyBatis 注解 SQL 提取到 Mapper XML"
                ));
                statementsToClean.computeIfAbsent(source, ignored -> new ArrayList<>()).addAll(entry.getValue());
            } catch (Exception e) {
                warnings.add("将 MyBatis 注解 SQL 提取到 Mapper XML 失败：" + source + "：" + e.getMessage());
            }
        }
        try {
            fileChanges.addAll(removeJavaSqlAnnotations(statementsToClean.values().stream()
                    .flatMap(List::stream)
                    .toList()));
        } catch (Exception e) {
            warnings.add("从 Java Mapper 中移除已提取的 MyBatis 注解 SQL 失败：" + e.getMessage());
        }
        return new MapperMigrationResult(fileChanges, List.of(), List.of(), warnings);
    }

    private boolean repairExistingAnnotationSql(Path mapperXml, List<AnnotationStatement> statements) throws IOException {
        if (!Files.isRegularFile(mapperXml)) {
            return false;
        }
        String xml = Files.readString(mapperXml, StandardCharsets.UTF_8);
        String updated = xml;
        for (AnnotationStatement statement : statements) {
            if (!"update".equals(statement.tagName())) {
                continue;
            }
            updated = repairExistingStatementSql(updated, statement);
        }
        if (updated.equals(xml)) {
            return false;
        }
        Files.writeString(mapperXml, updated, StandardCharsets.UTF_8);
        return true;
    }

    private String repairExistingStatementSql(String xml, AnnotationStatement statement) {
        Matcher matcher = startTagPattern(statement).matcher(xml);
        StringBuilder updated = new StringBuilder();
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            int bodyStart = matcher.end();
            Matcher closing = Pattern.compile("(?is)</\\s*" + Pattern.quote(statement.tagName()) + "\\s*>")
                    .matcher(xml);
            closing.region(bodyStart, xml.length());
            if (!closing.find()) {
                break;
            }
            String body = xml.substring(bodyStart, closing.start());
            String fixedBody = fixMissingUpdateSetCommas(body);
            if (!fixedBody.equals(body)) {
                updated.append(xml, cursor, bodyStart).append(fixedBody);
                cursor = closing.start();
                changed = true;
            }
            matcher.region(closing.end(), xml.length());
        }
        if (!changed) {
            return xml;
        }
        updated.append(xml.substring(cursor));
        return updated.toString();
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
                    "计划将 MyBatis 注解 SQL 提取到 mapper-dm XML"
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
                            rewriteConfig.resolveUpsertManualReviewReason(
                                    statement.key(),
                                    conversion.reason()
                            )
                    ));
                }
                if ("select".equals(statement.tagName())
                        && !statement.resultType().isBlank()
                        && mapperXmlRewriter.hasPhysicalReservedResultColumn(conversion.convertedSql())) {
                    manualReviewItems.add(new SqlChange(
                            target.toString(),
                            statement.key(),
                            statement.sql(),
                            conversion.convertedSql(),
                            withAnnotationRule(conversion.appliedRules()),
                            true,
                            MapperXmlRewriter.RESULT_TYPE_RESERVED_COLUMN_REASON
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
            progress("Extracting annotation SQL to mapper-dm XML: " + target
                    + " (" + statements.size() + " statements)");
            boolean existed = Files.exists(target);
            try {
                writeAnnotationStatements(target, statements);
                fileChanges.add(FileChange.applied(
                        target.toString(),
                        existed ? "UPDATE" : "CREATE",
                        "已将 MyBatis 注解 SQL 提取到 mapper-dm XML"
                ));
                MapperRewriteResult rewriteResult = mapperXmlRewriter.rewrite(
                        target,
                        target.toString(),
                        true,
                        sqlConverter,
                        rewriteConfig,
                        annotationKeys(statements)
                );
                automaticConversions.addAll(annotationConversions(rewriteResult.automaticConversions(), statements));
                manualReviewItems.addAll(annotationConversions(rewriteResult.manualReviewItems(), statements));
                warnings.addAll(rewriteResult.warnings());
            } catch (Exception e) {
                warnings.add("将 MyBatis 注解 SQL 提取到 Mapper XML 失败：" + target + "：" + e.getMessage());
            }
        }

        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private List<FileChange> removeJavaSqlAnnotations(List<AnnotationStatement> statements) throws IOException {
        Map<Path, List<AnnotationStatement>> statementsByJavaFile = new LinkedHashMap<>();
        for (AnnotationStatement statement : statements) {
            if (statement.javaSource() != null) {
                statementsByJavaFile.computeIfAbsent(statement.javaSource(), ignored -> new ArrayList<>()).add(statement);
            }
        }
        if (statementsByJavaFile.isEmpty()) {
            return List.of();
        }
        List<FileChange> fileChanges = new ArrayList<>();
        for (Map.Entry<Path, List<AnnotationStatement>> entry : statementsByJavaFile.entrySet()) {
            Path javaFile = entry.getKey();
            progress("Removing extracted annotation SQL from Java mapper: " + javaFile);
            if (!Files.isRegularFile(javaFile)) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String updated = removeJavaSqlAnnotations(source, entry.getValue());
            if (!updated.equals(source)) {
                Files.writeString(javaFile, updated, StandardCharsets.UTF_8);
                fileChanges.add(FileChange.applied(
                        javaFile.toString(),
                        "UPDATE",
                        "已从 Java Mapper 中移除提取后的 MyBatis 注解 SQL"
                ));
            }
        }
        return fileChanges;
    }

    private Set<Path> javaSources(List<AnnotationStatement> statements) {
        Set<Path> javaSources = new LinkedHashSet<>();
        for (AnnotationStatement statement : statements) {
            if (statement.javaSource() != null) {
                javaSources.add(statement.javaSource());
            }
        }
        return javaSources;
    }

    private String removeJavaSqlAnnotations(String source, List<AnnotationStatement> statements) {
        Map<String, Set<String>> tagsByMethod = new LinkedHashMap<>();
        for (AnnotationStatement statement : statements) {
            tagsByMethod.computeIfAbsent(statement.id(), ignored -> new LinkedHashSet<>()).add(statement.tagName());
        }
        if (tagsByMethod.isEmpty()) {
            return source;
        }

        CompilationUnit compilationUnit = StaticJavaParser.parse(source);
        LexicalPreservingPrinter.setup(compilationUnit);
        boolean changed = false;
        for (MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            Set<String> tags = tagsByMethod.get(method.getNameAsString());
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            List<AnnotationExpr> annotationsToRemove = method.getAnnotations().stream()
                    .filter(annotation -> tags.contains(sqlAnnotationTag(annotation)))
                    .toList();
            for (AnnotationExpr annotation : annotationsToRemove) {
                method.getAnnotations().remove(annotation);
                changed = true;
            }
        }
        if (!changed) {
            return source;
        }
        String updated = LexicalPreservingPrinter.print(compilationUnit);
        StaticJavaParser.parse(updated);
        return updated;
    }

    private List<SqlChange> annotationConversions(List<SqlChange> changes, List<AnnotationStatement> statements) {
        Set<String> annotationKeys = annotationKeys(statements);
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

    private Set<String> annotationKeys(List<AnnotationStatement> statements) {
        Set<String> annotationKeys = new LinkedHashSet<>();
        for (AnnotationStatement statement : statements) {
            annotationKeys.add(statement.key());
        }
        return annotationKeys;
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
        String lineSeparator = lineSeparator(xml);
        String originalXml = xml;
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
            additions.append(statementXml(statement, lineSeparator));
        }
        if (!additions.isEmpty()) {
            mapperEnd = xml.lastIndexOf("</mapper>");
            if (mapperEnd < 0) {
                throw new IllegalStateException("Target mapper XML is missing </mapper>.");
            }
            xml = xml.substring(0, mapperEnd) + additions + xml.substring(mapperEnd);
        }
        if (!xml.equals(originalXml)) {
            Files.writeString(target, normalizeLineSeparators(xml, lineSeparator), StandardCharsets.UTF_8);
        }
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

    private String statementXml(AnnotationStatement statement, String lineSeparator) {
        String sql = statement.sql().trim();
        String body;
        if (isScriptSql(sql)) {
            body = indent(stripScript(sql), "        ", lineSeparator);
        } else {
            body = "        <![CDATA[" + lineSeparator
                    + indent(sql, "            ", lineSeparator)
                    + lineSeparator + "        ]]>";
        }
        String resultType = "";
        if ("select".equals(statement.tagName()) && !statement.resultType().isBlank()) {
            resultType = " resultType=\"" + escapeXmlAttribute(statement.resultType()) + "\"";
        }
        return lineSeparator + "    <" + statement.tagName() + " id=\"" + escapeXmlAttribute(statement.id()) + "\"" + resultType + ">" + lineSeparator
                + body
                + lineSeparator + "    </" + statement.tagName() + ">" + lineSeparator;
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

    private String indent(String value, String indentation, String lineSeparator) {
        return Pattern.compile("\\R").splitAsStream(value == null ? "" : value)
                .map(line -> indentation + line)
                .reduce((left, right) -> left + lineSeparator + right)
                .orElse(indentation);
    }

    private String lineSeparator(String value) {
        return value != null && value.contains("\r\n") ? "\r\n" : "\n";
    }

    private String normalizeLineSeparators(String value, String lineSeparator) {
        return Pattern.compile("\\R").matcher(value).replaceAll(Matcher.quoteReplacement(lineSeparator));
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

    private Map<String, Path> mapperSourceByNamespace(ProjectScanResult scanResult) {
        Map<String, Path> sourceByNamespace = new LinkedHashMap<>();
        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            if (isMapperDmXml(mapperXmlFile)) {
                continue;
            }
            Path source = Paths.get(mapperXmlFile.path());
            String namespace = namespace(source);
            if (!namespace.isBlank()) {
                sourceByNamespace.put(namespace, source);
            }
        }
        return sourceByNamespace;
    }

    private boolean isMapperDmXml(MapperXmlFile mapperXmlFile) {
        String relativePath = mapperXmlFile.resourcesRelativePath().replace('\\', '/');
        String absolutePath = mapperXmlFile.path().replace('\\', '/');
        return relativePath.startsWith("mapper-dm/")
                || relativePath.contains("/mapper-dm/")
                || absolutePath.contains("/mapper-dm/");
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

    private Path defaultSourceTarget(AnnotationStatement statement) {
        return statement.moduleRoot().resolve("src/main/resources/mapper/" + statement.simpleName() + ".xml");
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
        Map<String, AnnotationStatement> statements = new LinkedHashMap<>();
        for (AnnotationStatement statement : scanJavaAnnotationStatements(projectRoot)) {
            statements.putIfAbsent(statement.key(), statement);
        }
        for (AnnotationStatement statement : scanClassAnnotationStatements(projectRoot)) {
            statements.putIfAbsent(statement.key(), statement);
        }
        List<AnnotationStatement> result = new ArrayList<>(statements.values());
        result.sort(Comparator.comparing(AnnotationStatement::key));
        return result;
    }

    private List<AnnotationStatement> scanJavaAnnotationStatements(Path projectRoot) {
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

    private List<AnnotationStatement> scanClassAnnotationStatements(Path projectRoot) {
        List<AnnotationStatement> statements = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .filter(path -> isMainClassOutput(projectRoot, path))
                    .filter(path -> !isIgnoredClassPath(projectRoot, path))
                    .sorted()
                    .forEach(path -> statements.addAll(scanClassFile(path)));
        } catch (IOException ignored) {
        }
        statements.sort(Comparator.comparing(AnnotationStatement::key));
        return statements;
    }

    private boolean isMainClassOutput(Path projectRoot, Path path) {
        String normalized = projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return normalized.contains("/target/classes/") || normalized.startsWith("target/classes/");
    }

    private boolean isIgnoredClassPath(Path projectRoot, Path path) {
        String normalized = projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return normalized.contains("/target/test-classes/")
                || normalized.contains("/.git/")
                || normalized.contains("/.idea/");
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
        CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(source);
        } catch (Exception e) {
            return List.of();
        }
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getName().asString())
                .orElse("");
        if (packageName.isBlank()) {
            return List.of();
        }
        Map<String, String> imports = imports(source);
        List<AnnotationStatement> statements = new ArrayList<>();
        Path moduleRoot = moduleRoot(javaFile);
        for (MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            ClassOrInterfaceDeclaration owner = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
            if (owner == null) {
                continue;
            }
            String simpleName = owner.getNameAsString();
            String namespace = packageName + "." + simpleName;
            for (AnnotationExpr annotation : method.getAnnotations()) {
                String tagName = sqlAnnotationTag(annotation);
                if (tagName.isBlank()) {
                    continue;
                }
                String sql = annotationSql(annotation);
                if (sql.isBlank()) {
                    continue;
                }
                String resultType = "select".equals(tagName)
                        ? resultType(method.getType(), packageName, imports)
                        : "";
                statements.add(new AnnotationStatement(
                        namespace,
                        simpleName,
                        method.getNameAsString(),
                        tagName,
                        normalizeAnnotationSql(sql),
                        resultType,
                        moduleRoot,
                        true,
                        javaFile,
                        -1,
                        -1
                ));
            }
        }
        statements.sort(Comparator.comparing(AnnotationStatement::key));
        return statements;
    }

    private String sqlAnnotationTag(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        int dot = name.lastIndexOf('.');
        String simpleName = dot < 0 ? name : name.substring(dot + 1);
        return switch (simpleName) {
            case "Select" -> "select";
            case "Insert" -> "insert";
            case "Update" -> "update";
            case "Delete" -> "delete";
            default -> "";
        };
    }

    private String annotationSql(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return annotationSql(singleMemberAnnotation.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return annotationSql(pair.getValue());
                }
            }
        }
        return "";
    }

    private String annotationSql(Expression expression) {
        if (expression instanceof StringLiteralExpr stringLiteral) {
            return stringLiteral.asString();
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteral) {
            return textBlockLiteral.asString();
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return annotationSql(enclosedExpr.getInner());
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = annotationSql(binaryExpr.getLeft());
            String right = annotationSql(binaryExpr.getRight());
            return left.isBlank() && right.isBlank() ? "" : left + right;
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializer) {
            List<String> values = new ArrayList<>();
            for (Expression value : arrayInitializer.getValues()) {
                String item = annotationSql(value);
                if (item.isBlank()) {
                    return "";
                }
                values.add(item);
            }
            return String.join(" ", values);
        }
        return "";
    }

    private String resultType(
            Type declaredType,
            String packageName,
            Map<String, String> imports
    ) {
        String type = declaredType == null ? "" : declaredType.asString();
        if (type.isBlank() || "void".equals(type)) {
            return "";
        }
        String rowType = rowResultType(type);
        if (rowType.isBlank() || isTypeVariable(rowType)) {
            return "java.lang.Object";
        }
        return resolveType(rowType, packageName, imports);
    }

    private static String normalizeAnnotationSql(String sql) {
        return fixMissingUpdateSetCommas(sql == null ? "" : sql.trim());
    }

    private static String fixMissingUpdateSetCommas(String sql) {
        Matcher updateMatcher = UPDATE_SET_PATTERN.matcher(sql);
        if (!updateMatcher.find()) {
            return sql;
        }
        Matcher whereMatcher = WHERE_PATTERN.matcher(sql);
        whereMatcher.region(updateMatcher.end(), sql.length());
        if (!whereMatcher.find()) {
            return sql;
        }
        String setBody = sql.substring(updateMatcher.end(), whereMatcher.start());
        String fixedSetBody = MISSING_UPDATE_SET_COMMA_PATTERN.matcher(setBody).replaceAll("$1, ");
        if (fixedSetBody.equals(setBody)) {
            return sql;
        }
        return sql.substring(0, updateMatcher.end()) + fixedSetBody + sql.substring(whereMatcher.start());
    }

    private List<AnnotationStatement> scanClassFile(Path classFile) {
        try {
            return new ClassAnnotationScanner(classFile).scan();
        } catch (Exception ignored) {
            return List.of();
        }
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

    private Path moduleRoot(Path javaFile) {
        String normalized = javaFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        int marker = normalized.indexOf("/src/main/java/");
        if (marker < 0) {
            return javaFile.toAbsolutePath().normalize().getParent();
        }
        return Paths.get(normalized.substring(0, marker));
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

    private static final class ClassAnnotationScanner {
        private final Path classFile;
        private final Object[] constantPool;
        private String className = "";
        private String simpleName = "";
        private Path moduleRoot;

        private ClassAnnotationScanner(Path classFile) {
            this.classFile = classFile;
            this.constantPool = new Object[0];
        }

        private ClassAnnotationScanner(Path classFile, Object[] constantPool) {
            this.classFile = classFile;
            this.constantPool = constantPool;
        }

        private List<AnnotationStatement> scan() throws IOException {
            byte[] bytes = Files.readAllBytes(classFile);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != 0xCAFEBABE) {
                    return List.of();
                }
                input.readUnsignedShort();
                input.readUnsignedShort();
                Object[] pool = readConstantPool(input);
                return new ClassAnnotationScanner(classFile, pool).scanBody(input);
            }
        }

        private List<AnnotationStatement> scanBody(DataInputStream input) throws IOException {
            input.readUnsignedShort();
            int thisClass = input.readUnsignedShort();
            input.readUnsignedShort();
            className = className(thisClass);
            if (className.isBlank()) {
                return List.of();
            }
            int lastDot = className.lastIndexOf('.');
            simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
            moduleRoot = classModuleRoot(classFile);
            skipInterfaces(input);
            skipMembers(input);
            List<AnnotationStatement> statements = readMethods(input);
            skipAttributes(input);
            return statements;
        }

        private Object[] readConstantPool(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = input.readUTF();
                    case 3, 4 -> input.skipBytes(4);
                    case 5, 6 -> {
                        input.skipBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> pool[i] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> input.skipBytes(4);
                    case 15 -> input.skipBytes(3);
                    default -> throw new IOException("Unsupported class constant pool tag: " + tag);
                }
            }
            return pool;
        }

        private void skipInterfaces(DataInputStream input) throws IOException {
            int interfaces = input.readUnsignedShort();
            input.skipBytes(interfaces * 2);
        }

        private void skipMembers(DataInputStream input) throws IOException {
            skipMemberTable(input);
        }

        private void skipMemberTable(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                input.skipBytes(6);
                skipAttributes(input);
            }
        }

        private List<AnnotationStatement> readMethods(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            List<AnnotationStatement> statements = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                input.readUnsignedShort();
                String methodName = utf8(input.readUnsignedShort());
                String descriptor = utf8(input.readUnsignedShort());
                String signature = "";
                List<ClassAnnotationSql> annotations = new ArrayList<>();
                int attributes = input.readUnsignedShort();
                for (int attributeIndex = 0; attributeIndex < attributes; attributeIndex++) {
                    String attributeName = utf8(input.readUnsignedShort());
                    int length = input.readInt();
                    byte[] attributeBytes = input.readNBytes(length);
                    try (DataInputStream attributeInput = new DataInputStream(new ByteArrayInputStream(attributeBytes))) {
                        if ("Signature".equals(attributeName)) {
                            signature = utf8(attributeInput.readUnsignedShort());
                        } else if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                            annotations.addAll(readRuntimeVisibleAnnotations(attributeInput));
                        }
                    }
                }
                for (ClassAnnotationSql annotation : annotations) {
                    String resultType = "select".equals(annotation.tagName())
                            ? resultTypeFromClassSignature(descriptor, signature)
                            : "";
                    statements.add(new AnnotationStatement(
                            className,
                            simpleName,
                            methodName,
                            annotation.tagName(),
                            normalizeAnnotationSql(annotation.sql()),
                            resultType,
                            moduleRoot,
                            false,
                            null,
                            -1,
                            -1
                    ));
                }
            }
            return statements;
        }

        private List<ClassAnnotationSql> readRuntimeVisibleAnnotations(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            List<ClassAnnotationSql> annotations = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String descriptor = utf8(input.readUnsignedShort());
                String tagName = SQL_ANNOTATION_TAGS.get(descriptor);
                String sql = "";
                int pairs = input.readUnsignedShort();
                for (int pairIndex = 0; pairIndex < pairs; pairIndex++) {
                    String elementName = utf8(input.readUnsignedShort());
                    List<String> values = readElementValue(input);
                    if ("value".equals(elementName)) {
                        sql = String.join(" ", values).trim();
                    }
                }
                if (tagName != null && !sql.isBlank()) {
                    annotations.add(new ClassAnnotationSql(tagName, sql));
                }
            }
            return annotations;
        }

        private List<String> readElementValue(DataInputStream input) throws IOException {
            int tag = input.readUnsignedByte();
            return switch (tag) {
                case 's' -> List.of(utf8(input.readUnsignedShort()));
                case '[' -> readElementValueArray(input);
                case '@' -> {
                    skipAnnotation(input);
                    yield List.of();
                }
                case 'e' -> {
                    input.skipBytes(4);
                    yield List.of();
                }
                case 'c', 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                    input.skipBytes(2);
                    yield List.of();
                }
                default -> List.of();
            };
        }

        private List<String> readElementValueArray(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                values.addAll(readElementValue(input));
            }
            return values;
        }

        private void skipAnnotation(DataInputStream input) throws IOException {
            input.readUnsignedShort();
            int pairs = input.readUnsignedShort();
            for (int i = 0; i < pairs; i++) {
                input.readUnsignedShort();
                readElementValue(input);
            }
        }

        private void skipAttributes(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                input.readUnsignedShort();
                int length = input.readInt();
                input.skipBytes(length);
            }
        }

        private String resultTypeFromClassSignature(String descriptor, String signature) {
            String returnType = returnType(signature == null || signature.isBlank() ? descriptor : signature);
            if (returnType.isBlank()) {
                return "";
            }
            return rowType(returnType);
        }

        private String returnType(String methodSignature) {
            int close = methodSignature.lastIndexOf(')');
            if (close < 0 || close + 1 >= methodSignature.length()) {
                return "";
            }
            return methodSignature.substring(close + 1);
        }

        private String rowType(String signature) {
            String type = stripArrayAndWildcard(signature.trim());
            if (type.isBlank() || "V".equals(type)) {
                return "";
            }
            return switch (type.charAt(0)) {
                case 'Z' -> "java.lang.Boolean";
                case 'B' -> "java.lang.Byte";
                case 'C' -> "java.lang.Character";
                case 'S' -> "java.lang.Short";
                case 'I' -> "java.lang.Integer";
                case 'J' -> "java.lang.Long";
                case 'F' -> "java.lang.Float";
                case 'D' -> "java.lang.Double";
                case 'T' -> "java.lang.Object";
                case 'L' -> objectRowType(type);
                default -> "java.lang.Object";
            };
        }

        private String stripArrayAndWildcard(String signature) {
            String result = signature;
            while (result.startsWith("[")) {
                result = result.substring(1);
            }
            if (result.startsWith("+") || result.startsWith("-")) {
                result = result.substring(1);
            }
            if ("*".equals(result)) {
                return "Ljava/lang/Object;";
            }
            return result;
        }

        private String objectRowType(String signature) {
            int end = objectTypeEnd(signature, 0);
            if (end < 0) {
                return "java.lang.Object";
            }
            String content = signature.substring(1, end);
            String raw = rawObjectType(content);
            if (isCollectionResult(raw) || "java.util.Optional".equals(raw)) {
                String argument = firstGenericArgument(content);
                return argument.isBlank() ? "java.lang.Object" : rowType(argument);
            }
            if ("java.util.Map".equals(raw)) {
                return "java.util.Map";
            }
            return raw;
        }

        private String rawObjectType(String content) {
            int generic = content.indexOf('<');
            String raw = generic < 0 ? content : content.substring(0, generic);
            return raw.replace('/', '.');
        }

        private String firstGenericArgument(String content) {
            int open = content.indexOf('<');
            if (open < 0) {
                return "";
            }
            int close = matchingGenericEnd(content, open);
            if (close < 0 || open + 1 >= close) {
                return "";
            }
            String genericContent = content.substring(open + 1, close);
            int argumentEnd = typeSignatureEnd(genericContent, 0);
            return argumentEnd < 0 ? "" : genericContent.substring(0, argumentEnd);
        }

        private int typeSignatureEnd(String value, int start) {
            if (start >= value.length()) {
                return -1;
            }
            char first = value.charAt(start);
            if (first == '+' || first == '-') {
                return typeSignatureEnd(value, start + 1);
            }
            if (first == '*') {
                return start + 1;
            }
            while (first == '[' && start + 1 < value.length()) {
                start++;
                first = value.charAt(start);
            }
            if (first == 'L') {
                int end = objectTypeEnd(value, start);
                return end < 0 ? -1 : end + 1;
            }
            if (first == 'T') {
                int end = value.indexOf(';', start);
                return end < 0 ? -1 : end + 1;
            }
            return start + 1;
        }

        private int objectTypeEnd(String value, int start) {
            int depth = 0;
            for (int i = start + 1; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current == '<') {
                    depth++;
                } else if (current == '>') {
                    depth--;
                } else if (current == ';' && depth == 0) {
                    return i;
                }
            }
            return -1;
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

        private boolean isCollectionResult(String type) {
            return Set.of(
                    "java.util.List",
                    "java.util.Collection",
                    "java.util.Set",
                    "java.lang.Iterable",
                    "java.util.Iterator",
                    "org.apache.ibatis.cursor.Cursor"
            ).contains(type);
        }

        private String className(int classIndex) {
            Object value = constantPool[classIndex];
            if (value instanceof Integer nameIndex) {
                return utf8(nameIndex).replace('/', '.');
            }
            return "";
        }

        private String utf8(int index) {
            if (index <= 0 || index >= constantPool.length) {
                return "";
            }
            Object value = constantPool[index];
            if (value instanceof String string) {
                return string;
            }
            if (value instanceof Integer nestedIndex) {
                return utf8(nestedIndex);
            }
            return "";
        }

        private Path classModuleRoot(Path classFile) {
            String normalized = classFile.toAbsolutePath().normalize().toString().replace('\\', '/');
            int marker = normalized.indexOf("/target/classes/");
            if (marker < 0) {
                return classFile.toAbsolutePath().normalize().getParent();
            }
            return Paths.get(normalized.substring(0, marker));
        }
    }

    private record AnnotationStatement(
            String namespace,
            String simpleName,
            String id,
            String tagName,
            String sql,
            String resultType,
            Path moduleRoot,
            boolean sourceAvailable,
            Path javaSource,
            int annotationStart,
            int annotationEnd
    ) {
        private String key() {
            return namespace + "." + id;
        }
    }

    private record StatementXmlUpdate(String xml, boolean found) {
    }

    private record ClassAnnotationSql(String tagName, String sql) {
    }

}
