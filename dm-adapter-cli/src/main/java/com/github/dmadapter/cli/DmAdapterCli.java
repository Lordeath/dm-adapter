package com.github.dmadapter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "dm-adapter",
        mixinStandardHelpOptions = true,
        version = "dm-adapter 0.1.0-SNAPSHOT",
        description = "Assist Spring Boot + MyBatis + Maven projects with Dameng database adaptation.",
        subcommands = {
                ScanCommand.class,
                MigrateCommand.class,
                ReportCommand.class
        }
)
public class DmAdapterCli implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new DmAdapterCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
