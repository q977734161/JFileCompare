# 文件对比工具

一个基于 Java Swing 的文件和目录对比工具，支持内容校验、递归对比、差异编辑、同步预览、历史记录、过滤规则和窗口偏好保存。

![图标](assets/app-icon.png)

## 特性

- 文件对比和目录对比
- 基于 SHA-256 的内容判断
- 左右结果联动滚动
- 差异编辑与单行同步
- 目录过滤和过滤预设
- 历史记录与最近任务
- 编码检测与换行保真
- 同步预览、备份和回滚
- 窗口位置与偏好保存

## 快速开始

Windows 上直接运行：

```bat
run.bat
```

源码方式启动：

```powershell
mvn compile
java -cp "target/classes;lib/*" FileCompareTool
```

## 源码结构

```text
src/main/java    生产代码
src/test/java    非视觉测试和 Swing 烟测
scripts/         编译、测试、打包脚本
assets/          图标和图片
lib/             离线依赖
legal/           第三方许可证正文
article-output/  文章稿件和导出物
```

`build/`、`dist/` 和 `out/` 都是生成目录，不建议提交。

## Windows 发布

Windows 发布包包含便携 ZIP 和当前用户安装包，构建和使用说明见 [README-WINDOWS.md](README-WINDOWS.md)。

```powershell
.\scripts\package-windows.ps1 -JdkHome "D:\Program Files\Java\jdk-21"
```

## 版本说明

当前发布版本为 `0.9.0-rc1`。应用内部继续显示候选版本号，Windows 安装器使用纯数字版本号，便于 `jpackage` 处理。

## 许可证

项目本体见 [LICENSE.txt](LICENSE.txt)，第三方依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

