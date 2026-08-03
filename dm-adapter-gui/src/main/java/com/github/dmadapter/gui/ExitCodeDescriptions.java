package com.github.dmadapter.gui;

final class ExitCodeDescriptions {
    private ExitCodeDescriptions() {
    }

    static String describe(int exitCode) {
        return switch (exitCode) {
            case 0 -> "执行成功，或本次未请求数据库验证。";
            case 1 -> "工具内部错误，请查看执行日志。";
            case 2 -> "项目路径无效，或项目不是 Maven 项目。";
            case 3 -> "存在 SQL 根因失败或仍有人工确认项。";
            case 4 -> "验证清单、连接、schema、数据库能力、环境或时限存在问题。";
            default -> "CLI 进程异常结束，退出码：" + exitCode;
        };
    }
}
