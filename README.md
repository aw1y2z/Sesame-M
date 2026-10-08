# Sesame-M（芝麻粒-M）

[![License](https://img.shields.io/github/license/aw1y2z/Sesame-M.svg)](LICENSE)

> 芝麻粒系列的又一个分支版本，基于芝麻粒生态做个人向维护与改造。

本项目可与其它同源的芝麻粒模块共存安装。

## 为了大家的资金安全与个人信息安全，强烈建议
1. 不要使用任何未开放源代码的修改版！
2. 不要使用任何未开放源代码的修改版！！
3. 不要使用任何未开放源代码的修改版！！！

## 主要功能

感谢蚂蚁森林对绿化事业的贡献，也为祖国的绿化事业出一份微薄之力。

配置页按 **14 个分组、15 个模块**组织，共 **312** 个可配置项：

| 分组 | 模块 | 主要功能 |
| --- | --- | --- |
| 基础 | 基础 | 执行间隔、定时执行与定时唤醒、只收能量时段、超时重启、新接口开关、RPC 请求列表、气泡提示 |
| 森林 | 森林 | 收取自己与好友能量、一键收取、不收取名单、Pk 榜与 1V1 能量挑战、金球、浇水与返水、复活能量、能量雨、道具收集与赠送、自动续用保护罩、合种浇水、森林乐园、寻宝与抽抽乐助力、种树攻略场景任务、保护地巡护、各类每日任务与礼盒 |
| 保护 | 保护 | 合种、碳中和、保护森林、保护动物、保护地、保护海洋 |
| 保护 | 古树 | 保护古树（按区划代码列表逐区保护，可限定仅星期一、三、五运行） |
| 物种 | 物种 | 物种任务、道具（抽卡 / 万能卡片 / 图鉴勋章）、帮抽卡片、赠送卡片 |
| 海洋 | 海洋 | 海洋任务、清理海域、万能拼图、潘多拉海域、海洋摸鱼 |
| 庄园 | 庄园 | 饲料与道具（新蛋卡 / 加速卡 / 加饭卡）、投喂与帮喂、召唤 / 雇佣 / 遣返小鸡、每日捐蛋与排位赛（偷榜可设捐献上限）、小鸡乐园 / 厨房 / 日记、收取爱心蛋与道具、收麦子与送麦子、装扮焕新 |
| 新村 | 新村 | 摆摊与收摊、贴罚单、丢肥料与收肥料、请走小摊、新村任务、助力就业岗位、解锁新村新店 |
| 农场 | 农场 | 农场任务、农场施肥（含一键施肥 5 次）、农场抽抽乐、农场乐园、摇钱树、分享助力 |
| 金豆 | 金豆夺宝 | 自动签到、自动挖矿、自动完成任务与领奖、金豆乐园抽奖与游戏奖励、领奖后数据同步 |
| 运动 | 运动 | 行走路线（含全主题路线）、运动任务、收运动币、抢好友、文体中心、同步步数、行走捐、健康岛 |
| 会员 | 会员 | 会员任务与签到、会员积分、芝麻粒与芝麻粒任务、攒芝麻分进度、游戏中心、我的快递、黄金票（签到与收取、提取兑换黄金） |
| 经营 | 经营 | 打卡、收取、捐助 |
| AI答 | AI答 | 自定义 AI 接口答题（自填接口地址、模型名、令牌、输出 Token 上限，可测试连通性） |
| 其他 | 小镇 | 小镇任务领奖、每日签到、房屋产物收取（无独立配置项） |

> 分组与模块构成取自 `ModelGroup`、`ModelOrder`；每一项的开关、默认值、取值范围与依赖关系见 [配置项说明](docs/配置项说明.md)。

## 本项目的主要改动
1. **更换 applicationId 为 `io.github.aw1y2z.sesame`**，实现与官方版芝麻粒（`io.github.lazyimmortal.sesame`）等同源模块共存安装、互不覆盖；
2. **迁移至 libxposed API 102**；
3. **整体重写 UI**：全面迁移至 Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix)（Xiaomi HyperOS 风格），界面由 Android Support/XML 旧实现重构；
4. **修复若干历史问题**：native 库解压、日志分项开关失效、Android 15+ 目录写入兼容等；
5. **升级构建与依赖链**：compileSdk 34→37、minSdk 21→26，AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.4.20，AndroidX 化；
6. **重整日志体系**：结果类记录只写分类文件（森林 / 庄园 / 金豆 / 其他），运行日志只留流程并按模块打 tag（可按模块筛流程），失败应答按模块归入对应分类文件。

## 技术栈 / 使用的框架
- **模块运行框架**: [libxposed](https://github.com/libxposed/api) API 102,由 [LSPosed](https://github.com/LSPosed/LSPosed) 等兼容框架加载
- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4(Xiaomi HyperOS 设计风格组件库)；activity-compose 1.13.0、appcompat 1.8.0、material-icons-extended 1.7.8
- **网络**: OkHttp 4.12.0、NanoHTTPD 2.3.1
- **JSON / 日志 / 注解**: Jackson 2.22（databind 2.22.3、annotations 2.22）、XLog 1.11.1、Lombok 1.18.48
- **构建**: Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.4.20 / JDK 17

## 文档

| 文档 | 内容 |
| --- | --- |
| [使用说明](docs/使用说明.md) | 安装与权限、界面结构与改动保存时机、数据目录、配置框架；**日志怎么看**（类目、写入规则、清理规则）；**自动黑名单**的判据与生命周期；AI 答、扩展功能、配置备份、常见问题排查 |
| [配置项说明](docs/配置项说明.md) | 15 个模块、312 个配置项的逐项说明（code、显示名称、默认值与范围、依赖关系） |

> 配置项说明由脚本从源码自动生成；如与实现不一致，**以源码为准**。

## 免责声明
1. 本 APP 是为了学习研究用，不得进行任何形式的转发、发布、传播。
2. 请于 24 小时内卸载本 APP。若使用期间造成任何损失，作者不负任何责任。
3. 本 APP 不篡改、不修改、不获取任何个人信息及其支付宝信息。
4. 本 APP 使用者因为违反本声明的规定而触犯中华人民共和国法律的，一切后果自负，作者不承担任何责任。
5. 凡以任何方式直接、间接使用 APP 者，视为自愿接受本声明的约束。
6. 本 APP 如无意中侵犯了某个媒体或个人的知识产权，请来信或来电告之，作者将立即删除。

## 授权说明
本项目基于 [Dragon813 版 Sesame-GR](https://github.com/Dragon813/Sesame-GR)、[TKaxv-7S 版 Sesame](https://github.com/SenOffical/Sesame-TK)、[constanline 版 XQuickEnergy](https://github.com/constanline/XQuickEnergy) 与 [pansong291 版 XQuickEnergy](https://github.com/pansong291/XQuickEnergy) 开发。

本项目采用 [MIUIX](https://github.com/compose-miuix-ui/miuix) 提供 Xiaomi HyperOS 设计风格的组件库，并基于 [libxposed](https://github.com/libxposed/api) API 102 运行于 LSPosed 框架。

遵循前述所有基于库的协议，并**禁止**用于任何商业用途、禁止二次修改后**闭源**发布。

第三方组件及其对应的许可证原文已收录于 [licenses/](licenses/) 目录：

| 组件 / 项目 | 许可证 | 文件 |
| --- | --- | --- |
| 本项目自身 | GPL-3.0 | [LICENSE](LICENSE) |
| [Dragon813/Sesame-GR](https://github.com/Dragon813/Sesame-GR) | GPL-3.0 | [licenses/LICENSE-dragon813-sesame-gr.txt](licenses/LICENSE-dragon813-sesame-gr.txt) |
| [LSPosed/LSPosed](https://github.com/LSPosed/LSPosed) | GPL-3.0 | [licenses/LICENSE-lsposed.txt](licenses/LICENSE-lsposed.txt) |
| [constanline/XQuickEnergy](https://github.com/constanline/XQuickEnergy) | Apache-2.0 | [licenses/LICENSE-xquickenergy-constanline.txt](licenses/LICENSE-xquickenergy-constanline.txt) |
| [pansong291/XQuickEnergy](https://github.com/pansong291/XQuickEnergy) | Apache-2.0 | [licenses/LICENSE-xquickenergy-pansong291.txt](licenses/LICENSE-xquickenergy-pansong291.txt) |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | [licenses/LICENSE-miuix.txt](licenses/LICENSE-miuix.txt) |
| [libxposed/api](https://github.com/libxposed/api) | Apache-2.0 | [licenses/LICENSE-libxposed-api.txt](licenses/LICENSE-libxposed-api.txt) |

## 特别说明
- 本模块完全免费开源，没有任何收费，请勿二次贩卖。
- 本项目**不支持**合并任何通过修改数据而**实际获利**的功能 PR。
- 鉴于项目的特殊性，本项目可能在任何时间**停止更新**或**删除**。

## 特别感谢
- 感谢芝麻粒生态的维护者与贡献者们（TKaxv-7S、Dragon813、LazyImmortal、Fansirsqi 等）的无私付出。

