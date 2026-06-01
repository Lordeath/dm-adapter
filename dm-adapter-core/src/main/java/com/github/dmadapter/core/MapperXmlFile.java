package com.github.dmadapter.core;

public record MapperXmlFile(
        String path,
        String resourcesRoot,
        String resourcesRelativePath
) {
    public MapperXmlFile {
        path = path == null ? "" : path;
        resourcesRoot = resourcesRoot == null ? "" : resourcesRoot;
        resourcesRelativePath = resourcesRelativePath == null ? "" : resourcesRelativePath;
    }

    public MapperXmlFile(String path, String resourcesRelativePath) {
        this(path, "", resourcesRelativePath);
    }
}
