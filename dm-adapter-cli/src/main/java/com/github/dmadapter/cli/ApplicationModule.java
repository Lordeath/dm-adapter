package com.github.dmadapter.cli;

import java.nio.file.Path;

record ApplicationModule(
        Path moduleRoot,
        Path pomPath,
        Path applicationClassPath,
        String packageName
) {
}
