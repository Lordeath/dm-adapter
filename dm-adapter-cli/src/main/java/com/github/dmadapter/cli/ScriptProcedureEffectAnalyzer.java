package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative effect analysis of the definitions in scope at each CALL. Never executes SQL. */
final class ScriptProcedureEffectAnalyzer {
    private static final Set<String> TYPES = Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "VARCHAR", "VARCHAR2", "CHAR", "CHARACTER", "TEXT", "LONGTEXT", "CLOB", "BLOB", "JSON",
            "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "DOUBLE", "REAL", "DATE", "DATETIME", "TIMESTAMP",
            "TIME", "BOOLEAN", "BOOL", "BIT", "BINARY", "VARBINARY", "MEDIUMINT");
    private static final Set<String> FUNCTIONS = Set.of("CONCAT", "CONCAT_WS", "COALESCE", "NULLIF", "IFNULL",
            "MIN", "MAX", "COUNT", "SUM", "AVG", "NOW", "UUID", "CURRENT_TIMESTAMP", "LENGTH",
            "CHAR_LENGTH", "UPPER", "LOWER", "DATABASE", "TRIM", "LTRIM", "RTRIM", "CAST", "CONVERT",
            "REPLACE", "REVERSE", "SUBSTR", "SUBSTRING", "LEFT", "RIGHT", "ROUND", "ABS", "CEIL", "FLOOR",
            "INSTR", "LOCATE", "IF", "TO_CHAR", "TO_DATE", "NVL", "TIMESTAMPDIFF", "DATE_ADD",
            "DATE_SUB", "GROUP_CONCAT", "ROW_NUMBER");
    private static final Set<String> SQL_GROUPS = Set.of("EXISTS", "IN", "VALUES", "VALUE", "NOT", "AND", "OR",
            "SELECT", "FROM", "JOIN", "WHERE", "ON", "AS", "KEY", "PRIMARY", "UNIQUE", "INDEX", "CHECK",
            "REFERENCES", "USING", "DEFAULT", "CASE", "WHEN", "THEN", "ELSE", "OVER");

    record Effects(boolean known, Set<String> mutationTargets, Set<String> assignedVariables,
                   Set<String> readVariables, String reason) {
        Effects {
            mutationTargets = Set.copyOf(mutationTargets);
            assignedVariables = Set.copyOf(assignedVariables);
            readVariables = Set.copyOf(readVariables);
        }

        static Effects unknown(String reason) {
            return new Effects(false, Set.of(), Set.of(), Set.of(), reason);
        }
    }

    private record Name(String schema, String name) {
        String display() { return schema.isEmpty() ? name : schema + "." + name; }
    }
    private record Call(Name name, List<Token> arguments, int arity) { }
    private record Definition(Name name, List<Token> tokens, int body, Set<String> locals,
                              int arity, String unsupported) { }
    private record Direct(Effects effects, List<Call> calls) { }

    private final Map<Name, Definition> initialDefinitions = new LinkedHashMap<>();
    private final Map<Definition, Direct> directCache = new HashMap<>();
    private final Map<String, Map<Name, Definition>> scopes = new HashMap<>();

    ScriptProcedureEffectAnalyzer(List<String> sources) {
        for (String source : sources) {
            List<Token> tokens = lex(source);
            Definition definition = definition(tokens, "");
            if (definition != null) {
                initialDefinitions.put(definition.name(), definition);
            } else {
                Name drop = dropped(tokens, "");
                if (drop != null) initialDefinitions.remove(drop);
            }
        }
    }

    Map<Integer, Effects> analyze(List<String> statements, String schema) {
        return analyze(statements, schema, schema);
    }

    Map<Integer, Effects> analyze(List<String> statements, String schema, String scope) {
        Map<Name, Definition> definitions = scopes.computeIfAbsent(scope, ignored -> {
            Map<Name, Definition> seeded = new LinkedHashMap<>();
            initialDefinitions.forEach((key, definition) -> {
                Definition scoped = definition(definition.tokens(), schema);
                seeded.put(scoped.name(), scoped);
            });
            return seeded;
        });
        Map<Integer, Effects> result = new LinkedHashMap<>();
        for (int i = 0; i < statements.size(); i++) {
            List<Token> tokens = lex(statements.get(i));
            Definition created = definition(tokens, schema);
            if (created != null) {
                definitions.put(created.name(), created);
                continue;
            }
            Name drop = dropped(tokens, schema);
            if (drop != null) {
                definitions.remove(drop);
                continue;
            }
            if (keyword(tokens, 0, "CALL")) {
                Call call = call(tokens, schema);
                Effects effects = call == null ? Effects.unknown("无法解析 CALL 的名称或参数")
                        : resolve(call, definitions, new HashSet<>());
                result.put(i, effects);
                // An opaque routine may replace a previously defined routine too.
                if (!effects.known()) definitions.clear();
            } else if (keyword(tokens, 0, "USE") || keyword(tokens, 0, "EXECUTE")
                    || keyword(tokens, 0, "EXECUTABLE_COMMENT") || keyword(tokens, 0, "UNCLOSED_COMMENT")
                    || keyword(tokens, 0, "UNCLOSED_QUOTE")
                    || keyword(tokens, 0, "SET") && (keyword(tokens, 1, "SCHEMA")
                    || keyword(tokens, 1, "CURRENT"))) {
                definitions.clear();
            }
        }
        return Map.copyOf(result);
    }

    private Effects resolve(Call call, Map<Name, Definition> definitions, Set<Name> visiting) {
        Definition definition = definitions.get(call.name());
        if (definition == null) return Effects.unknown("缺少调用前生效的过程定义：" + call.name().display());
        Direct direct = directCache.computeIfAbsent(definition, this::analyzeDefinition);
        if (!definition.unsupported().isEmpty()) {
            // In particular an OUT/INOUT argument must remain an assignable variable,
            // even when this CALL is its last textual use in the script.
            Set<String> reads = new LinkedHashSet<>(direct.effects().readVariables());
            call.arguments().stream().filter(Token::variable).forEach(token -> reads.add(token.value()));
            return new Effects(false, direct.effects().mutationTargets(), direct.effects().assignedVariables(),
                    reads, direct.effects().reason());
        }
        if (call.arity() != definition.arity()) return Effects.unknown("CALL 参数数量与定义不一致：" + call.name().display());
        if (!pure(call.arguments(), Set.of(), false)) return Effects.unknown("CALL 参数含未知函数或赋值：" + call.name().display());
        if (!direct.effects().known()) return direct.effects();
        if (visiting.size() >= 64 || !visiting.add(call.name())) return Effects.unknown("递归调用无法静态分析：" + call.name().display());
        Set<String> tables = new LinkedHashSet<>(direct.effects().mutationTargets());
        Set<String> writes = new LinkedHashSet<>(direct.effects().assignedVariables());
        Set<String> reads = new LinkedHashSet<>(direct.effects().readVariables());
        for (Call nested : direct.calls()) {
            Effects child = resolve(nested, definitions, visiting);
            tables.addAll(child.mutationTargets());
            writes.addAll(child.assignedVariables());
            reads.addAll(child.readVariables());
            if (!child.known()) {
                visiting.remove(call.name());
                return new Effects(false, tables, writes, reads, call.name().display() + " -> " + child.reason());
            }
        }
        visiting.remove(call.name());
        return new Effects(true, tables, writes, reads, "");
    }

    private Direct analyzeDefinition(Definition definition) {
        Set<String> reads = new LinkedHashSet<>();
        definition.tokens().stream().filter(Token::variable).forEach(t -> reads.add(t.value()));
        Body body = new Body(definition);
        try {
            if (!definition.unsupported().isEmpty()) throw new Unsupported(definition.unsupported());
            body.block();
            body.cursor.take(";");
            if (!body.cursor.end()) throw new Unsupported("过程结束后的未知内容");
            return new Direct(new Effects(true, body.tables, body.writes, reads, ""), List.copyOf(body.calls));
        } catch (Unsupported e) {
            return new Direct(new Effects(false, body.tables, body.writes, reads,
                    definition.name().display() + "：" + e.getMessage()), List.of());
        }
    }

    private final class Body {
        final Definition definition;
        final Cursor cursor;
        final Set<String> tables = new LinkedHashSet<>();
        final Set<String> writes = new LinkedHashSet<>();
        final Set<String> locals;
        final List<Call> calls = new ArrayList<>();
        int depth;

        Body(Definition definition) {
            this.definition = definition;
            this.cursor = new Cursor(definition.tokens(), definition.body());
            this.locals = new HashSet<>(definition.locals());
        }

        void block() {
            if (++depth > 64) throw new Unsupported("控制流嵌套过深");
            cursor.require("BEGIN");
            sequence();
            cursor.require("END");
            depth--;
        }

        void sequence() {
            while (!cursor.end() && !cursor.at("END") && !cursor.at("ELSE") && !cursor.at("ELSEIF")) {
                if (cursor.at("BEGIN")) {
                    block();
                    cursor.require(";");
                } else if (cursor.take("IF")) {
                    if (++depth > 64) throw new Unsupported("控制流嵌套过深");
                    expression(cursor.until("THEN"), false, Set.of());
                    cursor.require("THEN");
                    sequence();
                    while (cursor.take("ELSEIF")) {
                        expression(cursor.until("THEN"), false, Set.of());
                        cursor.require("THEN");
                        sequence();
                    }
                    if (cursor.take("ELSE")) sequence();
                    cursor.require("END"); cursor.require("IF"); cursor.require(";");
                    depth--;
                } else {
                    List<Token> statement = cursor.until(";");
                    cursor.require(";");
                    statement(statement);
                }
            }
        }

        void statement(List<Token> tokens) {
            if (tokens.isEmpty()) return;
            Cursor c = new Cursor(tokens, 0);
            Set<Integer> groups = new HashSet<>();
            if (c.take("CALL")) {
                Call call = call(tokens, definition.name().schema());
                if (call == null) throw new Unsupported("无法解析嵌套 CALL");
                calls.add(call);
                return;
            }
            if (c.take("DECLARE")) {
                localDeclaration(c, locals);
                return;
            }
            if (c.take("SET")) {
                assignment(c, "=");
                return;
            }
            if (tokens.size() > 1 && tokens.get(1).is(":=")) {
                assignment(c, ":=");
                return;
            }
            if (c.take("RETURN")) {
                if (!c.end()) throw new Unsupported("不支持带值 RETURN");
                return;
            }
            if (c.take("NULL")) {
                if (!c.end()) throw new Unsupported("未知 NULL 语句");
                return;
            }
            if (c.take("INSERT")) {
                c.take("IGNORE"); c.require("INTO");
                tables.add(c.name("").name());
                groups.add(c.position);
                expression(tokens, false, groups);
                return;
            }
            if (c.take("UPDATE") || c.take("DELETE")) {
                boolean delete = keyword(tokens, 0, "DELETE");
                if (delete) c.require("FROM");
                tables.add(c.name("").name());
                if (!delete) {
                    int set = topLevel(tokens, "SET", c.position);
                    if (set < 0) throw new Unsupported("UPDATE 缺少 SET");
                    // Over-approximate a joined update: every base table could be a write target.
                    // This also covers derived tables without guessing which alias SET updates.
                    int depth = 0;
                    for (int i = c.position; i < set; i++) {
                        Token token = tokens.get(i);
                        if (token.is("(")) depth++;
                        else if (token.is(")")) depth--;
                        if (token.is("JOIN") || token.is("FROM") || depth == 0 && token.is(",")) {
                            Cursor table = new Cursor(tokens, i + 1);
                            if (!table.at("(")) tables.add(table.name("").name());
                        }
                    }
                    expression(tokens, false, groups);
                    return;
                }
                if (c.take("AS")) c.identifier();
                else if (!c.end() && !c.at("SET") && !c.at("WHERE") && !c.at("ORDER") && !c.at("LIMIT")) c.identifier();
                if (delete) {
                    if (!c.end() && !c.at("WHERE") && !c.at("ORDER") && !c.at("LIMIT")) throw new Unsupported("多表 DELETE");
                } else c.require("SET");
                expression(tokens, false, groups);
                return;
            }
            if (c.take("SELECT")) {
                int into = topLevel(tokens, "INTO", 1);
                if (into >= 0) {
                    Cursor outputs = new Cursor(tokens, into + 1);
                    do { output(outputs.next()); } while (outputs.take(","));
                    if (!outputs.end() && !outputs.at("FROM")) throw new Unsupported("SELECT INTO 输出无法解析");
                }
                expression(tokens, false, groups);
                return;
            }
            if (c.take("CREATE")) {
                c.take("UNIQUE");
                if (c.take("INDEX")) {
                    c.name(""); c.require("ON");
                    tables.add(c.name("").name()); groups.add(c.position);
                } else {
                    c.require("TABLE");
                    if (c.take("IF")) { c.require("NOT"); c.require("EXISTS"); }
                    tables.add(c.name("").name()); groups.add(c.position);
                }
                metadataWrites();
                expression(tokens, true, groups);
                return;
            }
            if (c.take("ALTER")) {
                c.require("TABLE"); tables.add(c.name("").name());
                if (!c.at("ADD") && !c.at("MODIFY") && !c.at("CHANGE") && !c.at("DROP")) throw new Unsupported("未支持的 ALTER TABLE 操作");
                metadataWrites();
                expression(tokens, true, groups);
                return;
            }
            throw new Unsupported("无法分析语句 " + tokens.get(0).value());
        }

        void metadataWrites() {
            tables.add("columns"); tables.add("tables"); tables.add("statistics");
        }

        void output(Token token) {
            if (token.variable()) writes.add(token.value());
            else if (!token.identifier() || !locals.contains(token.value())) throw new Unsupported("赋值目标不是用户变量或已声明局部变量");
        }

        void assignment(Cursor c, String operator) {
            output(c.next());
            if (!c.take(operator) && !(operator.equals("=") && c.take(":="))) throw new Unsupported("无法解析赋值");
            if (topLevel(c.rest(), ",", 0) >= 0) throw new Unsupported("尚未支持多变量 SET 的影响分析");
            expression(c.rest(), false, Set.of());
        }

        void expression(List<Token> tokens, boolean ddl, Set<Integer> groups) {
            if (ddl) {
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).is("REFERENCES") || tokens.get(i).is("KEY") || tokens.get(i).is("INDEX")) {
                        Cursor object = new Cursor(tokens, i + 1);
                        if (!object.end() && !object.at("(")) {
                            object.name("");
                            groups.add(object.position);
                        }
                    }
                }
            }
            String reason = expressionIssue(tokens, groups, ddl);
            if (!reason.isEmpty()) throw new Unsupported(reason);
        }
    }

    private static boolean pure(List<Token> tokens, Set<Integer> groups, boolean ddl) {
        return expressionIssue(tokens, groups, ddl).isEmpty();
    }

    private static String expressionIssue(List<Token> tokens, Set<Integer> groups, boolean ddl) {
        int balance = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.is(":=") || token.is("@@") || token.is("PREPARE") || token.is("EXECUTE")) {
                return "表达式包含赋值、会话状态或动态 SQL：" + token.value();
            }
            if (token.is("(")) balance++;
            if (token.is(")") && --balance < 0) return "表达式括号不平衡";
            if (token.identifier() && i + 1 < tokens.size() && tokens.get(i + 1).is("(") && !groups.contains(i + 1)) {
                String name = token.value().toUpperCase(Locale.ROOT);
                boolean type = TYPES.contains(name) && (ddl || i > 0 && tokens.get(i - 1).is("AS"));
                if (i > 0 && tokens.get(i - 1).is(".") || token.quoted()
                        || !FUNCTIONS.contains(name) && !SQL_GROUPS.contains(name) && !type) {
                    return "表达式包含未知函数：" + token.value();
                }
            }
        }
        return balance == 0 ? "" : "表达式括号不平衡";
    }

    private static void localDeclaration(Cursor c, Set<String> locals) {
        Token name = c.identifier();
        if (name.variable()) throw new Unsupported("局部变量声明错误");
        Token type = c.identifier();
        if (!TYPES.contains(type.value().toUpperCase(Locale.ROOT))) throw new Unsupported("不支持的局部变量类型");
        if (c.take("(")) { c.until(")"); c.require(")"); }
        if (c.take("DEFAULT") || c.take(":=")) {
            if (!pure(c.rest(), Set.of(), false)) throw new Unsupported("局部变量初始值包含未知函数");
            c.position = c.tokens.size();
        }
        if (!c.end()) throw new Unsupported("不支持的局部变量声明");
        locals.add(name.value());
    }

    private static Definition definition(List<Token> tokens, String schema) {
        Cursor c = new Cursor(tokens, 0);
        if (!c.take("CREATE")) return null;
        if (c.take("OR")) { if (!c.take("REPLACE")) return null; }
        if (c.take("DEFINER")) {
            while (!c.end() && !c.at("PROCEDURE")) c.next();
        }
        if (!c.take("PROCEDURE")) return null;
        Name name;
        try { name = c.name(schema); } catch (Unsupported e) { return null; }
        int arity = 0;
        Set<String> locals = new LinkedHashSet<>();
        try {
            c.require("(");
            if (!c.take(")")) {
                do {
                    if (c.at("OUT") || c.at("INOUT")) throw new Unsupported("OUT/INOUT 参数尚未支持影响分析");
                    c.take("IN");
                    Token parameter = c.identifier();
                    locals.add(parameter.value());
                    if (c.at("OUT") || c.at("INOUT")) throw new Unsupported("OUT/INOUT 参数尚未支持影响分析");
                    c.take("IN");
                    Token type = c.identifier();
                    if (!TYPES.contains(type.value().toUpperCase(Locale.ROOT))) throw new Unsupported("未知参数类型");
                    if (c.take("(")) { c.until(")"); c.require(")"); }
                    arity++;
                } while (c.take(","));
                c.require(")");
            }
            if (c.take("AS") || c.take("IS")) {
                while (!c.end() && !c.at("BEGIN")) {
                    localDeclaration(new Cursor(c.until(";"), 0), locals);
                    c.require(";");
                }
            }
            if (!c.at("BEGIN")) throw new Unsupported("无法识别过程体 BEGIN");
            return new Definition(name, tokens, c.position, Set.copyOf(locals), arity, "");
        } catch (Unsupported e) {
            return new Definition(name, tokens, c.position, Set.copyOf(locals), arity, e.getMessage());
        }
    }

    private static Name dropped(List<Token> tokens, String schema) {
        Cursor c = new Cursor(tokens, 0);
        if (!c.take("DROP") || !c.take("PROCEDURE")) return null;
        try {
            if (c.take("IF")) c.require("EXISTS");
            return c.name(schema);
        } catch (Unsupported e) { return null; }
    }

    private static Call call(List<Token> tokens, String schema) {
        Cursor c = new Cursor(tokens, 0);
        try {
            c.require("CALL");
            Name name = c.name(schema);
            List<Token> arguments = List.of();
            int arity = 0;
            if (c.take("(")) {
                arguments = c.until(")"); c.require(")");
                if (!arguments.isEmpty()) {
                    arity = 1;
                    int depth = 0;
                    for (Token token : arguments) {
                        if (token.is("(")) depth++;
                        else if (token.is(")")) depth--;
                        else if (token.is(",") && depth == 0) arity++;
                    }
                }
            }
            c.take(";");
            return c.end() ? new Call(name, arguments, arity) : null;
        } catch (Unsupported e) { return null; }
    }

    private static int topLevel(List<Token> tokens, String keyword, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (depth == 0 && t.is(keyword)) return i;
            if (t.is("(")) depth++;
            else if (t.is(")")) depth--;
        }
        return -1;
    }

    private static boolean keyword(List<Token> tokens, int index, String word) {
        return index < tokens.size() && tokens.get(index).is(word);
    }

    private record Token(String value, int kind) {
        boolean is(String word) { return kind != 2 && kind != 3 && kind != 4 && value.equalsIgnoreCase(word); }
        boolean identifier() { return kind == 1 || kind == 3; }
        boolean variable() { return kind == 4; }
        boolean quoted() { return kind == 3; }
    }

    private static final class Cursor {
        final List<Token> tokens;
        int position;
        Cursor(List<Token> tokens, int position) { this.tokens = tokens; this.position = position; }
        boolean end() { return position >= tokens.size(); }
        boolean at(String word) { return !end() && tokens.get(position).is(word); }
        boolean take(String word) { if (!at(word)) return false; position++; return true; }
        void require(String word) { if (!take(word)) throw new Unsupported("预期 " + word); }
        Token next() { if (end()) throw new Unsupported("语句不完整"); return tokens.get(position++); }
        Token identifier() { Token t = next(); if (!t.identifier()) throw new Unsupported("预期标识符"); return t; }
        Name name(String schema) {
            String first = identifier().value();
            if (take(".")) return new Name(first, identifier().value());
            return new Name(schema.toLowerCase(Locale.ROOT), first);
        }
        List<Token> rest() { return tokens.subList(position, tokens.size()); }
        List<Token> until(String word) {
            int start = position;
            int depth = 0;
            while (!end()) {
                if (depth == 0 && at(word)) return tokens.subList(start, position);
                Token t = next();
                if (t.is("(")) depth++;
                else if (t.is(")") && --depth < 0) throw new Unsupported("括号不平衡");
            }
            throw new Unsupported("缺少 " + word);
        }
    }

    private static List<Token> lex(String sql) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i) && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2))) || c == '#') {
                int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end + 1; continue;
            }
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return List.of(new Token("UNCLOSED_COMMENT", 0));
                // Executable MySQL comments are not ordinary comments.
                if (sql.startsWith("/*!", i)) return List.of(new Token("EXECUTABLE_COMMENT", 0));
                i = end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                for (i++; i < sql.length(); i++) {
                    char next = sql.charAt(i);
                    if (next == c) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == c) { value.append(c); i++; }
                        else { i++; closed = true; break; }
                    } else if (next == '\\' && c == '\'' && i + 1 < sql.length()) { value.append(sql.charAt(++i)); }
                    else value.append(next);
                }
                if (!closed) return List.of(new Token("UNCLOSED_QUOTE", 0));
                tokens.add(new Token(value.toString().toLowerCase(Locale.ROOT), c == '\'' ? 2 : 3)); continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '@') {
                int start = i++;
                if (c == '@' && i < sql.length() && sql.charAt(i) == '@') { tokens.add(new Token("@@", 0)); i++; continue; }
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || "_$".indexOf(sql.charAt(i)) >= 0)) i++;
                tokens.add(new Token(sql.substring(c == '@' ? start + 1 : start, i).toLowerCase(Locale.ROOT), c == '@' ? 4 : 1)); continue;
            }
            if (sql.startsWith(":=", i)) { tokens.add(new Token(":=", 0)); i += 2; }
            else { tokens.add(new Token(String.valueOf(c), 0)); i++; }
        }
        return List.copyOf(tokens);
    }

    private static final class Unsupported extends RuntimeException {
        Unsupported(String reason) { super(reason, null, false, false); }
    }
}
