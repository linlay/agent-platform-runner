# schedules 示例说明

## 用途

该目录存放示例计划任务定义，文件名即 `scheduleId`。

## 命名规范

- 文件后缀：`.yml`
- 文件命名：`<schedule-id>.yml`
- `schedule-id` 建议使用小写字母/数字/中划线/下划线

## 文件字段

- 头部前两行固定为：
  - 第 1 行：`name: ...`
  - 第 2 行：`description: ...`
- `description` 必须为单行，不支持 `|` / `>` 多行写法
- 必填：`name`、`description`、`cron`、`query`
- 目标：`agentKey` 或 `teamId` 至少一个
- 可选：`enabled`、`zoneId`、`params`

## 附带示例

- `demo_daily_summary.yml`：每日摘要示例（默认禁用）。
- `demo_viewport_weather_minutely.yml`：每分钟触发一次 `demoViewport`，随机选择一个城市查询天气并尽量输出 viewport。

## 最小示例

```yaml
name: 每日摘要
description: 每天上午九点触发一次今日待办摘要
cron: "0 0 9 * * *"
agentKey: demoModePlain
query: 请输出今天待办摘要
```

## 与外层目录关系

- 源：`example/schedules/`
- 目标：项目根目录 `schedules/`
- 策略：覆盖同名文件，保留额外文件
