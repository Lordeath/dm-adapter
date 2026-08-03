package com.github.dmadapter.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.DmAdapterSummary;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SummaryIssue;
import com.github.dmadapter.core.SummaryStage;
import com.github.dmadapter.core.TargetLengthSemantics;
import com.github.dmadapter.report.ReportReader;
import com.github.dmadapter.report.ReportWriter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;

final class DmAdapterFrame extends JFrame {
    private final JTextField projectField = new JTextField();
    private final JTextField reportDirField = new JTextField();
    private final JTextField appModuleField = new JTextField();
    private final JTextField mapperDirField = new JTextField();
    private final JTextField sqlRootField = new JTextField();
    private final JTextField sqlRootOutField = new JTextField();
    private final JCheckBox sqlScriptsOnlyBox = new JCheckBox("仅迁移 SQL 脚本 (--sql-scripts-only)");
    private final JTextField schemaField = new JTextField();
    private final JTextField systemSchemaField = new JTextField();
    private final JCheckBox databaseValidationBox = new JCheckBox(
            "启用达梦数据库验证与元数据探测 (DM_SQL_VALIDATION=true)"
    );
    private final JTextField jdbcUrlField = new JTextField();
    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox generateValidationTestBox = new JCheckBox(
            "生成 Mapper 验证测试 (--generate-validation-test；启用数据库验证时自动运行)"
    );
    private final JTextField dmDriverField = new JTextField();
    private final JTextField rewriteConfigField = new JTextField();
    private final JTextField validationConfigField = new JTextField();
    private final JComboBox<String> targetLengthSemanticsBox = new JComboBox<>(new String[]{
            "自动探测", TargetLengthSemantics.CHAR.name(), TargetLengthSemantics.BYTE.name()
    });
    private final JTextArea summaryArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("就绪");
    private final JButton scanButton = new JButton("扫描项目 (scan)");
    private final JButton dryRunButton = new JButton("迁移预览 (migrate --dry-run)");
    private final JButton migrateButton = new JButton("执行迁移 (migrate)");
    private final JButton cancelButton = new JButton("取消");
    private final JButton openReportButton = new JButton("打开报告");
    private final JButton openWorkspaceButton = new JButton("打开工作目录");

    private final CliCommandBuilder commandBuilder = new CliCommandBuilder();
    private final CliProcessRunner processRunner = new CliProcessRunner();
    private final ReportReader reportReader = new ReportReader();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Timer summaryTimer;
    private GuiOperation lastOperation;
    private Path lastReportDir = CliCommandBuilder.defaultReportDir();

    DmAdapterFrame() {
        super("dm-adapter GUI");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setSize(1180, 820);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(createConfigurationTabs(), BorderLayout.NORTH);
        add(createOutputPanel(), BorderLayout.CENTER);
        add(createActionPanel(), BorderLayout.SOUTH);
        bindActions();
        updateDatabaseFields();
        summaryTimer = new Timer(800, event -> refreshSummary(false));
    }

