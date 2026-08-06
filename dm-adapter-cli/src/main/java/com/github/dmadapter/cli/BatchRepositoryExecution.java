package com.github.dmadapter.cli;

import com.github.dmadapter.core.BatchRepositoryReport;

record BatchRepositoryExecution(int exitCode, BatchRepositoryReport report) {
}
