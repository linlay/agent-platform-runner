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

- `image.yml`：面向本机联调的 `mcp-server-image` 注册示例，默认地址为 `http://127.0.0.1:11962/mcp`。
- 当前 runner 只识别 `serverKey/baseUrl/endpointPath/enabled/readTimeoutMs` 这一类注册字段，不兼容 `mcp-server-image` 仓库里 `name/label/transport` 的示例格式。
- `mcp-server-image` 的 `tools/call.params._meta.chatId` 会由 runner 自动透传，图片文件会落到当前 chat 对应的数据目录。

## 与外层目录关系

- 源：`example/mcp-servers/`
- 目标：项目根目录 `mcp-servers/`
- 策略：覆盖同名文件，保留额外文件
