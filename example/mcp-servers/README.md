# mcp-servers 示例说明

## 用途

该目录存放示例 MCP Server 注册文件。

## 命名规范

- 文件后缀：`.json`
- 文件命名：语义化命名（如 `mock.json`）

## 最小示例

```json
{
  "serverKey": "mock",
  "baseUrl": "http://127.0.0.1:18080",
  "endpointPath": "/mcp",
  "enabled": true
}
```

## 如何新增

1. 在本目录新增 server 配置 JSON。
2. 保证 `serverKey` 唯一、`baseUrl` 可访问。
3. 执行示例安装脚本同步到外层 `mcp-servers/`。

## 与外层目录关系

- 源：`example/mcp-servers/`
- 目标：项目根目录 `mcp-servers/`
- 策略：覆盖同名文件，保留额外文件
