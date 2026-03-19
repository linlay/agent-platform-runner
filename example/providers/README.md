# providers 示例说明

该目录存放可复制到项目根目录 `providers/` 的 provider 示例配置。

说明：

- 当前仅提供一个平台无关模板：`example.yml`。
- 使用方式：复制为 `providers/<provider-key>.yml`，再填写真实 `key/baseUrl/apiKey/model`。
- 文件名建议与 `key` 保持一致。
- `apiKey` 使用占位值，安装后请替换成真实密钥。
- `providers/` 是运行时外部目录，默认支持热加载。
