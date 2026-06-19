# 校园学生行为分析与风险预警系统

面向校园管理场景的学生行为分析与风险预警平台。系统整合学生基础信息、门禁日志、图书借阅日志和网络行为日志，完成数据清洗、时间窗口聚合、行为特征抽取、K-Means 聚类画像、规则评分和风险预警同步，并通过 Web 看板展示学生画像、群体分布、预警列表和任务运行状态。

> 本项目关注“校园行为变化与管理关注风险”，不做医学诊断、心理诊断或健康结论。

## 项目亮点

- 多源数据融合：接入用户基础信息、门禁、借阅、网络 4 类 CSV 数据，并落库到 MySQL。
- 行为特征工程：围绕网络使用、空间出入、借阅学习、活跃度等维度构建 18 项分析特征。
- 学生行为画像：基于 Weka SimpleKMeans 对学生进行聚类，生成“高投入学习型、在线风险型、夜间失衡型、稳健自律型、校园参与型”等群体标签。
- 风险预警闭环：结合综合风险分、分维度风险分、异常标记和聚类上下文，同步生成可处理的风险预警。
- 任务调度与监控：使用 Quartz 定时刷新分析快照、用户画像和预警状态，支持任务触发、重试、暂停、恢复、日志导出和操作审计。
- 前后端一体化：Spring Boot 提供 REST API，静态页面使用 HTML、CSS、JavaScript、ECharts 展示看板。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 2.7.15, Spring MVC |
| 数据访问 | Spring Data JPA, Hibernate, HikariCP |
| 数据库/缓存 | MySQL 8, Redis, Spring Session |
| 任务调度 | Quartz JDBC JobStore |
| 数据处理 | Apache Commons CSV, Gson |
| 聚类分析 | Weka SimpleKMeans |
| 前端 | HTML, CSS, JavaScript, ECharts |
| 构建测试 | Maven, JUnit 5, Spring Boot Test |

## 核心功能

### 1. 数据导入与清洗

启动时由 `DataInitializer` 检查基础数据，如果数据库为空，会从 `src/main/resources/data/` 导入：

- `campus_user.csv`：学生基础信息。
- `access_log.csv`：门禁/空间出入记录。
- `borrow_log.csv`：图书借阅记录。
- `network_log.csv`：网络访问与流量记录。

数据导入后会生成初始分析快照和基础风险预警。

### 2. 多维行为分析

系统以用户为维度聚合最近时间窗口内的行为，提取以下代表性指标：

- 网络维度：日均在线时长、学习流量占比、网络活动次数、异常流量标记。
- 门禁维度：图书馆访问次数、教学楼访问次数、晚归次数、日均出入频率。
- 借阅维度：借阅次数、平均借阅天数、未归还数量。
- 综合维度：活跃天数、健康度分、综合风险分、网络/门禁/借阅分维度风险分。

### 3. 聚类画像

`KMeansService` 使用 18 项特征进行标准化处理，并通过 Weka SimpleKMeans 完成学生群体聚类。聚类结果会写入用户画像，供画像页、群体详情页和预警规则使用。

### 4. 风险预警

`AnalysisComputationService` 负责计算综合风险分和预警候选项，覆盖：

- 综合风险
- 网络行为异常
- 行为考勤异常
- 学业投入异常
- 群体偏移预警
- 画像群体风险
- 借阅学习异常

预警支持分页查询、统计看板、高风险用户列表、处理记录和处理人追踪。

### 5. 定时任务与任务监控

系统内置 3 类 Quartz 任务：

- `analysisUpdateJob`：刷新分析快照。
- `profileUpdateJob`：刷新聚类和用户画像。
- `warningCheckJob`：同步风险预警状态。

任务监控页支持查看状态、执行日志、操作审计、失败详情、导出记录，以及手动触发、重试、暂停、恢复。

### 6. 登录与权限管理

系统提供后台登录、当前账号资料修改、密码修改、账号列表、新建账号、角色调整、启停账号和密码重置。页面和敏感 API 均通过会话拦截器保护。

## 页面入口



主要页面：

| 页面 | 说明 |
| --- | --- |
| `login.html` | 登录页 |
| `dashboard.html` | 综合看板 |
| `users.html` | 学生列表与筛选 |
| `analysis.html` | 单个学生行为分析 |
| `profile.html` | 聚类画像与群体详情 |
| `warning.html` | 风险预警看板和处理 |
| `tasks.html` | 定时任务监控 |
| `account.html` | 系统账号管理 |

## API 概览

