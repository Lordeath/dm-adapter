package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;

import java.nio.file.Path;

interface DependencyTreeInspector {
    DependencyTreeAnalysis analyze(Path projectRoot, DependencyCoordinate dmDriverCoordinate);
}
