# Jenkins Windows Freestyle batch：Codex 实施交接文档

本文用于交给负责 Jenkins 配置的 Codex 或运维人员直接实施。目标是在一台 Windows Jenkins Agent 上，
由一个 Freestyle Job 每日顺序处理多个业务 Git 仓库：构建最新版 `dm-adapter`，从每个业务仓库远端分支的
最新提交执行离线 MySQL → 达梦转换，按固定提交信息提交并推送。某个项目失败后继续处理其他项目，最后只要
有一个项目失败，整个 Job 就失败。

## 1. 已确定的运行约束

- Job 类型：Windows Freestyle Project。
- 调度：每天一次，Build periodically 填写 `H 2 * * *`。
- 单 Job、多项目，项目清单由 Jenkins 托管的 JSON 文件提供。
- 项目串行执行，不在业务仓库当前检出目录上转换。
- 每次处理前由 CLI `fetch` 远端分支，并基于远端 HEAD 创建临时 detached worktree。
- 不执行 `mvn test`，Job 运行期只用 `mvn -q -DskipTests package` 构建工具。
- batch 不连接达梦，不执行数据库验证、不读取达梦元数据、不生成验证测试。
- 使用 Job 内固定的一条 Git commit message；没有变更时不产生空提交。
- 发现任何人工确认项时，该项目失败且不 commit、不 push。
- 转换期间远端分支前进时丢弃临时 worktree，从新 HEAD 完整重跑；最多两次。
- 不执行 merge、rebase 或 force push。
- 同一“仓库 + 项目子目录 + 远端分支”已有 batch 时返回 `SKIPPED_LOCKED`，退出码为 `0`。
- 原始仓库缓存只用于 Git 元数据和凭据上下文；batch 推送后不会把它的本地分支 fast-forward。
- 报告写到业务仓库外部并由 Jenkins 归档。

## 2. Windows Agent 前置条件

在绑定该 Job 的 Windows Agent 上准备：

1. Java 17，`java -version` 可由运行 Jenkins Agent 服务的账户执行。
2. Maven 3.9.x，`mvn -version` 可执行。
3. Git for Windows，`git --version` 可执行。
4. Windows PowerShell 5.1 或 PowerShell 7。
5. Jenkins Agent 服务账户对 `%WORKSPACE%` 有读写权限。
6. Git 服务机器账户具有全部业务仓库目标分支的 clone 和 push 权限。
7. 机器账户的 Git SSH host key 已写入该服务账户的 `known_hosts`；不要关闭 host key 校验。

推荐使用 SSH 仓库 URL，并在 Job 中使用 Jenkins **SSH Agent** build wrapper 注入私钥。若使用 HTTPS，
应由运行 Agent 的服务账户预先配置 Git Credential Manager；禁止把 token 拼进 JSON URL、命令参数或控制台日志。

batch 会设置 `GIT_TERMINAL_PROMPT=0` 和 `GCM_INTERACTIVE=Never`。凭据缺失时项目应快速失败，不能等待交互输入。

## 3. Freestyle Job 配置

### 3.1 基础设置

1. 新建 Freestyle Project，例如 `dm-adapter-daily-batch`。
2. 勾选 **Restrict where this project can be run**，指定准备好的 Windows Agent label。
3. 勾选 **Do not allow concurrent builds**。CLI 自身仍有文件锁，作为重复 Job 或误配置并发时的第二层保护。
4. Build periodically 填写：

   ```text
   H 2 * * *
   ```

### 3.2 dm-adapter 工具源码

在 Source Code Management 中配置本仓库及需要构建的分支。通过 Git Plugin 的 Additional Behaviours
将源码检出到子目录：

```text
tool-source
```

构建脚本将使用：

```text
%WORKSPACE%\tool-source\dm-adapter-cli\target\dm-adapter-cli-0.1.0-SNAPSHOT.jar
```

### 3.3 Jenkins 托管项目清单

安装并使用 **Config File Provider**。新建一个 Custom file，例如 ID 为 `dm-adapter-batch-projects`，内容参考
下一节。然后在 Job 的 Build Environment 中选择 **Provide Configuration files**：

- File：`dm-adapter-batch-projects`
- Target：`batch-projects.json`

最终脚本从 `%WORKSPACE%\batch-projects.json` 读取。项目清单不是凭据文件，不能在其中放用户名、密码或 token。

### 3.4 Git 凭据

