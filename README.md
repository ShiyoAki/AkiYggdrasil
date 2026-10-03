# AkiYggdrasil

**A server-side NeoForge mod that lets one Minecraft server accept multiple
Yggdrasil authentication sources at the same time.**

让同一个 Minecraft 服务器同时支持多个 Yggdrasil 认证源：LittleSkin、
其他 authlib-injector 兼容皮肤站、以及 Mojang 正版。不同来源的玩家可以
一起进服，并且各自显示自己的皮肤。

- 支持 **NeoForge 1.21.1** 服务端（含 Youer 等 Paper/Purpur + NeoForge 混合端）
- **纯服务端模组**：玩家端什么都不用装，PCL / HMCL 等启动器照常使用外置登录
- 配置**热重载**：修改保存后约 3 秒自动生效，无需重启服务器
- 内置两个认证源：`LittleSkin`（https://littleskin.cn/api/yggdrasil）和
  `MojangOfficial`（Mojang 正版），按优先级依次验证

## 安装 / Install

1. 服务器使用 NeoForge 1.21.1（或兼容 NeoForge 的混合端），并开启正版验证
   （`online-mode=true`）；
2. 下载 `akiyggdrasil-0.1.0.jar`（从 GitHub Releases 或 Actions 构建产物获取），
   放进服务端 `mods/` 目录；
3. 启动服务器。首次启动会自动生成 `config/akiyggdrasil.toml` 配置文件。

## 配置 / Configuration

配置文件：`config/akiyggdrasil.toml`（支持中文注释；删除文件可恢复默认配置）。

```toml
# 每个 [[sources]] 表都是一个认证源

[[sources]]
name = "LittleSkin"                          # 认证源名称，必须唯一
type = "API"                                 # OFFICIAL = Mojang 正版 / API = 皮肤站
apiRoot = "https://littleskin.cn/api/yggdrasil"  # API 源根地址（type = API 时必填）
ordinal = 0                                  # 优先级，数字越小越先尝试

[[sources]]
name = "MojangOfficial"
type = "OFFICIAL"
ordinal = 1
```

| 字段 | 说明 |
|---|---|
| `name` | 认证源名称，必须唯一 |
| `type` | `API`：authlib-injector 兼容皮肤站；`OFFICIAL`：Mojang 正版 |
| `apiRoot` | `type = API` 时必填。会话/服务地址自动推导为 `<apiRoot>/sessionserver`、`<apiRoot>/minecraftservices` |
| `sessionHost` / `servicesHost` | 可选，`OFFICIAL` 类型可覆盖默认的 Mojang 官方地址 |
| `ordinal` | 轮询优先级，从小到大依次询问 |

> **热重载**：保存文件后约 3 秒自动生效。若文件处于编辑中途（语法不完整），
> 会保留上一份有效配置并在日志中告警，不会影响正在运行的服务。

## 常见问题 / FAQ

- **玩家需要装什么吗？** 什么都不用装。这是纯服务端模组，玩家用任意支持
  外置登录的启动器（PCL、HMCL、官方启动器）登录自己的皮肤站账号即可。
- **为什么服务器要求开正版验证？** 只有 `online-mode=true` 时 Minecraft
  才会走 Yggdrasil 会话验证流程，多源认证依赖这个流程。
- **某来源的玩家登不进来 / 皮肤不显示？** 检查该源的 `apiRoot` 是否填写正确、
  服务器能否访问该源；源失联时模组会记录 WARN 并跳过它，把失联源从配置里
  删掉即可。
- **聊天签名（PROFILE_KEY）说明**：第三方 Yggdrasil 源不签发聊天会话密钥，
  因此对聊天密钥采用「接受任意签名」的处理，保证所有来源玩家聊天可用；
  皮肤材质属性仍按真实公钥严格验证，无法伪造。

## 反馈 / Feedback

本项目使用**生成式人工智能**辅助开发。遇到 Bug 或有什么建议，欢迎到
[Issues](https://github.com/ShiyoAki/AkiYggdrasil/issues) 提交——已内置
中英文的 Bug 反馈与功能建议模板，按模板填写即可。

## 许可 / License

[MIT](LICENSE)。

参考项目：

- [authlib-injector](https://github.com/yushijinhun/authlib-injector) —
  Yggdrasil 服务端技术规范；
- [MultiYggdrasil](https://github.com/Qiushui1012/MultiYggdrasil) —
  多源认证服务的架构设计。
