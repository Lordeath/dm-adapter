package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class MapperJavaParamFixer {
    private static final Pattern MYBATIS_PARAMETER_PATTERN = Pattern.compile(
            "[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:\\s*,[^}]*)?}"
    );
    private static final Pattern PARAM_ANNOTATION_PATTERN = Pattern.compile(
            "@(?:org\\.apache\\.ibatis\\.annotations\\.)?Param\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"\\s*\\)"
    );
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^\\s*import\\s+[^;]+;");
    private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[\\s*])?\\s*$");
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> SYNTHETIC_PARAMETER_NAMES = Set.of(
            "_parameter", "param1", "param2", "param3", "param4", "param5", "arg0", "arg1", "arg2", "arg3",
            "list", "collection", "array"
    );

    MapperJavaParamFixResult fix(ProjectScanResult scanResult, AdapterContext context) {
        if (context.dryRun()) {
            return MapperJavaParamFixResult.empty();
        }
        Map<String, Map<String, Set<String>>> parameterReferencesByNamespace = mapperParameterReferences(scanResult);
        if (parameterReferencesByNamespace.isEmpty()) {
            return MapperJavaParamFixResult.empty();
        }
        List<FileChange> fileChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> namespaceEntry : parameterReferencesByNamespace.entrySet()) {
            String namespace = namespaceEntry.getKey();
            Optional<Path> javaSource = javaSource(context.projectRoot(), namespace);
            if (javaSource.isEmpty()) {
                continue;
            }
            Path source = javaSource.get();
            String content;
            try {
                content = Files.readString(source, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                warnings.add("Skipped Java mapper @Param fix for encrypted or non-UTF-8 source " + source + ".");
                continue;
            } catch (Exception e) {
                warnings.add("Skipped Java mapper @Param fix for " + source + ": " + e.getMessage());
                continue;
            }
            JavaRewrite rewrite = rewriteMapperSource(content, namespaceEntry.getValue());
            if (!rewrite.changed()) {
                continue;
            }
            try {
                Files.writeString(source, rewrite.content(), StandardCharsets.UTF_8);
                fileChanges.add(FileChange.applied(
                        source.toString(),
                        "UPDATE",
                        "Fixed Java mapper @Param annotations from mapper XML parameter names"
                ));
            } catch (Exception e) {
                warnings.add("Failed to write Java mapper @Param fixes to " + source + ": " + e.getMessage());
            }
        }
        return new MapperJavaParamFixResult(fileChanges, warnings);
    }

    JavaRewrite rewriteMapperSource(String source, Map<String, Set<String>> referencesByMethod) {
        List<Replacement> replacements = new ArrayList<>();
        boolean needsParamImport = false;
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf('(', index);
            if (open < 0) {
                break;
            }
            String methodName = methodNameBefore(source, open);
            if (methodName.isBlank() || !referencesByMethod.containsKey(methodName)) {
                index = open + 1;
                continue;
            }
            int close = matchingParenthesis(source, open);
            if (close < 0) {
                index = open + 1;
                continue;
            }
            if (!looksLikeMapperMethod(source, close + 1)) {
                index = close + 1;
                continue;
            }
            Set<String> references = referencesByMethod.getOrDefault(methodName, Set.of());
            List<ParameterSegment> parameters = parameters(source, open + 1, close);
            JavaMethodRewrite methodRewrite = rewriteMethodParameters(parameters, references);
            replacements.addAll(methodRewrite.replacements());
            needsParamImport |= methodRewrite.needsParamImport();
            index = close + 1;
        }
        if (replacements.isEmpty()) {
            return new JavaRewrite(source, false);
        }
        String rewritten = applyReplacements(source, replacements);
        if (needsParamImport && !hasParamImportOrQualifiedAnnotation(rewritten)) {
            rewritten = addParamImport(rewritten);
        }
        return new JavaRewrite(rewritten, !rewritten.equals(source));
    }

    private JavaMethodRewrite rewriteMethodParameters(List<ParameterSegment> parameters, Set<String> references) {
        List<Replacement> replacements = new ArrayList<>();
        Map<String, List<ParameterSegment>> byAnnotationName = new LinkedHashMap<>();
        for (ParameterSegment parameter : parameters) {
            replacements.addAll(removeDuplicateParamAnnotations(parameter));
            if (!parameter.paramAnnotationName().isBlank()) {
                byAnnotationName.computeIfAbsent(parameter.paramAnnotationName(), ignored -> new ArrayList<>()).add(parameter);
            }
        }
        for (List<ParameterSegment> duplicated : byAnnotationName.values()) {
            if (duplicated.size() < 2) {
                continue;
            }
            for (ParameterSegment parameter : duplicated) {
                if (references.contains(parameter.variableName())
                        && !parameter.variableName().equals(parameter.paramAnnotationName())) {
                    replacements.add(new Replacement(
                            parameter.annotationValueStart(),
                            parameter.annotationValueEnd(),
                            parameter.variableName()
                    ));
                }
            }
        }
        boolean needsParamImport = false;
        if (parameters.size() == 1 && references.size() == 1) {
            ParameterSegment parameter = parameters.get(0);
            String reference = references.iterator().next();
            if (parameter.paramAnnotationName().isBlank()
                    && !parameter.variableName().isBlank()
                    && !reference.equals(parameter.variableName())
                    && simpleParameterType(parameter.typeText())) {
                replacements.add(new Replacement(
                        parameter.annotationInsertOffset(),
                        parameter.annotationInsertOffset(),
                        "@Param(\"" + reference + "\") "
                ));
                needsParamImport = true;
            }
        }
        if (parameters.size() > 1) {
            for (ParameterSegment parameter : parameters) {
                if (!parameter.paramAnnotationName().isBlank()
                        || parameter.variableName().isBlank()
                        || !references.contains(parameter.variableName())
                        || !simpleParameterType(parameter.typeText())) {
                    continue;
                }
                replacements.add(new Replacement(
                        parameter.annotationInsertOffset(),
                        parameter.annotationInsertOffset(),
                        "@Param(\"" + parameter.variableName() + "\") "
                ));
                needsParamImport = true;
            }
        }
        return new JavaMethodRewrite(replacements, needsParamImport);
    }

    private List<Replacement> removeDuplicateParamAnnotations(ParameterSegment parameter) {
        List<ParamAnnotation> annotations = parameter.paramAnnotations();
        if (annotations.size() < 2) {
            return List.of();
        }
        Map<String, ParamAnnotation> lastByName = new LinkedHashMap<>();
        for (ParamAnnotation annotation : annotations) {
            lastByName.put(annotation.name(), annotation);
        }
        List<Replacement> replacements = new ArrayList<>();
        Set<ParamAnnotation> annotationsToKeep = new LinkedHashSet<>(lastByName.values());
        for (ParamAnnotation annotation : annotations) {
            if (!annotationsToKeep.contains(annotation)) {
                replacements.add(new Replacement(annotation.start(), annotation.end(), ""));
            }
        }
        return replacements;
    }

    private Map<String, Map<String, Set<String>>> mapperParameterReferences(ProjectScanResult scanResult) {
        Map<String, Map<String, Set<String>>> references = new LinkedHashMap<>();
        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            if (isMapperDm(mapperXmlFile)) {
                continue;
            }
            Path path = Path.of(mapperXmlFile.path());
            if (!Files.exists(path)) {
                continue;
            }
            try (InputStream inputStream = Files.newInputStream(path)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                Document document = factory.newDocumentBuilder().parse(inputStream);
                Element root = document.getDocumentElement();
                if (root == null || !"mapper".equals(root.getTagName())) {
                    continue;
                }
                String namespace = root.getAttribute("namespace");
                if (namespace == null || namespace.isBlank()) {
                    continue;
                }
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (!(child instanceof Element element) || !STATEMENT_TAGS.contains(element.getTagName())) {
                        continue;
                    }
                    String id = element.getAttribute("id");
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    Set<String> names = parameterNames(element.getTextContent());
                    if (!names.isEmpty()) {
                        references.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                                .addAll(names);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return references;
    }

    private boolean isMapperDm(MapperXmlFile mapperXmlFile) {
        String normalized = (mapperXmlFile.resourcesRelativePath() + "/" + mapperXmlFile.path())
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        return normalized.contains("/mapper-dm/") || normalized.contains("src/main/resources/mapper-dm/");
    }

    private Set<String> parameterNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = MYBATIS_PARAMETER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String name = matcher.group(1);
            int dot = name.indexOf('.');
            if (dot >= 0) {
                name = name.substring(0, dot);
            }
            if (!name.isBlank() && !SYNTHETIC_PARAMETER_NAMES.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private Optional<Path> javaSource(Path projectRoot, String namespace) {
        String relative = namespace.replace('.', '/') + ".java";
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> normalized(path).contains("/src/main/java/"))
                    .filter(path -> normalized(path).endsWith("/" + relative))
                    .sorted(Comparator.comparing(Path::toString))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String methodNameBefore(String source, int openParen) {
        int end = openParen - 1;
        while (end >= 0 && Character.isWhitespace(source.charAt(end))) {
            end--;
        }
        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
            start--;
        }
        if (start == end) {
            return "";
        }
        return source.substring(start + 1, end + 1);
    }

    private boolean looksLikeMapperMethod(String source, int index) {
        int current = skipWhitespace(source, index);
        if (source.startsWith("throws", current)) {
            int semicolon = source.indexOf(';', current);
            int brace = source.indexOf('{', current);
            return semicolon >= 0 && (brace < 0 || semicolon < brace);
        }
        return current < source.length() && source.charAt(current) == ';';
    }

    private List<ParameterSegment> parameters(String source, int start, int end) {
        List<ParameterSegment> parameters = new ArrayList<>();
        int parameterStart = start;
        int depth = 0;
        for (int i = start; i <= end; i++) {
            char ch = i == end ? ',' : source.charAt(i);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && depth == 0) {
                ParameterSegment segment = parameter(source, parameterStart, i);
                if (segment != null) {
                    parameters.add(segment);
                }
                parameterStart = i + 1;
            }
        }
        return parameters;
    }

    private ParameterSegment parameter(String source, int start, int end) {
        int trimmedStart = skipWhitespace(source, start);
        int trimmedEnd = end;
        while (trimmedEnd > trimmedStart && Character.isWhitespace(source.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        if (trimmedStart >= trimmedEnd) {
            return null;
        }
        String text = source.substring(trimmedStart, trimmedEnd);
        Matcher annotation = PARAM_ANNOTATION_PATTERN.matcher(text);
        List<ParamAnnotation> paramAnnotations = new ArrayList<>();
        while (annotation.find()) {
            paramAnnotations.add(new ParamAnnotation(
                    annotation.group(1),
                    trimmedStart + annotation.start(),
                    trimmedStart + annotation.end(),
                    trimmedStart + annotation.start(1),
                    trimmedStart + annotation.end(1)
            ));
        }
        String annotationName = "";
        int valueStart = -1;
        int valueEnd = -1;
        if (!paramAnnotations.isEmpty()) {
            ParamAnnotation firstAnnotation = paramAnnotations.get(0);
            annotationName = firstAnnotation.name();
            valueStart = firstAnnotation.valueStart();
            valueEnd = firstAnnotation.valueEnd();
        }
        String withoutAnnotations = removeAnnotations(text).replace("final ", "").trim();
        Matcher variable = VARIABLE_NAME_PATTERN.matcher(withoutAnnotations);
        if (!variable.find()) {
            return null;
        }
        String variableName = variable.group(1);
        String typeText = withoutAnnotations.substring(0, variable.start(1)).trim();
        int insertOffset = trimmedStart;
        return new ParameterSegment(
                variableName,
                typeText,
                annotationName,
                valueStart,
                valueEnd,
                insertOffset,
                paramAnnotations
        );
    }

    private String removeAnnotations(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '@') {
                int annotationEnd = index + 1;
                while (annotationEnd < text.length()
                        && (Character.isJavaIdentifierPart(text.charAt(annotationEnd))
                        || text.charAt(annotationEnd) == '.')) {
                    annotationEnd++;
                }
                annotationEnd = skipWhitespace(text, annotationEnd);
                if (annotationEnd < text.length() && text.charAt(annotationEnd) == '(') {
                    int close = matchingParenthesis(text, annotationEnd);
                    annotationEnd = close < 0 ? text.length() : close + 1;
                }
                result.append(" ".repeat(Math.max(1, annotationEnd - index)));
                index = annotationEnd;
                continue;
            }
            result.append(ch);
            index++;
        }
        return result.toString();
    }

    private boolean simpleParameterType(String typeText) {
        String normalized = typeText == null ? "" : typeText
                .replace("...", "")
                .replace("[]", "")
                .trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart).trim();
        }
        int dot = normalized.lastIndexOf('.');
        String simple = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        return Set.of(
                "String", "CharSequence", "Long", "Integer", "Short", "Byte", "Double", "Float",
                "Boolean", "Character", "BigDecimal", "BigInteger", "Date", "LocalDate",
                "LocalDateTime", "LocalTime", "Timestamp", "UUID",
                "long", "int", "short", "byte", "double", "float", "boolean", "char"
        ).contains(simple);
    }

    private int matchingParenthesis(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = stringEnd(source, i + 1, ch);
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int stringEnd(String source, int index, char quote) {
        for (int i = index; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                i++;
                continue;
            }
            if (ch == quote) {
                return i;
            }
        }
        return source.length() - 1;
    }

    private int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length() && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private boolean hasParamImportOrQualifiedAnnotation(String source) {
        return source.contains("import org.apache.ibatis.annotations.Param;")
                || source.contains("@org.apache.ibatis.annotations.Param");
    }

    private String addParamImport(String source) {
        Matcher importMatcher = IMPORT_PATTERN.matcher(source);
        int insertOffset = -1;
        while (importMatcher.find()) {
            insertOffset = importMatcher.end();
        }
        if (insertOffset >= 0) {
            return source.substring(0, insertOffset)
                    + lineSeparator(source)
                    + "import org.apache.ibatis.annotations.Param;"
                    + source.substring(insertOffset);
        }
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
        if (packageMatcher.find()) {
            return source.substring(0, packageMatcher.end())
                    + lineSeparator(source)
                    + lineSeparator(source)
                    + "import org.apache.ibatis.annotations.Param;"
                    + source.substring(packageMatcher.end());
        }
        return "import org.apache.ibatis.annotations.Param;" + lineSeparator(source) + source;
    }

    private String lineSeparator(String source) {
        return source.contains("\r\n") ? "\r\n" : "\n";
    }

    private String applyReplacements(String source, List<Replacement> replacements) {
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        String rewritten = source;
        for (Replacement replacement : replacements) {
            rewritten = rewritten.substring(0, replacement.start())
                    + replacement.value()
                    + rewritten.substring(replacement.end());
        }
        return rewritten;
    }

    private String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private record JavaRewrite(String content, boolean changed) {
    }

    private record JavaMethodRewrite(List<Replacement> replacements, boolean needsParamImport) {
    }

    private record Replacement(int start, int end, String value) {
    }

    private record ParamAnnotation(
            String name,
            int start,
            int end,
            int valueStart,
            int valueEnd
    ) {
    }

    private record ParameterSegment(
            String variableName,
            String typeText,
            String paramAnnotationName,
            int annotationValueStart,
            int annotationValueEnd,
            int annotationInsertOffset,
            List<ParamAnnotation> paramAnnotations
    ) {
    }
}