SSH 方案下，在 Build Environment 勾选 **SSH Agent** 并选择机器账户私钥。所有 JSON `url` 使用同一凭据
可访问的 SSH URL。若各仓库权限不同，可给机器账户统一授予最小必要权限，或拆成不同 Job；不要在脚本中切换明文凭据。

### 3.5 构建步骤与归档

添加一个 **Execute Windows PowerShell script** 构建步骤，粘贴第 5 节完整脚本，只修改脚本开头的固定
commit message、Git 作者名和邮箱。

添加 Post-build Action **Archive the artifacts**：

```text
batch-artifacts/${BUILD_NUMBER}/**/*.md,batch-artifacts/${BUILD_NUMBER}/**/*.json
```

建议勾选“即使构建失败也归档”（不同 Jenkins/插件版本名称可能略有差异）。若该版本的 Archive artifacts
只在成功后执行，改用 Flexible Publish 或等价插件，让失败项目的报告也被保存。

## 4. 项目 JSON 格式

最小清单：

```json
[
  {
    "name": "newsee-contract-rest",
    "url": "git@git.example.com:NEAP/newsee-contract-rest.git",
    "branch": "main",
    "projectSubdir": "."
  },
  {
    "name": "newsee-bill-rest",
    "url": "git@git.example.com:NEAP/newsee-bill-rest.git",
    "branch": "master.ARM",
    "projectSubdir": "newsee-bill-rest"
  }
]
```

带 SQL 脚本和可选路径的完整示例：

```json
[
  {
    "name": "newsee-system-rest",
    "url": "git@git.example.com:NEAP/newsee-system-rest.git",
    "branch": "main",
    "projectSubdir": ".",
    "mapperDir": "src/main/resources/mapper-dm",
    "rewriteConfig": "dm/sql-rewrite.yml",
    "sqlRoot": "sql/v2",
    "sqlRootOut": "sql/v2-dm",
    "schema": "newsee-system",
    "systemSchema": "newsee-system",
    "targetLengthSemantics": "CHAR",
    "preserveSql": [
      "00000000.sql",
      "manual/vendor-script.sql"
    ]
  }
]
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | Jenkins 缓存和报告目录名，只允许字母、数字、点、下划线和横线。 |
| `url` | 是 | Git 仓库 SSH/HTTPS URL，不得含明文凭据。 |
| `branch` | 是 | 要 fetch 和 push 的远端分支。 |
| `projectSubdir` | 是 | Maven 项目相对仓库根目录；根项目填 `.`。 |
| `mapperDir` | 否 | Mapper 达梦输出目录，相对项目目录且必须仍在 Git 仓库内。 |
| `rewriteConfig` | 否 | SQL 重写配置，相对项目目录且必须在 Git 仓库内；需要长期维护规则时建议纳入版本控制。 |
| `sqlRoot` / `sqlRootOut` | 同时出现 | MySQL 脚本源目录和达梦脚本输出目录，均相对项目目录且在仓库内。 |
| `schema` | 否 | 仅在配置 SQL 脚本迁移时使用。batch 不据此连接数据库。 |
| `systemSchema` | 否 | 文件名含独立 `system` token 的脚本目标 schema。 |
| `targetLengthSemantics` | SQL 脚本时必填 | 只能为 `CHAR` 或 `BYTE`；替代 batch 禁用的数据库能力探测。 |
| `preserveSql` | 否 | 相对 `sqlRoot` 的保留脚本路径数组。顶层 `00000000.sql` 本就会保留。 |

禁止在清单中使用 `--app-module`、`--config`、`--generate-validation-test` 或 `--dry-run`。`--report-dir`
由 Jenkins 脚本统一放在业务仓库外部。

## 5. 可直接使用的 PowerShell 编排脚本

```powershell
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Job 固定值：上线前只在这里修改一次。
$CommitMessage = '定时同步 MySQL 到达梦适配'
$GitAuthorName = 'dm-adapter Jenkins Bot'
$GitAuthorEmail = 'dm-adapter-jenkins@example.com'

$Workspace = [IO.Path]::GetFullPath($env:WORKSPACE)
$ToolSource = Join-Path $Workspace 'tool-source'
$ManifestPath = Join-Path $Workspace 'batch-projects.json'
$RepositoriesRoot = Join-Path $Workspace 'repositories'
$BuildArtifactRoot = Join-Path (Join-Path $Workspace 'batch-artifacts') $env:BUILD_NUMBER
$CliJar = Join-Path $ToolSource 'dm-adapter-cli\target\dm-adapter-cli-0.1.0-SNAPSHOT.jar'

