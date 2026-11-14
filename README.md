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

配置页按分组与模块两级组织：

| 分组 | 模块 | 主要功能 |
| --- | --- | --- |
| 基础 | 基础 | 执行间隔、定时执行与定时唤醒、只收能量时段、超时重启、新接口开关、RPC 请求列表、气泡提示 |
| 森林 | 森林 | 收取自己与好友能量、一键收取、不收取名单、Pk 榜与 1V1 能量挑战、金球、浇水与返水、复活能量、能量雨、道具收集与赠送、自动续用保护罩、合种浇水、森林乐园、寻宝与抽抽乐助力、保护地巡护、各类每日任务与礼盒 |
| 保护 | 保护 | 合种、碳中和、保护森林、保护动物、保护地、保护海洋 |
| 保护 | 古树 | 保护古树 |
| 物种 | 物种 | 物种任务、道具（抽卡 / 万能卡片 / 图鉴勋章）、帮抽卡片、赠送卡片 |
| 海洋 | 海洋 | 海洋任务、清理海域、万能拼图、潘多拉海域、海洋摸鱼 |
| 庄园 | 庄园 | 饲料与道具（新蛋卡 / 加速卡 / 加饭卡）、投喂与帮喂、召唤 / 雇佣 / 遣返小鸡、每日捐蛋与排位赛、小鸡乐园 / 厨房 / 日记、小鸡大厨（AI 厨房）、收取爱心蛋与道具、收麦子与送麦子、装扮焕新 |
| 新村 | 新村 | 摆摊与收摊、贴罚单、丢肥料与收肥料、请走小摊、新村任务、助力就业岗位、解锁新村新店 |
| 农场 | 农场 | 农场任务、农场施肥、农场抽抽乐、农场乐园、摇钱树、分享助力 |
| 金豆 | 金豆夺宝 | 自动签到、自动挖矿、自动完成任务与领奖、金豆乐园抽奖与游戏奖励 |
| 运动 | 运动 | 行走路线、运动任务、收运动币、抢好友、文体中心、同步步数、行走捐、健康岛 |
| 会员 | 会员 | 会员任务与签到、会员广告任务、会员积分、芝麻粒与芝麻粒任务、攒芝麻分进度、游戏中心、我的快递、黄金票、福利金 |
| 经营 | 经营 | 打卡、收取、捐助 |
| AI答 | AI答 | 自定义 AI 接口答题 |

> 分组与模块构成取自 `ModelGroup`、`ModelOrder`；每一项的开关、默认值、取值范围与依赖关系见 [配置项说明](docs/配置项说明.md)。

设置页另有两个工具入口：**好友统计**（本周 / 累计从各好友处收到的能量，可排序、搜索并跳转好友主页）与**扩展功能**（清空光盘行动图片、自定义行走路线）。

「配置」tab 的账号行支持**左滑删除**：二次确认后删除该账号的配置、状态与滚动备份（统计为全局一份，不随之删除）；不保留删除记录，该账号下次登录会重新出现并从默认配置开始，删除前需先强制停止支付宝。

