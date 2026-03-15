# viewport-servers 示例说明

该目录存放示例 viewport server 注册文件。

用途：

- 通过 MCP `viewports/list` 注册远端 viewport summary
- 通过 MCP `viewports/get` 透传远端 viewport payload
- 与 `mcp-servers/` 独立配置；同一服务可同时出现在两处

示例字段：

```yaml
serverKey: mock
baseUrl: http://localhost:11969
endpointPath: /mcp
```

同步方式：

- 源：`example/viewport-servers/`
- 目标：项目根目录 `viewport-servers/`
- 可通过 `example/install-example-*` 一键复制
