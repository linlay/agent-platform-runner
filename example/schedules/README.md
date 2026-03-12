# schedules 示例说明

## 用途

该目录存放示例计划任务定义，文件名即 `scheduleId`。

## 命名规范

- 文件后缀：`.json`
- 文件命名：`<schedule-id>.json`
- `schedule-id` 建议使用小写字母/数字/中划线/下划线

## 文件字段

- 必填：`cron`、`query`
- 目标：`agentKey` 或 `teamId` 至少一个
- 可选：`enabled`、`name`、`zoneId`、`params`

## 附带示例

- `demo_daily_summary.json`：每日摘要示例（默认禁用）。
- `demo_viewport_weather_minutely.json`：每分钟触发一次 `demoViewport`，随机选择一个城市查询天气并尽量输出 viewport。

## 最小示例

```json
{
  "cron": "0 0 9 * * *",
  "agentKey": "demoModePlain",
  "query": "请输出今天待办摘要"
}
```

## 与外层目录关系

- 源：`example/schedules/`
- 目标：项目根目录 `schedules/`
- 策略：覆盖同名文件，保留额外文件