## 技术栈 / 使用的框架
- **模块运行框架**: [libxposed](https://github.com/libxposed/api)（`io.github.libxposed:api`、`:service`，均 102.0.0）,由 [LSPosed](https://github.com/LSPosed/LSPosed) 等兼容框架加载
- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4(Xiaomi HyperOS 设计风格组件库)；activity-compose 1.13.0、appcompat 1.8.0、material-icons-extended 1.7.8
- **网络**: OkHttp 4.12.0、NanoHTTPD 2.3.1
- **JSON / 日志 / 注解**: Jackson 2.22（databind 2.22.3、annotations 2.22）、XLog 1.11.1、Lombok 1.18.48
- **构建**: Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.4.20 / JDK 17

## 文档

| 文档 | 内容 |
| --- | --- |
| [使用说明](docs/使用说明.md) | 安装与权限、界面结构与改动保存时机、数据目录、配置框架、**账号管理与删除**；**日志怎么看**（类目、写入规则、清理规则）；**自动黑名单**的判据与生命周期；AI 答、好友统计、扩展功能、配置备份、常见问题排查 |
| [配置项说明](docs/配置项说明.md) | 各模块配置项的逐项说明（code、显示名称、默认值与范围、依赖关系） |
| [合规与免责](docs/合规与免责.md) | 完整版：项目性质与非官方声明、可能产生的风险、使用义务、数据处理与网络行为、责任范围、权利通知、条款版本记录 |

> 配置项说明由脚本从源码自动生成；如与实现不一致，**以源码为准**。

## 合规与免责

> 完整版见 [合规与免责](docs/合规与免责.md)。

1. **性质**：本项目为个人学习与技术研究用途的开源项目，**非官方产品**，与蚂蚁集团、支付宝及其关联方**无任何隶属、合作、授权或背书关系**。「支付宝」「蚂蚁森林」「芝麻粒」等名称与标识归其权利人所有。
2. **风险自担**：本模块通过注入方式改变宿主应用的行为，**可能违反平台用户协议**，并可能导致账号被限制、已获权益被回收或账号被封禁。是否使用、如何使用，由你自行评估并自行承担全部后果。
3. **无担保**：本项目按「现状」提供，不附带任何明示或默示担保，不保证可用性、正确性、连续性，也不保证不被平台识别或处理。
4. **数据处理**：本模块自身未申请 `INTERNET` 权限；注入后代码运行在支付宝进程内，网络请求经由**宿主应用的网络权限**发出，仅用于完成你已授权的操作，以及将答题所需的题目文本发送至**你自行配置的第三方 AI 接口**。作者不运营任何接收用户数据的服务器，不收集、不存储、不上传你的账号凭证、好友列表、能量与任务数据。
5. **使用义务**：应遵守所在地区法律法规及平台协议；不得用于商业牟利、代他人操作、批量控制账号，不得用于规避平台安全机制。
6. **责任限制**：在适用法律允许的最大范围内，作者不对因使用或无法使用本项目产生的任何直接或间接损失承担责任。本条款不排除依法不能排除的责任。
7. **权利通知**：如本项目内容侵犯了你的合法权益，请邮件联系 **aw1y2z@foxmail.com**，核实后将立即删除或修改相关内容。
8. 使用本项目即视为已阅读并同意上述内容。

## 联系与反馈

本仓库不设公开 Issue 入口。使用问题、权利通知与安全报告请邮件至 **aw1y2z@foxmail.com**；完整条款见 [合规与免责](docs/合规与免责.md)。

## 授权说明
本项目基于 [Dragon813 版 Sesame-GR](https://github.com/Dragon813/Sesame-GR)、[TKaxv-7S 版 Sesame](https://github.com/SenOffical/Sesame-TK)、[constanline 版 XQuickEnergy](https://github.com/constanline/XQuickEnergy) 与 [pansong291 版 XQuickEnergy](https://github.com/pansong291/XQuickEnergy) 开发。

本项目采用 [MIUIX](https://github.com/compose-miuix-ui/miuix) 提供 Xiaomi HyperOS 设计风格的组件库，并基于 [libxposed](https://github.com/libxposed/api) API 102 运行于 LSPosed 框架。

本项目遵循上游 GPL-3.0 协议。作者**不建议**将本项目用于任何商业用途，也不接受以获利为目的的功能贡献。上述表述为作者意愿，不构成对 GPL-3.0 所授予权利的额外限制。

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
- 本模块由作者免费开源提供，作者未收取任何费用，也未授权任何人代为收费分发。GPL-3.0 允许他人自行分发、甚至收取一定费用，但**通过第三方付费获得的副本与作者无关**，作者不保证其完整性、安全性与更新；如你付费购买，请向收款方主张权利。
- 鉴于项目的特殊性，作者不承诺持续更新，可能在任何时间**停止维护、停止更新或归档**本仓库。

## 特别感谢
- 感谢芝麻粒生态的维护者与贡献者们（TKaxv-7S、Dragon813、LazyImmortal、Fansirsqi 等）的无私付出。

