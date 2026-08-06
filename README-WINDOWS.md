# 文件对比工具 Windows 教程

适用版本：`0.9.0-rc1`  
适用系统：Windows 10 / 11 x64

## 你会得到什么

- `FileCompareTool-0.9.0-rc1-win-x64-portable.zip`
- `FileCompareTool-0.9.0-rc1-win-x64-setup.exe`
- `SHA256SUMS.txt`

便携版解压即用。安装版提供当前用户安装、开始菜单入口、快捷方式和卸载入口。

## 如果你只是下载来使用

1. 从 GitHub Releases 下载对应的 `portable.zip` 或 `setup.exe`
2. 先核对 `SHA256SUMS.txt`
3. 便携版直接解压后双击 `FileCompareTool.exe`
4. 安装版直接运行安装程序，按向导完成安装

## 构建要求

- Windows x64
- JDK 21 LTS
- `java.exe` 和 `jpackage.exe`
- WiX Toolset 3 的 `candle.exe` 和 `light.exe`
- PowerShell 5.1+

先确认环境：

```powershell
java -version
jpackage --version
```

如果本机没有 WiX 3，可先安装 WiX Toolset 3，或者把 `wix314-binaries.zip` 放在项目根目录让脚本自动解压使用。

## 从源码生成发布包

在项目根目录执行：

```powershell
.\scripts\package-windows.ps1 -JdkHome "D:\Program Files\Java\jdk-21"
```

脚本会依次完成：

1. 运行 19 项非视觉测试
2. 编译 Java 8 兼容字节码
3. 生成带 `Main-Class` 的 JAR
4. 生成 `jpackage` app-image
5. 生成便携 ZIP
6. 生成安装 EXE
7. 写入 `SHA256SUMS.txt`

输出目录是 `dist/`。

## 校验发布包

在 `dist/` 里执行：

```powershell
Get-FileHash .\FileCompareTool-0.9.0-rc1-win-x64-portable.zip -Algorithm SHA256
Get-FileHash .\FileCompareTool-0.9.0-rc1-win-x64-setup.exe -Algorithm SHA256
```

结果应与 `SHA256SUMS.txt` 一致。

## 便携版使用

1. 解压 `FileCompareTool-0.9.0-rc1-win-x64-portable.zip`
2. 直接双击 `FileCompareTool.exe`
3. 不需要额外安装 Java

便携版不会写入安装信息，但会把用户数据保留在本机固定目录。

## 安装版使用

1. 运行 `FileCompareTool-0.9.0-rc1-win-x64-setup.exe`
2. 按向导完成当前用户安装
3. 从开始菜单启动“文件对比工具”

安装版默认不要求管理员权限。卸载时只移除程序文件，不会自动删除用户数据。

## 用户数据位置

```text
%LOCALAPPDATA%\FileCompareTool\
├── filter-config.xml
├── history.xml
├── preferences.xml
└── backups\
```

升级前建议先备份整个目录。便携版升级时，解压到新的空目录，不要和旧版本混放。

## 升级与卸载

- 升级：先关闭程序，再覆盖安装或重新解压
- 回退：保留旧版安装包和旧版便携目录
- 卸载：在系统卸载入口中移除程序
- 彻底清理：确认不再需要数据后，手动删除 `%LOCALAPPDATA%\FileCompareTool`

## 常见问题

### 没有找到 WiX 工具

确认 `candle.exe` 和 `light.exe` 在 PATH 中。没有的话，先安装 WiX Toolset 3。

### 便携版启动不了

先确认不是被杀毒软件拦截，再检查 `FileCompareTool.exe` 是否完整解压。

### 安装后找不到数据

数据不在安装目录，在 `%LOCALAPPDATA%\FileCompareTool`。

