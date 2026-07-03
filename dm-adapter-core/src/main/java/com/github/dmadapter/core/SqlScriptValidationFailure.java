package com.github.dmadapter.core;

public record SqlScriptValidationFailure(
        String sourceFile,
        String outputFile,
        String schema,
        int statementIndex,
        String category,
        String errorSummary,
        String failedSqlSummary
) {
}