    private JTabbedPane createConfigurationTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("项目", createProjectPanel());
        tabs.addTab("SQL 脚本", createSqlPanel());
        tabs.addTab("数据库验证", createDatabasePanel());
        tabs.addTab("高级", createAdvancedPanel());
        tabs.setPreferredSize(new Dimension(1100, 235));
        return tabs;
    }

    private JPanel createProjectPanel() {
        JPanel panel = formPanel();
        addPathRow(panel, 0, "项目根目录 (--project) *", projectField, true, null);
        addPathRow(panel, 1, "工作目录 (--report-dir；留空=当前目录)", reportDirField, true, null);
        addTextRow(panel, 2, "应用模块 (--app-module)", appModuleField,
                "可填写 Maven artifactId 或模块路径；留空时由 CLI 自动发现。");
        addPathRow(panel, 3, "Mapper 输出目录 (--mapper-dir)", mapperDirField, true, null);
        JLabel hint = new JLabel("留空时继续使用 CLI 默认目录：各模块 src/main/resources/mapper-dm；不会覆盖原始 mapper XML。");
        hint.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 2));
        addFullWidth(panel, 4, hint);
        return panel;
    }

    private JPanel createSqlPanel() {
        JPanel panel = formPanel();
        addPathRow(panel, 0, "MySQL SQL 源目录 (--sql-root)", sqlRootField, true, null);
        addPathRow(panel, 1, "达梦 SQL 输出目录 (--sql-root-out)", sqlRootOutField, true, null);
        addTextRow(panel, 2, "业务 schema (--schema)", schemaField, "支持逗号分隔的候选 schema。");
        addTextRow(panel, 3, "system schema (--system-schema)", systemSchemaField,
                "用于文件名中包含独立 system 段的 SQL 脚本。");
        addFullWidth(panel, 4, sqlScriptsOnlyBox);
        return panel;
    }

    private JPanel createDatabasePanel() {
        JPanel panel = formPanel();
        addFullWidth(panel, 0, databaseValidationBox);
        addTextRow(panel, 1, "JDBC URL (DM_JDBC_URL)", jdbcUrlField,
                "例如 jdbc:dm://127.0.0.1:5236?schema=APP。");
        addTextRow(panel, 2, "用户名 (DM_DB_USERNAME)", usernameField,
                "仅注入 CLI 子进程环境，不写入配置或报告。");
        addComponentRow(panel, 3, "密码 (DM_DB_PASSWORD)", passwordField,
                "密码不会出现在命令参数和 GUI 日志中。");
        addFullWidth(panel, 4, generateValidationTestBox);
        JLabel warning = new JLabel("警告：启用验证后可能真实修改共享测试库，SQL 脚本按清单执行且不自动回滚。");
        warning.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 2));
        addFullWidth(panel, 5, warning);
        return panel;
    }

    private JPanel createAdvancedPanel() {
        JPanel panel = formPanel();
        addTextRow(panel, 0, "达梦驱动坐标 (--dm-driver)", dmDriverField, "留空使用 CLI 默认坐标。");
        addPathRow(panel, 1, "SQL 重写配置 (--rewrite-config)", rewriteConfigField, false, null);
        addPathRow(panel, 2, "验证配置 (--config)", validationConfigField, false, null);
        addComponentRow(panel, 3, "字符长度语义 (--target-length-semantics)", targetLengthSemanticsBox,
                "离线迁移可显式选择 CHAR 或 BYTE；默认由目标库探测。");
        return panel;
    }

    private JSplitPane createOutputPanel() {
        summaryArea.setEditable(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        summaryArea.setText("运行扫描或迁移后，这里会显示结构化报告摘要。");

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(false);

        JScrollPane summaryScroll = new JScrollPane(summaryArea);
        summaryScroll.setBorder(BorderFactory.createTitledBorder("结果摘要"));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("CLI 实时日志"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, summaryScroll, logScroll);
        splitPane.setResizeWeight(0.38);
        splitPane.setDividerLocation(220);
        return splitPane;
    }

    private JPanel createActionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(statusLabel);
        left.add(openReportButton);
        left.add(openWorkspaceButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(scanButton);
        right.add(dryRunButton);
        right.add(migrateButton);
        right.add(cancelButton);
        cancelButton.setEnabled(false);
        panel.add(left, BorderLayout.WEST);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    private void bindActions() {
        scanButton.addActionListener(event -> start(GuiOperation.SCAN));
        dryRunButton.addActionListener(event -> start(GuiOperation.DRY_RUN));
        migrateButton.addActionListener(event -> start(GuiOperation.MIGRATE));
        cancelButton.addActionListener(event -> cancel());
        openReportButton.addActionListener(event -> openLatestReport());
        openWorkspaceButton.addActionListener(event -> openPath(selectedReportDir()));
        databaseValidationBox.addActionListener(event -> updateDatabaseFields());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }
        });
    }

    private void start(GuiOperation operation) {
        if (processRunner.isRunning()) {
            showError("已有任务正在运行。");
            return;
        }
        try {
            GuiRunConfiguration configuration = configuration();
            validateLocalPaths(operation, configuration);
            CliInvocation invocation = commandBuilder.build(operation, configuration);
            if (operation == GuiOperation.MIGRATE && !confirmMigration(configuration)) {
                return;
            }
            lastOperation = operation;
            lastReportDir = invocation.reportDir();
            logArea.setText("");
            summaryArea.setText(operation == GuiOperation.SCAN
                    ? "正在扫描项目……"
                    : "正在等待迁移摘要……");
            appendLog("启动 CLI，参数：" + invocation.arguments());
            setRunning(true);
            if (operation != GuiOperation.SCAN) {
                summaryTimer.start();
            }
            processRunner.run(invocation, this::appendLog).whenComplete((result, error) ->
                    SwingUtilities.invokeLater(() -> finish(result, error))
            );
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void finish(CliRunResult result, Throwable error) {
        summaryTimer.stop();
        setRunning(false);
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            appendLog("GUI 调用失败：" + cause.getMessage());
            statusLabel.setText("启动或执行失败");
            refreshSummary(true);
            return;
        }
        if (result.cancelled()) {
            appendLog("任务已由用户取消。数据库端已开始的语句可能仍需管理员确认状态。");
            statusLabel.setText("已取消");
        } else {
            String description = ExitCodeDescriptions.describe(result.exitCode());
            appendLog("CLI 结束：" + description);
            statusLabel.setText("完成（退出码 " + result.exitCode() + "）");
        }
        refreshSummary(true);
    }

    private void cancel() {
        if (!processRunner.isRunning()) {
            return;
        }
        appendLog("正在取消 CLI 及其子进程……");
        statusLabel.setText("正在取消");
        processRunner.cancel();
    }

    private GuiRunConfiguration configuration() {
        String selectedLength = (String) targetLengthSemanticsBox.getSelectedItem();
        TargetLengthSemantics semantics = "自动探测".equals(selectedLength)
                ? null
                : TargetLengthSemantics.valueOf(selectedLength);
        return new GuiRunConfiguration(
                pathValue(projectField),
                pathValue(reportDirField),
                appModuleField.getText(),
                pathValue(mapperDirField),
                pathValue(rewriteConfigField),
                pathValue(validationConfigField),
                schemaField.getText(),
                pathValue(sqlRootField),
                pathValue(sqlRootOutField),
                sqlScriptsOnlyBox.isSelected(),
                systemSchemaField.getText(),
                semantics,
                dmDriverField.getText(),
                generateValidationTestBox.isSelected(),
                databaseValidationBox.isSelected(),
                jdbcUrlField.getText(),
                usernameField.getText(),
                new String(passwordField.getPassword())
        );
    }

    private void validateLocalPaths(GuiOperation operation, GuiRunConfiguration configuration) {
        if (configuration.project() == null || !Files.isDirectory(configuration.project())) {
            throw new IllegalArgumentException("项目根目录不存在或不是目录。");
        }
        if (operation != GuiOperation.SCAN
                && configuration.sqlRoot() != null
                && !Files.isDirectory(configuration.sqlRoot())) {
            throw new IllegalArgumentException("MySQL SQL 源目录不存在或不是目录。");
        }
    }

    private boolean confirmMigration(GuiRunConfiguration configuration) {
        String message = "正式迁移会修改项目文件，但原始 mapper XML 不会被覆盖。";
        if (configuration.databaseValidation()) {
            message += "\n\n已启用数据库验证：这可能真实修改共享测试库，且 SQL 脚本不会自动回滚。";
        }
        message += "\n\n确认继续吗？";
        return JOptionPane.showConfirmDialog(
                this,
                message,
                "确认执行迁移",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION;
    }

    private void refreshSummary(boolean finalRefresh) {
        Path reportDir = lastReportDir;
        try {
            if (lastOperation == GuiOperation.SCAN) {
                Path scanJson = reportDir.resolve(ReportWriter.SCAN_REPORT_JSON);
                if (Files.isRegularFile(scanJson)) {
                    ProjectScanResult scan = objectMapper.readValue(scanJson.toFile(), ProjectScanResult.class);
                    summaryArea.setText(formatScan(scan));
                }
                return;
            }
            Path summaryJson = reportDir.resolve(ReportWriter.SUMMARY_JSON);
            if (Files.isRegularFile(summaryJson)) {
                DmAdapterSummary summary = reportReader.readSummary(reportDir);
                summaryArea.setText(formatSummary(summary));
            } else if (finalRefresh) {
                summaryArea.setText("本次运行未生成项目摘要，请查看 CLI 日志。");
            }
        } catch (Exception e) {
            if (finalRefresh) {
                appendLog("读取结果摘要失败：" + e.getMessage());
            }
        }
    }

    private String formatScan(ProjectScanResult scan) {
        StringBuilder result = new StringBuilder();
        result.append("Maven 项目：").append(yesNo(scan.mavenProject())).append('\n');
        result.append("Spring Boot 项目：").append(yesNo(scan.springBootProject())).append('\n');
        result.append("MyBatis XML 项目：").append(yesNo(scan.myBatisProject())).append('\n');
        result.append("已配置达梦 JDBC：").append(yesNo(scan.hasDmJdbcDriver())).append('\n');
        result.append("Mapper XML 数量：").append(scan.mapperXmlFiles().size()).append('\n');
        if (!scan.warnings().isEmpty()) {
            result.append("\n警告：\n");
            scan.warnings().forEach(warning -> result.append("- ").append(warning).append('\n'));
        }
        return result.toString();
    }

    private String formatSummary(DmAdapterSummary summary) {
        StringBuilder result = new StringBuilder();
        result.append("总体状态：").append(summary.overallStatus()).append('\n');
        result.append("项目：").append(summary.projectRoot()).append('\n');
        result.append("dry-run：").append(summary.dryRun()).append('\n');
        result.append("数据库执行模式：").append(summary.executionMode()).append("\n\n");
        result.append("阶段：\n");
        for (Map.Entry<String, SummaryStage> entry : summary.stages().entrySet()) {
            SummaryStage stage = entry.getValue();
            result.append("- ").append(stage.name()).append("：").append(stage.status());
            if (!stage.message().isBlank()) {
                result.append(" — ").append(stage.message());
            }
            if (!stage.counts().isEmpty()) {
                result.append(" ").append(stage.counts());
            }
            result.append('\n');
        }
        if (!summary.manualReview().isEmpty()) {
            result.append("\n人工确认：").append(summary.manualReview()).append('\n');
        }
        if (!summary.topIssues().isEmpty()) {
            result.append("\n主要问题：\n");
            for (SummaryIssue issue : summary.topIssues()) {
                result.append("- ").append(issue.severity()).append(' ')
                        .append(issue.category()).append('/').append(issue.pattern())
                        .append("：根因 ").append(issue.rootCount())
                        .append("，级联阻塞 ").append(issue.blockedCount()).append('\n');
            }
        }
        if (!summary.nextActions().isEmpty()) {
            result.append("\n下一步：\n");
            summary.nextActions().forEach(action -> result.append("- ").append(action).append('\n'));
        }
        return result.toString();
    }

    private void openLatestReport() {
        Path reportDir = lastOperation == null ? selectedReportDir() : lastReportDir;
        Path report;
        if (lastOperation == GuiOperation.SCAN) {
            report = reportDir.resolve(ReportWriter.SCAN_REPORT_MARKDOWN);
        } else if (Files.isRegularFile(reportDir.resolve(ReportWriter.SUMMARY_MARKDOWN))) {
            report = reportDir.resolve(ReportWriter.SUMMARY_MARKDOWN);
        } else {
            report = reportDir.resolve(ReportWriter.MIGRATION_REPORT_MARKDOWN);
        }
        openPath(report);
    }

    private void openPath(Path path) {
        if (path == null || !Files.exists(path)) {
            showError("路径不存在：" + path);
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("当前桌面环境不支持打开文件。");
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            showError("无法打开路径：" + e.getMessage());
        }
    }

    private Path selectedReportDir() {
        Path configured = pathValue(reportDirField);
        return configured == null ? CliCommandBuilder.defaultReportDir() : configured;
    }

    private void updateDatabaseFields() {
        boolean enabled = databaseValidationBox.isSelected();
        jdbcUrlField.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
    }

    private void setRunning(boolean running) {
        scanButton.setEnabled(!running);
        dryRunButton.setEnabled(!running);
        migrateButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        openReportButton.setEnabled(!running);
        if (running) {
            statusLabel.setText("运行中");
        }
    }

    private void closeWindow() {
        if (processRunner.isRunning()) {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    "CLI 任务仍在运行。关闭窗口会取消任务，确认继续吗？",
                    "确认退出",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        summaryTimer.stop();
        processRunner.close();
        dispose();
    }

    private void appendLog(String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendLog(message));
            return;
        }
        logArea.append((message == null ? "" : message) + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints filler = constraints(99, 0);
        filler.weighty = 1;
        panel.add(new JPanel(), filler);
        return panel;
    }

    private void addTextRow(JPanel panel, int row, String label, JTextField field, String tooltip) {
        addComponentRow(panel, row, label, field, tooltip);
    }

    private void addComponentRow(JPanel panel, int row, String label, java.awt.Component component, String tooltip) {
        JLabel rowLabel = new JLabel(label);
        rowLabel.setToolTipText(tooltip);
        component.setPreferredSize(new Dimension(680, 28));
        if (component instanceof javax.swing.JComponent swingComponent) {
            swingComponent.setToolTipText(tooltip);
        }
        GridBagConstraints labelConstraints = constraints(row, 0);
        labelConstraints.weightx = 0;
        labelConstraints.fill = GridBagConstraints.NONE;
        panel.add(rowLabel, labelConstraints);
        GridBagConstraints fieldConstraints = constraints(row, 1);
        fieldConstraints.gridwidth = 2;
        panel.add(component, fieldConstraints);
    }

    private void addPathRow(
            JPanel panel,
            int row,
            String label,
            JTextField field,
            boolean directory,
            java.util.function.Consumer<Path> selectionConsumer
    ) {
        JLabel rowLabel = new JLabel(label);
        JButton browse = new JButton("浏览…");
        browse.addActionListener(event -> choosePath(field, directory, selectionConsumer));
        GridBagConstraints labelConstraints = constraints(row, 0);
        labelConstraints.weightx = 0;
        labelConstraints.fill = GridBagConstraints.NONE;
        panel.add(rowLabel, labelConstraints);
        panel.add(field, constraints(row, 1));
        GridBagConstraints buttonConstraints = constraints(row, 2);
        buttonConstraints.weightx = 0;
        buttonConstraints.fill = GridBagConstraints.NONE;
        panel.add(browse, buttonConstraints);
    }

    private void addFullWidth(JPanel panel, int row, java.awt.Component component) {
        GridBagConstraints constraints = constraints(row, 0);
        constraints.gridwidth = 3;
        panel.add(component, constraints);
    }

    private GridBagConstraints constraints(int row, int column) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.weightx = column == 1 ? 1 : 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 6, 4, 6);
        return constraints;
    }

    private void choosePath(JTextField field, boolean directory, java.util.function.Consumer<Path> selectionConsumer) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        Path current = pathValue(field);
        if (current != null) {
            Path initialDirectory = Files.isDirectory(current) ? current : current.getParent();
            if (initialDirectory != null) {
                chooser.setCurrentDirectory(initialDirectory.toFile());
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            field.setText(selected.toString());
            if (selectionConsumer != null) {
                selectionConsumer.accept(selected);
            }
        }
    }

    private Path pathValue(JTextField field) {
        String value = field.getText();
        return value == null || value.isBlank() ? null : Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "dm-adapter", JOptionPane.ERROR_MESSAGE);
    }
}
