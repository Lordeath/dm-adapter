package com.github.dmadapter.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record CliInvocation(List<String> arguments, Map<String, String> environment, Path reportDir) {
    CliInvocation {
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }
}
