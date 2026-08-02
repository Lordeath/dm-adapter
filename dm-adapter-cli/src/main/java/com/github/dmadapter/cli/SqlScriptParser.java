package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlScriptParser {
    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile("(?i)^\\s*DELIMITER\\s+(\\S+)\\s*$");
    private static final Pattern SCRIPT_LINE = Pattern.compile(".*(?:\\R|$)");
    private static final Pattern SLASH_TERMINATED_CREATE = Pattern.compile(
            "(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|TRIGGER|PACKAGE)\\b"
    );
    private static final Pattern SLASH_TERMINATED_BLOCK = Pattern.compile("(?is)^(?:DECLARE|BEGIN)\\b");

    private SqlScriptParser() {
    }

    static List<String> statements(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        boolean slashTerminatedBlock = false;
        StringBuilder buffer = new StringBuilder(content.length());
        ScriptScanState scanState = new ScriptScanState();
        for (String line : lines(content)) {
            Matcher delimiterMatcher = DELIMITER_DIRECTIVE.matcher(line.strip());
            if (!slashTerminatedBlock
                    && delimiterMatcher.matches()
                    && !scanState.insideLexicalContext()) {
                if (scanState.pendingExecutable()) {
                    scanState.completeDirectiveBoundaryStatement(buffer, statements);
                }
                delimiter = delimiterMatcher.group(1);
                continue;
            }
            if (slashTerminatedBlock) {
                if (isSlashTerminator(line)) {
                    scanState.completeSlashTerminatedStatement(buffer, statements);
                    slashTerminatedBlock = false;
                    continue;
                }
                buffer.append(line);
                continue;
            }
            if (";".equals(delimiter)
                    && !scanState.pendingExecutable()
                    && !scanState.insideLexicalContext()
                    && startsSlashTerminatedBlock(line)) {
                slashTerminatedBlock = true;
                buffer.append(line);
                continue;
            }
            if (isSlashTerminator(line)
                    && !scanState.pendingExecutable()
                    && !scanState.insideLexicalContext()) {
                continue;
            }
            buffer.append(line);
            scanState.scanAppendedText(buffer, delimiter, statements);
        }
        scanState.completeTrailingStatement(buffer, statements);
        return List.copyOf(statements);
    }

    static String scriptContent(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            String renderedStatement = renderStatement(statement);
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(renderedStatement);
            if (!endsWithSemicolon(renderedStatement)) {
                content.append(";");
            }
            if (requiresSlashTerminator(renderedStatement)) {
                content.append("\n/");
            }
        }
        if (!content.isEmpty()) {
            content.append("\n");
        }
        return content.toString();
    }

    static boolean executable(String statement) {
        return !isBlankSql(statement);
    }

    private static String renderStatement(String statement) {
        String renderedStatement = statement.stripTrailing();
        if (executable(renderedStatement)) {
            return renderedStatement;
        }
        return renderedStatement + "\nBEGIN\n    NULL;\nEND";
    }

    private static void addStatement(List<String> statements, String rawStatement) {
        String statement = rawStatement == null ? "" : rawStatement.strip();
        if (!isBlankSql(statement)) {
            statements.add(statement);
        }
    }

    private static List<String> lines(String content) {
        List<String> lines = new ArrayList<>();
        Matcher matcher = SCRIPT_LINE.matcher(content);
        while (matcher.find()) {
            String line = matcher.group();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean matchesAt(CharSequence text, String value, int index) {
        if (index + value.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (text.charAt(index + i) != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return true;
        }
        String withoutComments = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("(?m)^\\s*#.*$", " ");
        return withoutComments.isBlank();
    }

    private static boolean endsWithSemicolon(String statement) {
        String stripped = statement == null ? "" : statement.stripTrailing();
        return stripped.endsWith(";");
    }

    private static boolean startsSlashTerminatedBlock(String line) {
        return requiresSlashTerminator(stripLeadingCommentsAndWhitespace(line));
    }

    private static boolean requiresSlashTerminator(String statement) {
        String sql = stripLeadingCommentsAndWhitespace(statement);
        if (SLASH_TERMINATED_CREATE.matcher(sql).find()) {
            return true;
        }
        return SLASH_TERMINATED_BLOCK.matcher(sql).find();
    }

    private static boolean isSlashTerminator(String line) {
        return line != null && line.strip().equals("/");
    }

    private static String stripLeadingCommentsAndWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            if (startsWith(value, index, "--") || startsWith(value, index, "#")) {
                index = skipLine(value, index);
            } else if (startsWith(value, index, "/*")) {
                index = skipBlockComment(value, index);
            } else {
                break;
            }
        }
        return value.substring(Math.min(index, value.length()));
    }

    private static int skipLine(String value, int index) {
        int cursor = index;
        while (cursor < value.length() && value.charAt(cursor) != '\n' && value.charAt(cursor) != '\r') {
            cursor++;
        }
        if (cursor < value.length() && value.charAt(cursor) == '\r') {
            cursor++;
            if (cursor < value.length() && value.charAt(cursor) == '\n') {
                cursor++;
            }
        } else if (cursor < value.length() && value.charAt(cursor) == '\n') {
            cursor++;
        }
        return cursor;
    }

    private static int skipBlockComment(String value, int index) {
        int end = value.indexOf("*/", index + 2);
        return end < 0 ? value.length() : end + 2;
    }

    private static boolean startsWith(String value, int index, String prefix) {
        return index >= 0
                && index + prefix.length() <= value.length()
                && value.regionMatches(index, prefix, 0, prefix.length());
    }

    private static final class ScriptScanState {
        private int scanIndex;
        private int statementStart;
        private boolean pendingExecutable;
        private boolean singleQuoted;
        private boolean doubleQuoted;
        private boolean backtickQuoted;
        private boolean lineComment;
        private boolean blockComment;

        boolean pendingExecutable() {
            return pendingExecutable;
        }

        boolean insideLexicalContext() {
            return singleQuoted || doubleQuoted || backtickQuoted || lineComment || blockComment;
        }

        void scanAppendedText(StringBuilder sql, String delimiter, List<String> statements) {
            if (delimiter == null || delimiter.isEmpty()) {
                scanIndex = sql.length();
                return;
            }
            int index = scanIndex;
            while (index < sql.length()) {
                char current = sql.charAt(index);
                char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                if (lineComment) {
                    if (current == '\n' || current == '\r') {
                        lineComment = false;
                    }
                    index++;
                    continue;
                }
                if (blockComment) {
                    if (current == '*' && next == '/') {
                        blockComment = false;
                        index += 2;
                    } else {
                        index++;
                    }
                    continue;
                }
                if (singleQuoted) {
                    if (current == '\\' && next != '\0') {
                        index += 2;
                    } else if (current == '\'' && next == '\'') {
                        index += 2;
                    } else {
                        if (current == '\'') {
                            singleQuoted = false;
                        }
                        index++;
                    }
                    continue;
                }
                if (doubleQuoted) {
                    if (current == '\\' && next != '\0') {
                        index += 2;
                    } else if (current == '"' && next == '"') {
                        index += 2;
                    } else {
                        if (current == '"') {
                            doubleQuoted = false;
                        }
                        index++;
                    }
                    continue;
                }
                if (backtickQuoted) {
                    if (current == '`') {
                        backtickQuoted = false;
                    }
                    index++;
                    continue;
                }
                if (current == '-' && next == '-') {
                    lineComment = true;
                    index += 2;
                } else if (current == '#') {
                    lineComment = true;
                    index++;
                } else if (current == '/' && next == '*') {
                    blockComment = true;
                    index += 2;
                } else if (current == '\'') {
                    pendingExecutable = true;
                    singleQuoted = true;
                    index++;
                } else if (current == '"') {
                    pendingExecutable = true;
                    doubleQuoted = true;
                    index++;
                } else if (current == '`') {
                    pendingExecutable = true;
                    backtickQuoted = true;
                    index++;
                } else if (matchesAt(sql, delimiter, index)) {
                    if (pendingExecutable) {
                        addStatement(statements, sql.substring(statementStart, index));
                    }
                    index += delimiter.length();
                    statementStart = index;
                    resetStatementState();
                } else {
                    if (!Character.isWhitespace(current)) {
                        pendingExecutable = true;
                    }
                    index++;
                }
            }
            scanIndex = index;
        }

        void completeSlashTerminatedStatement(StringBuilder sql, List<String> statements) {
            addStatement(statements, sql.substring(statementStart));
            statementStart = sql.length();
            scanIndex = sql.length();
            resetStatementState();
        }

        void completeDirectiveBoundaryStatement(StringBuilder sql, List<String> statements) {
            addStatement(statements, sql.substring(statementStart));
            statementStart = sql.length();
            scanIndex = sql.length();
            resetStatementState();
        }

        void completeTrailingStatement(StringBuilder sql, List<String> statements) {
            if (pendingExecutable || scanIndex < sql.length()) {
                addStatement(statements, sql.substring(statementStart));
            }
        }

        private void resetStatementState() {
            pendingExecutable = false;
            singleQuoted = false;
            doubleQuoted = false;
            backtickQuoted = false;
            lineComment = false;
            blockComment = false;
        }
    }
}
