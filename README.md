# AkiYggdrasil

A server-side NeoForge 1.21.1 mod that lets one Minecraft server accept
**multiple Yggdrasil authentication sources at the same time** — LittleSkin,
other authlib-injector skin servers, and the official Mojang authentication —
so players from different sources can join together and see their own skins.

服务端模组：让同一个服务器同时支持多个 Yggdrasil 认证源（LittleSkin、自建皮肤站、
Mojang 正版），不同来源的玩家可以一起进服并各自显示自己的皮肤。

- **包名 / Package**: `com.shiyo.akiyggdrasil`
- **版本 / Version**: 0.1.0
- **作者 / Author**: ShiyoAki
- **许可 / License**: MIT（见 [LICENSE](LICENSE)）

## 特性 / Features

- 内置两个认证源（按 `ordinal` 顺序轮询）：
  - `LittleSkin` — https://littleskin.cn/api/yggdrasil
  - `MojangOfficial` — Mojang 正版认证
- 玩家登录时按顺序询问每个源，谁认识这个会话谁放行；皮肤材质签名用
  「Mojang 公钥 + 各 API 源 `signaturePublickey`」的**多公钥集合**验证，
  各来源的皮肤都能正常显示；
- 纯服务端模组，玩家什么都不用装（PCL、HMCL 等照常使用外置登录）；
- **配置热重载**：修改配置文件保存后约 3 秒自动生效，无需重启服务器；
- 兼容原版 NeoForge 服务端，也兼容 Youer 等 Paper/Purpur 系 + NeoForge
  混合端（已在 Youer 1.21.1 上实测）。

## 安装 / Install

1. 服务器需为 NeoForge 1.21.1 服务端（或兼容 NeoForge 的混合端），
   并开启正版验证（`online-mode=true`）；
2. 把 `akiyggdrasil-0.1.0.jar` 放进服务端的 `mods/` 目录；
3. 启动服务器。首次启动会在 `config/` 目录自动生成
   `akiyggdrasil.toml` 配置文件，之后即可编辑增删认证源。

## 配置 / Configuration

配置文件：`config/akiyggdrasil.toml`（支持中文注释，删除文件可恢复默认配置）。

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

字段说明：

| 字段 | 说明 |
|---|---|
| `name` | 认证源名称，必须唯一 |
| `type` | `API`：authlib-injector 兼容皮肤站；`OFFICIAL`：Mojang 正版 |
| `apiRoot` | `type = API` 时必填。会话/服务地址自动推导为 `<apiRoot>/sessionserver`、`<apiRoot>/minecraftservices` |
| `sessionHost` / `servicesHost` | 可选，`OFFICIAL` 类型可覆盖默认的 Mojang 官方地址 |
| `ordinal` | 轮询优先级，从小到大依次询问 |

> **热重载**：保存文件后约 3 秒自动生效。若文件处于编辑中途（语法不完整），
> 会保留上一份有效配置并在日志中告警，不会影响正在运行的服务。

## 工作原理 / How it works

服务端在 `net.minecraft.server.Main#main` 中创建认证服务。模组用 Mixin
（`PhysicalServerStartMixin`）把它替换为 `AkiAuthService`：

```
Main (vanilla) ──Mixin 重定向──> AkiAuthService
                                     ├─ createMinecraftSessionService() -> AkiSessionService
                                     │     多源 join / hasJoined / profile 轮询
                                     ├─ createProfileRepository()      -> AkiGameProfileRepository
                                     │     多源按名批量查询
                                     └─ getServicesKeySet()            -> 多公钥签名验证
                                            (Mojang 公钥 + 各 API 源 signaturePublickey)
```

```
src/main/java/com/shiyo/akiyggdrasil/
├── AkiYggdrasil.java            # @Mod 入口
├── api/YggdrasilApi.java        # Yggdrasil 协议客户端（纯 JDK HTTP + Gson）
├── auth/
│   ├── AkiAuthService.java      # 多源认证服务（被 Mixin 注入）
│   ├── AkiSessionService.java   # 多源会话服务（join/hasJoined/profile）
│   ├── AkiGameProfileRepository.java  # 多源名称查询
│   ├── AkiServicesKeyInfo.java  # 多公钥材质签名验证
│   ├── PublicKeyUtil.java       # PEM/DER 公钥解析
│   ├── DummySignature.java      # PROFILE_KEY（聊天密钥）校验用的空签名
│   └── TextureUrlChecker.java   # 材质域名策略
├── config/
│   ├── AkiConfig.java           # 认证源配置（TOML 解析 + 热重载）
│   ├── AuthSource.java          # 单个认证源模型
│   └── AuthSourceType.java      # OFFICIAL / API
└── mixin/PhysicalServerStartMixin.java  # 重定向 Main 的认证服务构造
```

## 构建 / Build

推送后由 GitHub Actions 自动编译（JDK 21 + Gradle），编译产物可在
Actions 页面下载。本地编译：

```powershell
.\gradlew.bat build
```

产物：`build/libs/akiyggdrasil-0.1.0.jar`。

## 已知取舍 / Trade-offs

- **聊天签名（PROFILE_KEY）**：第三方 Yggdrasil 源不签发聊天会话密钥，
  因此对 `PROFILE_KEY` 采用「接受任意签名」的空验证器，保证所有来源玩家
  聊天可用；材质属性（`PROFILE_PROPERTY`）仍按真实公钥严格验证。
- **启动时抓取各源元数据**：若某 API 源失联，会记录 WARN 并跳过该源
  （启动会变慢但不会崩溃），从配置里移除失联源即可恢复。

## 参考与许可 / References & License

核心逻辑为原创实现，仅参考了以下项目的协议规范与接口设计：

- [authlib-injector](https://github.com/yushijinhun/authlib-injector) —
  Yggdrasil 服务端技术规范（wiki 为 CC BY-SA 4.0）；其本体为 AGPLv3，
  本模组未复制其代码，仅遵循协议文档，不受 AGPL 传染。
- [MultiYggdrasil](https://github.com/Qiushui1012/MultiYggdrasil) —
  参考其多源认证服务架构（会话服务轮询、多公钥验证、Mixin 重定向方式）。

本模组采用 [MIT](LICENSE) 许可。
