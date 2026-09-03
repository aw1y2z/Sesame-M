# Sesame-M（芝麻粒-M）

[![License](https://img.shields.io/github/license/aw1y2z/Sesame-M.svg)](LICENSE)

> 芝麻粒系列的又一个分支版本，基于芝麻粒生态做个人向维护与改造。

本项目可与其它同源的芝麻粒模块共存安装。

## 为了大家的资金安全与个人信息安全，强烈建议
1. 不要使用任何未开放源代码的修改版！
2. 不要使用任何未开放源代码的修改版！！
3. 不要使用任何未开放源代码的修改版！！！

## 主要功能
感谢蚂蚁森林对绿化事业的贡献，快速收取蚂蚁森林能量，也为祖国的绿化事业出一份微薄之力。

## 本项目的主要改动
1. **更换 applicationId 为 `io.github.aw1y2z.sesame`**，实现与官方版芝麻粒（`io.github.lazyimmortal.sesame`）等同源模块共存安装、互不覆盖；
2. **迁移至 libxposed API 102**；
3. **整体重写 UI**：全面迁移至 Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix)（Xiaomi HyperOS 风格），界面由 Android Support/XML 旧实现重构；
4. **修复若干历史问题**：native 库解压、日志分项开关失效、Android 15+ 目录写入兼容等；
5. **升级构建与依赖链**：compileSdk 34→37、minSdk 21→26，AGP 9.2 / Gradle 9.4.1 / Kotlin 2.4，AndroidX 化。

## 技术栈 / 使用的框架
- **模块运行框架**: [libxposed](https://github.com/libxposed/api) API 102,由 [LSPosed](https://github.com/LSPosed/LSPosed) 等兼容框架加载
- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix)(Xiaomi HyperOS 设计风格组件库)
- **网络**: OkHttp、NanoHTTPD
- **JSON / 日志 / 注解**: Jackson、XLog、Lombok
- **构建**: Gradle 9.4 / AGP 9.2 / Kotlin 2.4 / JDK 17

## 使用说明
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