| 接口前缀 | 说明 |
| --- | --- |
| `/api/auth` | 登录、登出、当前用户、账号管理 |
| `/api/campus/stats` | 首页统计数据 |
| `/api/campus/trend` | 趋势数据 |
| `/api/campus/users` | 学生列表和用户画像查询 |
| `/api/campus/analysis` | 分析快照、分析历史、用户洞察、手动分析 |
| `/api/campus/cluster` | 聚类统计、群体画像、聚类用户、聚类洞察 |
| `/api/campus/warning` | 预警列表、预警统计、高风险用户、预警处理 |
| `/api/campus/tasks` | Quartz 任务状态、日志、审计、导出和运维操作 |
| `/api/campus/manual-tasks` | 手动全量任务状态 |

## 项目结构

```text
.
├── pom.xml
├── src
│   ├── main
│   │   ├── java/com/example/bysjdesign
│   │   │   ├── campus/controller     # REST API 和页面路由
│   │   │   ├── campus/entity         # JPA 实体
│   │   │   ├── config                # Web、Quartz、Redis、加密等配置
│   │   │   ├── interceptor           # 登录校验和数据访问拦截
│   │   │   ├── job                   # Quartz 任务
│   │   │   ├── repository            # Spring Data JPA 仓储
│   │   │   ├── service               # 分析、聚类、预警、账号、任务服务
│   │   │   └── util                  # CSV 清洗和启动初始化
│   │   └── resources
│   │       ├── data                  # 示例 CSV 数据集
│   │       ├── db/schema/mysql       # Quartz MySQL 表结构
│   │       ├── static                # 前端页面、CSS、JS
│   │       └── application*.properties
│   └── test/java/com/example/bysjdesign
│       └── *Test.java
└── docs
    └── resume-project-plan           # 项目说明和简历表达材料
```

## 本地运行

### 1. 环境准备

- JDK 17
- Maven 3.8+
- MySQL 8.x
- Redis 6.x 或更高版本，开发环境默认不强制启用 Redis Session

### 2. 创建数据库

```sql
CREATE DATABASE campus_behavior
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

Quartz 表会在启动时根据 `src/main/resources/db/schema/mysql/quartz_tables.sql` 初始化。

### 3. 配置环境变量

建议使用环境变量覆盖数据库和初始账号配置，避免把本地密码提交到 GitHub。

PowerShell 示例：

```powershell
$env:DB_USERNAME="root"
$env:DB_PASSWORD="your_mysql_password"
$env:APP_BOOTSTRAP_USERNAME="admin"
$env:APP_BOOTSTRAP_PASSWORD="change_this_password"
$env:APP_BOOTSTRAP_DISPLAY_NAME="系统管理员"
```

常用配置项：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_USERNAME` | MySQL 用户名 | `root` |
| `DB_PASSWORD` | MySQL 密码 | 以本地配置为准，建议覆盖 |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `SERVER_PORT` | 服务端口 | `8080` |
| `APP_BOOTSTRAP_USERNAME` | 首次启动创建的管理员账号 | 以配置文件为准，建议覆盖 |
| `APP_BOOTSTRAP_PASSWORD` | 首次启动创建的管理员密码 | 以配置文件为准，建议覆盖 |
| `APP_ANALYSIS_CRON` | 分析快照任务 Cron | `0 0 * * * ?` |
| `APP_PROFILE_CRON` | 用户画像任务 Cron | `0 5 * * * ?` |
| `APP_WARNING_CRON` | 预警同步任务 Cron | `0 10 * * * ?` |

### 4. 启动项目

```bash
mvn spring-boot:run
```

默认使用 `dev` profile。首次启动会：

1. 自动建表或更新表结构。
2. 导入 `resources/data` 下的 CSV 数据。
3. 生成基础分析快照。
4. 同步基础风险预警。
5. 创建初始系统账号。

### 5. 访问系统

```text
http://localhost:8080/login.html
```

使用环境变量中配置的管理员账号登录。

## 测试与构建

运行测试：

```bash
mvn test
```

打包：

```bash
mvn clean package
```

运行 Jar：

```bash
java -jar target/bysj-design-1.0.0.jar
```

测试和启动依赖本地 MySQL 数据库。若数据库中已有数据，启动初始化逻辑会跳过已存在的数据导入。


## 项目边界

本项目用于展示多源校园行为数据处理、后端工程化、聚类画像和风险预警流程。系统输出应理解为“管理关注线索”和“行为变化提示”，不应作为医学、心理或纪律处分结论。
