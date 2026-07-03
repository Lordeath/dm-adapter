package com.github.dmadapter.core;

public record SqlScriptManualReviewItem(
        String sourceFile,
        String outputFile,
        int statementIndex,
        String reason,
        String originalSql,
        String convertedSql
) {
}
