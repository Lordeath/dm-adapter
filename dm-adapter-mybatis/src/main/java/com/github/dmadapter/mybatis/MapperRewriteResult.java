package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.SqlChange;

import java.util.List;

record MapperRewriteResult(
        List<SqlChange> automaticConversions,
        List<SqlChange> manualReviewItems,
        List<String> warnings
) {
    MapperRewriteResult {
        automaticConversions = List.copyOf(automaticConversions == null ? List.of() : automaticConversions);
        manualReviewItems = List.copyOf(manualReviewItems == null ? List.of() : manualReviewItems);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
