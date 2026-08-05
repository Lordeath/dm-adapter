package com.github.dmadapter.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DamengReservedColumnRenamer {
    public static final String RULE_NAME = "DAMENG_RESERVED_COLUMN_RENAME";
    public static final String RESULT_ALIAS_RULE_NAME = "DAMENG_RESERVED_COLUMN_RESULT_ALIAS";

    private static final Set<String> RESERVED_COLUMN_NAMES = Set.of(
            "ROWID",
            "ROWNUM",
            "TRXID",
            "PHYROWID",
            "VERSIONS_STARTTIME",
            "VERSIONS_ENDTIME",
            "VERSIONS_STARTTRXID",
            "VERSIONS_ENDTRXID",
            "VERSIONS_OPERATION"
    );
    private static final String QUOTED_OR_BARE_IDENTIFIER =
            "(?:[A-Za-z_][A-Za-z0-9_]*|`(?:``|[^`])+`)";
    private static final Pattern DIRECT_SELECT_ITEM_PATTERN = Pattern.compile(
            "(?is)^(?<column>(?:(?:" + QUOTED_OR_BARE_IDENTIFIER + ")\\s*\\.\\s*)*"
                    + "(?<name>" + QUOTED_OR_BARE_IDENTIFIER + "))"
                    + "(?:(?<separator>\\s+)(?<as>AS\\s+)?(?<alias>" + QUOTED_OR_BARE_IDENTIFIER + "))?$"
    );
    private static final Pattern EXPLICIT_ALIAS_PATTERN = Pattern.compile(
            "(?is)^(?<expression>.+\\S)(?<separator>\\s+AS\\s+)(?<alias>"
                    + QUOTED_OR_BARE_IDENTIFIER
                    + ")$"
    );
    private static final Pattern IMPLICIT_RESERVED_ALIAS_PATTERN = Pattern.compile(
            "(?is)^(?<expression>.+\\S)(?<separator>\\s+)(?<alias>"
                    + QUOTED_OR_BARE_IDENTIFIER
                    + ")$"
    );
    private static final Set<String> SELECT_MODIFIERS = Set.of("ALL", "DISTINCT", "DISTINCTROW");

    private DamengReservedColumnRenamer() {
    }

    public static RenameResult renameBareIdentifiers(String sql) {
        return renameBareIdentifiers(sql, ReservedColumnRewriteMode.PHYSICAL_ONLY);
    }

    public static RenameResult renameBareIdentifiers(String sql, ReservedColumnRewriteMode mode) {
        if (sql == null || sql.isEmpty()) {
            return new RenameResult(sql == null ? "" : sql, false, false);
        }
        ReservedColumnRewriteMode effectiveMode = mode == null
                ? ReservedColumnRewriteMode.PHYSICAL_ONLY
                : mode;
        if (effectiveMode == ReservedColumnRewriteMode.PHYSICAL_ONLY) {
            return renamePhysicalIdentifiers(sql);
        }
        List<TextRange> selectLists = effectiveMode == ReservedColumnRewriteMode.RESULT_COLUMN_LIST
                ? List.of(new TextRange(0, topLevelSelectListEnd(sql, 0)))
                : topLevelSelectLists(sql);
        if (selectLists.isEmpty()) {
            return renamePhysicalIdentifiers(sql);
        }

        StringBuilder converted = new StringBuilder(sql.length() + 32);
        boolean changed = false;
        boolean resultAliasAdded = false;
        int copiedUntil = 0;
        for (TextRange selectList : selectLists) {
            RenameResult leading = renamePhysicalIdentifiers(sql.substring(copiedUntil, selectList.start()));
            converted.append(leading.convertedSql());
            changed = changed || leading.changed();

            RenameResult projection = renameSelectList(sql.substring(selectList.start(), selectList.end()));
            converted.append(projection.convertedSql());
            changed = changed || projection.changed();
            resultAliasAdded = resultAliasAdded || projection.resultAliasAdded();
            copiedUntil = selectList.end();
        }
        RenameResult trailing = renamePhysicalIdentifiers(sql.substring(copiedUntil));
        converted.append(trailing.convertedSql());
        changed = changed || trailing.changed();
        return new RenameResult(changed ? converted.toString() : sql, changed, resultAliasAdded);
    }

    private static RenameResult renameSelectList(String selectList) {
        List<TextRange> items = splitTopLevelCommaRanges(selectList);
        StringBuilder converted = new StringBuilder(selectList.length() + 32);
        boolean changed = false;
        boolean resultAliasAdded = false;
        int copiedUntil = 0;
        for (TextRange item : items) {
            converted.append(selectList, copiedUntil, item.start());
            RenameResult itemResult = renameSelectItem(selectList.substring(item.start(), item.end()));
            converted.append(itemResult.convertedSql());
            changed = changed || itemResult.changed();
            resultAliasAdded = resultAliasAdded || itemResult.resultAliasAdded();
            copiedUntil = item.end();
        }
        converted.append(selectList.substring(copiedUntil));
        return new RenameResult(changed ? converted.toString() : selectList, changed, resultAliasAdded);
    }

    private static RenameResult renameSelectItem(String item) {
        int leadingEnd = 0;
        while (leadingEnd < item.length() && Character.isWhitespace(item.charAt(leadingEnd))) {
            leadingEnd++;
        }
        int trailingStart = item.length();
        while (trailingStart > leadingEnd && Character.isWhitespace(item.charAt(trailingStart - 1))) {
            trailingStart--;
        }
        String core = item.substring(leadingEnd, trailingStart);
        String modifier = "";
        int modifierEnd = leadingSelectModifierEnd(core);
        if (modifierEnd > 0) {
            modifier = core.substring(0, modifierEnd);
            core = core.substring(modifierEnd);
        }

        Matcher direct = DIRECT_SELECT_ITEM_PATTERN.matcher(core);
        if (direct.matches()) {
            String columnName = unquoteIdentifier(direct.group("name"));
            String alias = direct.group("alias");
            boolean reservedColumn = isReservedColumnName(columnName);
            boolean reservedAlias = alias != null && isReservedColumnName(unquoteIdentifier(alias));
            if (reservedColumn || reservedAlias) {
                RenameResult column = renamePhysicalIdentifiers(direct.group("column"));
                StringBuilder rewritten = new StringBuilder(modifier).append(column.convertedSql());
                if (alias == null) {
                    rewritten.append(" AS ").append(quoteAlias(columnName));
                } else {
                    rewritten.append(direct.group("separator"));
                    if (direct.group("as") != null) {
                        rewritten.append(direct.group("as"));
                    }
                    rewritten.append(reservedAlias ? quoteAlias(unquoteIdentifier(alias)) : alias);
                }
                String result = item.substring(0, leadingEnd)
                        + rewritten
                        + item.substring(trailingStart);
                return new RenameResult(result, !result.equals(item), reservedColumn || reservedAlias);
            }
        }

        Matcher explicitAlias = EXPLICIT_ALIAS_PATTERN.matcher(core);
        if (explicitAlias.matches() && isReservedColumnName(unquoteIdentifier(explicitAlias.group("alias")))) {
            RenameResult expression = renamePhysicalIdentifiers(explicitAlias.group("expression"));
            String result = item.substring(0, leadingEnd)
                    + modifier
                    + expression.convertedSql()
                    + explicitAlias.group("separator")
                    + quoteAlias(unquoteIdentifier(explicitAlias.group("alias")))
                    + item.substring(trailingStart);
            return new RenameResult(result, !result.equals(item), true);
        }

        Matcher implicitAlias = IMPLICIT_RESERVED_ALIAS_PATTERN.matcher(core);
        if (implicitAlias.matches() && isReservedColumnName(unquoteIdentifier(implicitAlias.group("alias")))) {
            RenameResult expression = renamePhysicalIdentifiers(implicitAlias.group("expression"));
            String result = item.substring(0, leadingEnd)
                    + modifier
                    + expression.convertedSql()
                    + implicitAlias.group("separator")
                    + quoteAlias(unquoteIdentifier(implicitAlias.group("alias")))
                    + item.substring(trailingStart);
            return new RenameResult(result, !result.equals(item), true);
        }

        RenameResult physical = renamePhysicalIdentifiers(core);
        if (!physical.changed()) {
            return new RenameResult(item, false, false);
        }
        String result = item.substring(0, leadingEnd)
                + modifier
                + physical.convertedSql()
                + item.substring(trailingStart);
        return new RenameResult(result, true, false);
    }

    private static int leadingSelectModifierEnd(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        int wordStart = index;
        while (index < value.length() && Character.isLetter(value.charAt(index))) {
            index++;
        }
        if (wordStart == index || !SELECT_MODIFIERS.contains(value.substring(wordStart, index).toUpperCase(Locale.ROOT))) {
            return 0;
        }
        int whitespaceEnd = index;
        while (whitespaceEnd < value.length() && Character.isWhitespace(value.charAt(whitespaceEnd))) {
            whitespaceEnd++;
        }
        return whitespaceEnd > index ? whitespaceEnd : 0;
    }

    private static String quoteAlias(String alias) {
        return "\"" + alias.replace("\"", "\"\"") + "\"";
    }

    private static String unquoteIdentifier(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '`' && trimmed.charAt(trimmed.length() - 1) == '`') {
            return trimmed.substring(1, trimmed.length() - 1).replace("``", "`");
        }
        return trimmed;
    }

    private static RenameResult renamePhysicalIdentifiers(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new RenameResult(sql == null ? "" : sql, false, false);
        }

        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(sql, index, converted);
            } else if (current == '"') {
                index = appendDoubleQuotedText(sql, index, converted);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = appendMyBatisPlaceholder(sql, index, converted);
            } else if (startsLineComment(sql, index)) {
                index = appendUntilLineEnd(sql, index, converted);
            } else if (startsBlockComment(sql, index)) {
                index = appendUntilBlockCommentEnd(sql, index, converted);
            } else if (isIdentifierStart(current)) {
                int end = index + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String identifier = sql.substring(index, end);
                String renamed = shouldPreserveRownumPseudoColumn(sql, index, end)
                        ? identifier
                        : renameColumnName(identifier);
                converted.append(renamed);
                changed = changed || !identifier.equals(renamed);
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new RenameResult(changed ? converted.toString() : sql, changed, false);
    }

    public static String renameColumnName(String columnName) {
        if (isReservedColumnName(columnName)) {
            return "_" + columnName;
        }
        return columnName;
    }

    private static List<TextRange> topLevelSelectLists(String sql) {
        List<TextRange> ranges = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuotedText(sql, index, current);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (depth == 0 && startsKeyword(sql, index, "SELECT")) {
                int start = index + "SELECT".length();
                int end = topLevelSelectListEnd(sql, start);
                ranges.add(new TextRange(start, end));
                index = Math.max(end, start);
            } else {
                index++;
            }
        }
        return ranges;
    }

    private static int topLevelSelectListEnd(String sql, int start) {
        int depth = 0;
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuotedText(sql, index, current);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth == 0) {
                    return index;
                }
                depth--;
                index++;
            } else if (depth == 0 && (startsKeyword(sql, index, "FROM")
                    || startsKeyword(sql, index, "UNION")
                    || startsKeyword(sql, index, "INTO")
                    || startsKeyword(sql, index, "ORDER")
                    || startsKeyword(sql, index, "LIMIT")
                    || startsKeyword(sql, index, "OFFSET")
                    || startsKeyword(sql, index, "FETCH")
                    || current == ';')) {
                return index;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private static List<TextRange> splitTopLevelCommaRanges(String value) {
        List<TextRange> ranges = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuotedText(value, index, current);
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (startsLineComment(value, index)) {
                index = skipUntilLineEnd(value, index);
            } else if (startsBlockComment(value, index)) {
                index = skipUntilBlockCommentEnd(value, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (current == ',' && depth == 0) {
                ranges.add(new TextRange(start, index));
                start = index + 1;
                index++;
            } else {
                index++;
            }
        }
        ranges.add(new TextRange(start, value.length()));
        return ranges;
    }

    private static boolean startsKeyword(String value, int index, String keyword) {
        if (index < 0 || index + keyword.length() > value.length()
                || !value.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        return (index == 0 || !isIdentifierPart(value.charAt(index - 1)))
                && (index + keyword.length() == value.length()
                || !isIdentifierPart(value.charAt(index + keyword.length())));
    }

    private static int skipQuotedText(String value, int start, char quote) {
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index++);
            if (current == '\\' && quote != '`' && index < value.length()) {
                index++;
            } else if (current == quote) {
                if (index < value.length() && value.charAt(index) == quote) {
                    index++;
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private static int skipMyBatisPlaceholder(String sql, int start) {
        int end = sql.indexOf('}', start + 2);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int skipUntilLineEnd(String sql, int start) {
        int end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int skipUntilBlockCommentEnd(String sql, int start) {
        int end = sql.indexOf("*/", start + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    public static boolean isReservedColumnName(String columnName) {
        return columnName != null && RESERVED_COLUMN_NAMES.contains(columnName.toUpperCase(Locale.ROOT));
    }

    private static boolean shouldPreserveRownumPseudoColumn(String sql, int start, int end) {
        if (!"ROWNUM".equalsIgnoreCase(sql.substring(start, end))) {
            return false;
        }
        if (previousNonWhitespace(sql, start) == '.' || nextNonWhitespace(sql, end) == '.') {
            return false;
        }
        int operatorStart = skipWhitespace(sql, end);
        int valueStart = comparisonOperatorEnd(sql, operatorStart);
        if (valueStart < 0) {
            return false;
        }
        valueStart = skipWhitespace(sql, valueStart);
        return startsNumericLiteral(sql, valueStart);
    }

    private static char previousNonWhitespace(String value, int index) {
        int current = index - 1;
        while (current >= 0 && Character.isWhitespace(value.charAt(current))) {
            current--;
        }
        return current < 0 ? '\0' : value.charAt(current);
    }

    private static char nextNonWhitespace(String value, int index) {
        int current = skipWhitespace(value, index);
        return current >= value.length() ? '\0' : value.charAt(current);
    }

    private static int skipWhitespace(String value, int index) {
        int current = Math.max(0, index);
        while (current < value.length() && Character.isWhitespace(value.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int comparisonOperatorEnd(String value, int index) {
        if (index >= value.length()) {
            return -1;
        }
        char current = value.charAt(index);
        if (current == '=' || current == '<' || current == '>') {
            if (index + 1 < value.length() && value.charAt(index + 1) == '=') {
                return index + 2;
            }
            return index + 1;
        }
        return -1;
    }

    private static boolean startsNumericLiteral(String value, int index) {
        if (index >= value.length()) {
            return false;
        }
        if ((value.charAt(index) == '+' || value.charAt(index) == '-') && index + 1 < value.length()) {
            index++;
        }
        return Character.isDigit(value.charAt(index));
    }

    private static int appendSingleQuotedString(String sql, int start, StringBuilder converted) {
        int index = start;
        converted.append(sql.charAt(index++));
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\\' && index < sql.length()) {
                converted.append(sql.charAt(index++));
            } else if (current == '\'') {
                if (index < sql.length() && sql.charAt(index) == '\'') {
                    converted.append(sql.charAt(index++));
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private static int appendDoubleQuotedText(String sql, int start, StringBuilder converted) {
        int index = start;
        converted.append(sql.charAt(index++));
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\\' && index < sql.length()) {
                converted.append(sql.charAt(index++));
            } else if (current == '"') {
                if (index < sql.length() && sql.charAt(index) == '"') {
                    converted.append(sql.charAt(index++));
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private static boolean startsMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length()
                && (sql.charAt(index) == '#' || sql.charAt(index) == '$')
                && sql.charAt(index + 1) == '{';
    }

    private static int appendMyBatisPlaceholder(String sql, int start, StringBuilder converted) {
        int end = sql.indexOf('}', start + 2);
        if (end < 0) {
            converted.append(sql, start, sql.length());
            return sql.length();
        }
        converted.append(sql, start, end + 1);
        return end + 1;
    }

    private static boolean startsLineComment(String sql, int index) {
        return sql.startsWith("--", index)
                || (sql.charAt(index) == '#' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '{'));
    }

    private static int appendUntilLineEnd(String sql, int start, StringBuilder converted) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private static boolean startsBlockComment(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*';
    }

    private static int appendUntilBlockCommentEnd(String sql, int start, StringBuilder converted) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                converted.append(sql.charAt(index++));
                break;
            }
        }
        return index;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private record TextRange(int start, int end) {
    }

    public record RenameResult(String convertedSql, boolean changed, boolean resultAliasAdded) {
    }
}