$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'Never'
$env:GIT_AUTHOR_NAME = $GitAuthorName
$env:GIT_AUTHOR_EMAIL = $GitAuthorEmail
$env:GIT_COMMITTER_NAME = $GitAuthorName
$env:GIT_COMMITTER_EMAIL = $GitAuthorEmail

# batch 代码本身会强制禁用验证；这里再清理 Job 环境，避免其他命令误用连接信息。
$env:DM_SQL_VALIDATION = 'false'
Remove-Item Env:DM_JDBC_URL -ErrorAction SilentlyContinue
Remove-Item Env:DM_DB_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:DM_DB_PASSWORD -ErrorAction SilentlyContinue

function Get-OptionalProperty {
    param([object]$Object, [string]$Name)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Assert-LastExitCode {
    param([string]$Operation)
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Jenkins managed project manifest not found: $ManifestPath"
}

New-Item -ItemType Directory -Force -Path $RepositoriesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $BuildArtifactRoot | Out-Null

Push-Location $ToolSource
try {
    & mvn -q -DskipTests package
    Assert-LastExitCode 'dm-adapter package'
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $CliJar -PathType Leaf)) {
    throw "CLI jar not found after package: $CliJar"
}

$projects = @(Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
$results = [System.Collections.Generic.List[object]]::new()
$failures = [System.Collections.Generic.List[object]]::new()

foreach ($project in $projects) {
    $name = [string]$project.name
    $url = [string]$project.url
    $branch = [string]$project.branch
    $projectSubdir = [string]$project.projectSubdir
    $exitCode = 1
    $status = 'FAILED'
    $message = ''

    try {
        if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
            throw "Invalid project name: $name"
        }
        if ([string]::IsNullOrWhiteSpace($url) -or
            [string]::IsNullOrWhiteSpace($branch) -or
            [string]::IsNullOrWhiteSpace($projectSubdir)) {
            throw "Project $name is missing url, branch, or projectSubdir"
        }

        $repositoryDir = [IO.Path]::GetFullPath((Join-Path $RepositoriesRoot $name))
        $reportDir = [IO.Path]::GetFullPath((Join-Path $BuildArtifactRoot $name))
        New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

        if (-not (Test-Path -LiteralPath (Join-Path $repositoryDir '.git'))) {
            & git clone --branch $branch --single-branch -- $url $repositoryDir
            Assert-LastExitCode "git clone $name"
        } else {
            & git -C $repositoryDir remote set-url origin $url
            Assert-LastExitCode "git remote set-url $name"
        }

        $projectRoot = [IO.Path]::GetFullPath((Join-Path $repositoryDir $projectSubdir))
        $repositoryPrefix = $repositoryDir.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if ($projectRoot -ne $repositoryDir -and
            -not $projectRoot.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "projectSubdir escapes repository: $projectSubdir"
        }

        $cliArgs = [System.Collections.Generic.List[string]]::new()
        @(
            '-jar', $CliJar, 'migrate',
            '--batch',
            '--project', $projectRoot,
            '--git-remote', 'origin',
            '--git-branch', $branch,
            '--git-commit-message', $CommitMessage,
            '--report-dir', $reportDir
        ) | ForEach-Object { $cliArgs.Add([string]$_) }

        $optionalArguments = @{
            mapperDir = '--mapper-dir'
            rewriteConfig = '--rewrite-config'
            sqlRoot = '--sql-root'
            sqlRootOut = '--sql-root-out'
            schema = '--schema'
            systemSchema = '--system-schema'
            targetLengthSemantics = '--target-length-semantics'
        }
        foreach ($entry in $optionalArguments.GetEnumerator()) {
            $value = Get-OptionalProperty $project $entry.Key
            if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
                $cliArgs.Add($entry.Value)
                $cliArgs.Add([string]$value)
            }
        }
        $preserveSql = Get-OptionalProperty $project 'preserveSql'
        if ($null -ne $preserveSql) {
            foreach ($path in @($preserveSql)) {
                $cliArgs.Add('--preserve-sql')
                $cliArgs.Add([string]$path)
            }
        }

        Write-Host "===== dm-adapter batch: $name ($branch) ====="
        & java @cliArgs
        $exitCode = $LASTEXITCODE

        $batchJson = Join-Path $reportDir 'dm-adapter-batch-report.json'
        if (Test-Path -LiteralPath $batchJson -PathType Leaf) {
            $batchReport = Get-Content -LiteralPath $batchJson -Raw -Encoding UTF8 | ConvertFrom-Json
            $status = [string]$batchReport.status
            $message = [string]$batchReport.message
        } else {
            throw "CLI did not produce dm-adapter-batch-report.json (exit code $exitCode)"
        }
        if ($exitCode -ne 0) {
            throw "dm-adapter batch exited with $exitCode ($status): $message"
        }
    } catch {
        $message = $_.Exception.Message
        $failure = [pscustomobject]@{
            name = $name
            exitCode = $exitCode
            status = $status
            message = $message
        }
        $failures.Add($failure)
        Write-Error -ErrorAction Continue "Project $name failed: $message"
    } finally {
        $results.Add([pscustomobject]@{
            name = $name
            exitCode = $exitCode
            status = $status
            message = $message
        })
    }
}

