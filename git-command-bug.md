# Git 命令执行 Bug 报告

## 问题描述
在当前工具环境下，`git` 命令的某些子命令无法正常执行，即使项目本身的 `.git` 目录结构完整且配置正确。

## 受影响的项目
- `agent-platform-runner`
- `mcp-server-mock`（新建项目）

## 复现步骤
1. 尝试执行 `git -C Project/agent-platform-runner status`
2. 尝试执行 `git -C Project/mcp-server-mock init`
3. 尝试执行 `git clone https://github.com/linlay/mcp-server-mock.git Project/mcp-server-mock`

## 错误信息
```
Path not allowed outside authorized directories: <subcommand>
```

## 已验证正常的 Git 操作
- `cat Project/agent-platform-runner/.git/config` - 可以读取 git 配置文件
- `ls Project/agent-platform-runner/.git/` - 可以列出 .git 目录内容

## 受限制的 Git 子命令
- `git init`
- `git clone`
- `git pull`
- `git status`
- `git remote -v`

## 临时解决方案
在本地终端直接执行相关 git 命令，绕过工具限制：
```bash
cd Project/agent-platform-runner
git status
git remote -v

cd Project
git clone https://github.com/linlay/mcp-server-mock.git
```

## 报告日期
$(date +%Y-%m-%d)

## 报告人
linlay
