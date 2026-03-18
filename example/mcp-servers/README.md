# mcp-servers 示例说明

## 用途

该目录存放示例 MCP Server 注册文件。

## 命名规范

- 文件后缀：`.yml`、`.yaml`
- 文件命名：语义化命名（如 `mock.yml`）

## 最小示例

```yaml
serverKey: mock
baseUrl: http://127.0.0.1:18080
endpointPath: /mcp
enabled: true
```

## 如何新增

1. 在本目录新增 server 配置 YAML。
2. 保证 `serverKey` 唯一、`baseUrl` 可访问。
3. 执行示例安装脚本同步到外层 `mcp-servers/`。

## 图像 MCP 示例

- `imagine.yml`：面向本机联调的 `mcp-server-imagine` 注册示例，默认地址为 `http://127.0.0.1:11962/mcp`。
- `mcp-server-imagine` 是服务名；runner 侧注册文件使用 `serverKey: imagine`，但图像工具名仍保持 `image.*`。
- 同一个 `mcp-server-imagine` 未来可以继续暴露 `video.*` 工具，不需要改动当前 `image.*` 命名空间。
- 当前 runner 只识别 `serverKey/baseUrl/endpointPath/enabled/readTimeoutMs` 这一类注册字段，不兼容 `mcp-server-imagine` 仓库里 `name/label/transport` 的示例格式。
- `mcp-server-imagine` 的 `tools/call.params._meta.chatId` 会由 runner 自动透传，图片文件会落到当前 chat 对应的数据目录。
- 历史本地文件 `mcp-servers/image.yml` 已废弃；请改用 `mcp-servers/imagine.yml`。示例安装脚本会在同步前自动删除旧文件，避免和新文件同时存在时重复注册 `image.*` 工具。

## 与外层目录关系

- 源：`example/mcp-servers/`
- 目标：项目根目录 `mcp-servers/`
- 策略：覆盖同名文件；同步前会定向删除已废弃的 `mcp-servers/image.yml`
