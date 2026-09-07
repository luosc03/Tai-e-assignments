<div align="center">
  <a href="https://tai-e.pascal-lab.net/">
    <img src="https://tai-e.pascal-lab.net/o-tai-e.webp" height="180" alt="Tai-e">
  </a>

  <h1>Tai-e Assignments</h1>
  <p>南京大学《静态分析》课程实验作业</p>

  <p>
    <a href="https://tai-e.pascal-lab.net/">Tai-e 官网</a> ·
    <a href="https://tai-e.pascal-lab.net/intro/overview.html">中文实验概览</a> ·
    <a href="https://tai-e.pascal-lab.net/en/intro/overview.html">English Overview</a>
  </p>
</div>

本仓库基于 [Tai-e 官方作业仓库](https://github.com/pascal-lab/Tai-e-assignments)，用于记录个人学习过程、实验代码与测试结果。每个实验都是一个独立的 Gradle Java 项目。

## 实验内容

| 目录 | 主题 |
| --- | --- |
| [A1](A1/tai-e) | 控制流图（CFG）与活跃变量分析（Live Variable Analysis） |
| [A2](A2/tai-e) | 常量传播（Constant Propagation） |
| [A3](A3/tai-e) | 死代码检测（Dead Code Detection） |
| [A4](A4/tai-e) | 类层次分析（CHA）与过程间常量传播 |
| [A5](A5/tai-e) | 上下文不敏感指针分析（CI Pointer Analysis） |
| [A6](A6/tai-e) | 上下文敏感指针分析框架（CS Pointer Analysis） |
| [A7](A7/tai-e) | 2-对象敏感分析与别名感知的过程间常量传播 |
| [A8](A8/tai-e) | 指针分析基础上的污点分析（Taint Analysis） |

每个实验目录中的 `plan.yml` 定义了该实验启用的分析流程；`src/main/java` 存放待完成的分析实现，`src/test` 存放测试用例和期望结果。

## 环境要求

- JDK 17。工程在 `build.gradle.kts` 中启用了 Java 17 toolchain。
- Git。
- Gradle Wrapper 会自动使用 Gradle 7.4；通常不需要单独安装 Gradle。

官方作业仓库提供的运行库位于根目录 `lib/`，各实验还依赖对应目录下的 `lib/tai-e-assignment.jar`。如果使用了 Git 的按需对象克隆，首次运行前请确保这些 JAR 已经检出。

## 开始一个实验

以 A1 为例：

```bash
cd A1/tai-e

# Windows PowerShell
.\gradlew.bat test

# macOS / Linux
./gradlew test
```

完成代码后，建议在对应实验目录执行完整测试：

```bash
./gradlew test
```

如果需要运行 Tai-e 主程序，可使用 Gradle 的 `run` 任务；命令行参数格式和分析配置以该实验的 `plan.yml` 及官方实验说明为准：

```bash
./gradlew run --args="-cp <CLASS_PATH> -m <CLASS_NAME>"
```

## 推荐工作流

1. 阅读官方实验概览和当前实验的 `plan.yml`。
2. 先查看 `src/test` 中的输入程序与期望输出，理解分析语义。
3. 只在当前实验对应的 `src/main/java` 中完成待实现逻辑。
4. 使用 `gradlew test` 验证结果，并检查 CFG、分析结果或日志输出。
5. 提交代码时保持每个实验的改动独立，便于回溯和排查问题。

## 目录结构

```text
.
├── A1/tai-e/          # 实验一
├── A2/tai-e/          # 实验二
├── ...
├── A8/tai-e/          # 实验八
├── lib/               # Tai-e 公共运行库
└── README.md
```

## 学术诚信与作业保密

Tai-e 作业平台用于辅助学习静态程序分析。请遵守课程和平台规定，不要公开发布作业答案或可直接提交的完整解答；本仓库内容仅用于个人学习、复习和版本管理。

## 许可证

Tai-e 相关代码遵循仓库中提供的 [GNU LGPL v3](COPYING.LESSER) 及相关许可证文件。使用本仓库前请阅读 `COPYING` 和 `COPYING.LESSER`。
