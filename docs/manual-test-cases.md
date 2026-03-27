# 手动测试用例 (curl)

## 环境变量

```bash
# Docker Compose 默认端口（HOST_PORT=11949）
BASE_URL="http://localhost:11949"
# 若本地直接运行 mvn spring-boot:run，可切换为 http://localhost:8080
# BASE_URL="http://localhost:8080"
ACCESS_TOKEN=""
```

## 会话接口测试

```bash
curl -N -X GET "$BASE_URL/api/chats" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chats?lastRunId=mtoewf3u" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chats?agentKey=demoModePlain&lastRunId=mtoewf3u" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X POST "$BASE_URL/api/read" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656"}'
```

```bash
curl -N -X GET "$BASE_URL/api/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json"
```

```bash
curl -N -X GET "$BASE_URL/api/chat?chatId=d0e5b9ab-af21-4e3b-8e1a-a977dc6d5656&includeRawMessages=true" \
  -H "Content-Type: application/json"
```

## Query 回归测试

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"元素碳的简介，200字","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"","message":"下一个元素的简介","agentKey":"demoModePlain"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个微服务网关的落地方案，100字内","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我一个机房搬迁风险分析摘要，300字左右","agentKey":"demoModeThinking"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"我周日要搬迁机房到上海，检查下服务器(mac)的硬盘和CPU，然后决定下搬迁条件","agentKey":"demoModeReact"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"规划上海机房明天搬迁的实施计划，重点关注下天气","agentKey":"demoModePlanExecute"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"查上海明天天气","agentKey":"demoViewport"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"切换到深色主题","agentKey":"demoAction"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"先检查 /skills/docx 和 pandoc/soffice 是否可用，再总结 /workspace/report.docx 的内容","agentKey":"dailyOfficeAssistant"}'
```

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"【确认是否有敏感信息】本项目突破传统竖井式系统建设模式，基于1+1+3+N架构（1个企业级数据库、1套OneID客户主数据、3类客群CRM系统整合优化、N个展业数字化应用），打造了覆盖展业全生命周期、贯通公司全客群管理的OneLink分支一体化数智展业服务平台。在数据基础层面，本项目首创企业级数据库及OneID客户主数据运作体系，实现公司全域客户及业务数据物理入湖，并通过事前注册、事中应用管理、事后可分析的机制，实现个人、企业、机构三类客群千万级客户的统一识别与关联。","agentKey":"demoModePlainTooling"}'
```

## 确认对话框（Human-in-the-Loop）

`confirm_dialog` 是前端工具，LLM 调用后 SSE 流会暂停等待用户提交，需要两个终端配合测试。

终端 1：发起 query（SSE 流会在 LLM 调用 `confirm_dialog` 时暂停）

```bash
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我规划周六的旅游，给我几个目的地选项让我选","agentKey":"demoConfirmDialog"}'
```

观察 SSE 输出，当看到 `toolName=confirm_dialog` 且事件携带 `toolType`、`viewportKey`、`toolTimeout` 后，记录事件中的 `runId` 和 `toolId`。

终端 2：提交用户选择

```bash
curl -X POST "$BASE_URL/api/submit" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>",
    "toolId": "<TOOL_ID>",
    "params": {
      "selectedOption": "杭州西湖一日游",
      "selectedIndex": 1,
      "freeText": "",
      "isCustom": false
    }
  }'
```

若未命中等待中的 `runId + toolId`，接口仍返回 HTTP 200，但 `accepted=false` / `status=unmatched`。

submit 响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "accepted": true,
    "status": "accepted",
    "runId": "<RUN_ID>",
    "toolId": "<TOOL_ID>",
    "detail": "Frontend submit accepted for runId=<RUN_ID>, toolId=<TOOL_ID>"
  }
}
```

## 运行中引导与中断

运行中引导：

```bash
curl -X POST "$BASE_URL/api/steer" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>",
    "message": "优先给出可执行结论，再补充风险"
  }'
```

运行中断：

```bash
curl -X POST "$BASE_URL/api/interrupt" \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "<RUN_ID>"
  }'
```

## 文件展示（Data Viewer）

```bash
# 浏览器直接展示图片
curl "$BASE_URL/api/resource?file=sample_diagram.png" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_diagram.png
```

```bash
# 浏览器直接展示图片（file 使用编码后的 /data 路径）
curl "$BASE_URL/api/resource?file=%2Fdata%2Fsample_photo.jpg" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg
```

```bash
# 强制下载图片（?download=true）
curl "$BASE_URL/api/resource?file=%2Fdata%2Fsample_photo.jpg&download=true" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_photo.jpg
```

```bash
# 下载 CSV 数据表
curl "$BASE_URL/api/resource?file=sample_data.csv" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output sample_data.csv
```

```bash
# 1) 一步上传本地文件
curl -X POST "$BASE_URL/api/upload" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F requestId=req-upload-001 \
  -F chatId=123e4567-e89b-12d3-a456-426614174000 \
  -F "file=@requirements.md;type=text/markdown"
```

```bash
# 2) 上传成功后可直接通过 upload.url 下载
curl "$BASE_URL/api/resource?file=<ENCODED_FILE_PATH>" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --output requirements.md
```

```bash
# 与文件展示智能体对话
curl -N -X POST "$BASE_URL/api/query" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"展示所有可用的图片","agentKey":"demoDataViewer"}'
```
