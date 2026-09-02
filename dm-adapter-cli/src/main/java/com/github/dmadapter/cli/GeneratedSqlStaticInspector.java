package com.github.dmadapter.cli;

import java.util.Locale;
import java.util.regex.Pattern;

final class GeneratedSqlStaticInspector {
    private static final String REASON_PREFIX = "DM_OUTPUT_STATIC_GATE: ";
    private static final Pattern DELIMITER = Pattern.compile("(?i)\\bDELIMITER\\b");
    private static final Pattern SCRIPT_VARIABLE = Pattern.compile("@[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern ENGINE = Pattern.compile("(?i)\\bENGINE\\s*=");
    private static final Pattern USING_BTREE = Pattern.compile("(?i)\\bUSING\\s+BTREE\\b");
    private static final Pattern ALTER_AFTER = Pattern.compile("(?i)\\bAFTER\\b");
    private static final Pattern INLINE_COMMENT = Pattern.compile("(?i)\\bCOMMENT\\b(?!\\s+ON\\b)");

    private GeneratedSqlStaticInspector() {
    }

    static String manualReviewReason(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        Scan scan = scan(sql);
        if (!scan.reason().isBlank()) {
            return REASON_PREFIX + scan.reason();
        }
        String searchable = scan.searchableSql();
        if (DELIMITER.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的 SQL 仍包含 DELIMITER。";
        }
        if (searchable.contains("$$") || searchable.contains("//")) {
            return REASON_PREFIX + "生成的 SQL 仍包含 MySQL 存储过程分隔符。";
        }
        if (SCRIPT_VARIABLE.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的 SQL 仍包含脚本级 @变量。";
        }
        if (ENGINE.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的 SQL 仍包含 ENGINE 表选项。";
        }
        if (USING_BTREE.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的 SQL 仍包含 USING BTREE。";
        }
        String upper = searchable.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("ALTER TABLE") && ALTER_AFTER.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的 ALTER TABLE 仍包含 AFTER。";
        }
        if ((upper.startsWith("CREATE TABLE") || upper.startsWith("ALTER TABLE"))
                && INLINE_COMMENT.matcher(searchable).find()) {
            return REASON_PREFIX + "生成的表 DDL 仍包含行内 COMMENT 子句。";
        }
        if (isRoutineOrAnonymousBlock(upper) && !Pattern.compile("(?is)\\bEND\\s*;?\\s*$")
                .matcher(searchable)
                .find()) {
            return REASON_PREFIX + "生成的存储过程或匿名块没有以 END 结束。";
        }
        return "";
    }

    private static boolean isRoutineOrAnonymousBlock(String upper) {
        return upper.startsWith("CREATE PROCEDURE")
                || upper.startsWith("CREATE OR REPLACE PROCEDURE")
                || upper.startsWith("CREATE FUNCTION")
                || upper.startsWith("CREATE OR REPLACE FUNCTION")
                || upper.startsWith("DECLARE")
                || upper.startsWith("BEGIN");
    }

    private static Scan scan(String sql) {
        StringBuilder searchable = new StringBuilder(sql.length());
        int parentheses = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int start = index++;
                searchable.append(' ');
                boolean closed = false;
                while (index < sql.length()) {
                    current = sql.charAt(index);
                    if (current == '\r' || current == '\n') {
                        return new Scan("单引号字符串跨越了物理行，位置偏移量："
                                + index + "。", searchable.toString());
                    }
                    searchable.append(' ');
                    if (current == '\\' && index + 1 < sql.length()) {
                        searchable.append(' ');
                        index += 2;
                    } else if (current == '\'' && index + 1 < sql.length()
                            && sql.charAt(index + 1) == '\'') {
                        searchable.append(' ');
                        index += 2;
                    } else if (current == '\'') {
                        index++;
                        closed = true;
                        break;
                    } else {
                        index++;
                    }
                }
                if (!closed) {
                    return new Scan("从位置偏移量 " + start + " 开始的单引号字符串未闭合。",
                            searchable.toString());
                }
                continue;
            }
            if (current == '"' || current == '`') {
                char quote = current;
                int start = index++;
                searchable.append(' ');
                boolean closed = false;
                while (index < sql.length()) {
                    current = sql.charAt(index);
                    searchable.append(current == '\r' || current == '\n' ? current : ' ');
                    if (current == quote && index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                        searchable.append(' ');
                        index += 2;
                    } else if (current == quote) {
                        index++;
                        closed = true;
                        break;
                    } else {
                        index++;
                    }
                }
                if (!closed) {
                    return new Scan("从位置偏移量 " + start + " 开始的带引号标识符未闭合。",
                            searchable.toString());
                }
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int start = index;
                searchable.append("  ");
                index += 2;
                boolean closed = false;
                while (index < sql.length()) {
                    current = sql.charAt(index);
                    if (current == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
                        searchable.append("  ");
                        index += 2;
                        closed = true;
                        break;
                    }
                    searchable.append(current == '\r' || current == '\n' ? current : ' ');
                    index++;
                }
                if (!closed) {
                    return new Scan("从位置偏移量 " + start + " 开始的块注释未闭合。",
                            searchable.toString());
                }
                continue;
            }
            if ((current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-')
                    || current == '#') {
                while (index < sql.length() && sql.charAt(index) != '\r' && sql.charAt(index) != '\n') {
                    searchable.append(' ');
                    index++;
                }
                continue;
            }
            if (current == '(') {
                parentheses++;
            } else if (current == ')') {
                parentheses--;
                if (parentheses < 0) {
                    return new Scan("生成的 SQL 在位置偏移量 " + index + " 处存在无法匹配的右括号。",
                            searchable.toString());
                }
            }
            searchable.append(current);
            index++;
        }
        if (parentheses != 0) {
            return new Scan("生成的 SQL 存在 " + parentheses + " 个未闭合的左括号。",
                    searchable.toString());
        }
        return new Scan("", searchable.toString());
    }

    private record Scan(String reason, String searchableSql) {
    }
}
