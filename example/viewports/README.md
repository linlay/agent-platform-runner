# viewports 示例说明

## 用途

该目录存放示例前端视图定义，供 frontend tool 或 MCP 提示引用。

## 命名规范

- 支持后缀：`.html`、`.qlc`
- 文件命名：`<viewport-key>.<suffix>`
- `viewport-key` 不区分大小写，建议全小写

## 最小示例

```html
<!doctype html>
<html>
  <body>
    <div>demo viewport</div>
  </body>
</html>
```

## 如何新增

1. 新增 `.html` 或 `.qlc` 文件。
2. 通过 `viewportKey`（文件名去后缀）进行访问。
3. 执行示例安装脚本同步到外层 `viewports/`。

## 与外层目录关系

- 源：`example/viewports/`
- 目标：项目根目录 `viewports/`
- 策略：覆盖同名文件，保留额外文件