$aggregate = [pscustomobject]@{
    schemaVersion = 1
    buildNumber = $env:BUILD_NUMBER
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    projectCount = $results.Count
    failureCount = $failures.Count
    results = $results
}
$aggregateJson = Join-Path $BuildArtifactRoot 'dm-adapter-batch-aggregate.json'
$aggregate | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $aggregateJson -Encoding UTF8

$aggregateMarkdown = Join-Path $BuildArtifactRoot 'dm-adapter-batch-aggregate.md'
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# dm-adapter Jenkins Batch Summary')
$lines.Add('')
$lines.Add("- Build: ``$($env:BUILD_NUMBER)``")
$lines.Add("- Projects: ``$($results.Count)``")
$lines.Add("- Failures: ``$($failures.Count)``")
$lines.Add('')
$lines.Add('| Project | Status | Exit | Message |')
$lines.Add('| --- | --- | ---: | --- |')
foreach ($result in $results) {
    $safeMessage = ([string]$result.message).Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
    $lines.Add("| $($result.name) | $($result.status) | $($result.exitCode) | $safeMessage |")
}
$lines | Set-Content -LiteralPath $aggregateMarkdown -Encoding UTF8

if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) project(s) failed. See archived batch reports."
    exit 1
}
Write-Host 'All dm-adapter batch projects completed successfully.'
exit 0
```

## 6. batch 状态和退出码

每个项目的 `dm-adapter-batch-report.md/json` 至少给出远端分支、基础提交、推送提交、尝试次数、变更文件和
失败阶段。状态含义：

| 状态 | 退出码 | Job 聚合行为 |
| --- | ---: | --- |
| `SUCCESS` | 0 | 成功，已 commit 并 push。 |
| `NO_CHANGES` | 0 | 成功，无变更且未创建提交。 |
| `SKIPPED_LOCKED` | 0 | 安全跳过；通常应检查为何有重复任务。 |
| `FAILED` / 参数或项目错误 | 1 或 2 | 记录失败，继续下一个项目。 |
| `FAILED` / `manual-review` | 3 | 记录失败，不提交；人工查看详细报告。 |
| `FAILED` / Git 基础设施 | 5 | 记录失败；检查凭据、网络、分支保护或远端并发更新。 |

batch 不应产生退出码 `4`，因为它强制关闭数据库验证。若 Jenkins Job 中出现数据库连接日志，应立即停止
Job 并确认运行的是包含 batch 功能的最新版 jar。

## 7. 上线验收清单

首次启用时先只放一个非关键测试仓库，并手工触发 Job，逐项确认：

1. 控制台显示工具执行了 `mvn -q -DskipTests package`。
2. 项目远端产生固定 message 的提交，提交只包含预期 POM、`mapper-dm`、Java 参数修复、重写配置或 SQL 输出。
3. `%WORKSPACE%\repositories\<name>` 的本地 HEAD 和工作文件没有被 batch 推进或改写。
4. 第二次立即执行得到 `NO_CHANGES`，远端没有空提交。
5. 在测试 Mapper 中放入无法安全转换的 SQL 后得到退出码 `3`，远端没有新增提交。
6. Jenkins 在项目失败后继续运行后续项目，并在结尾将整个构建标记为失败。
7. 成功和失败构建均可下载每个项目的 Markdown/JSON 报告和聚合摘要。
8. 控制台、JSON 清单、报告和 Git remote URL 中均没有密码或 token。
9. 定时表达式为 `H 2 * * *`，并发构建已禁用。

验收完成后再逐步增加业务项目。长期运行中优先根据 `failureStage` 分流：`manual-review` 交给开发确认 SQL，
`fetch`/`push` 交给 Git/网络维护人员，`migration` 交给 dm-adapter 维护人员。不要为让定时任务“变绿”而开启
force push、自动 merge/rebase，或跳过人工确认门禁。
