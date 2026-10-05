# import_openalex

把 [OpenAlex](https://openalex.org/) 公开快照里的学术实体导入 MySQL，再同步到 Elasticsearch（检索）和对象存储（模型训练）的 ELT 数据管道。EL 部分在 `importer` 模块，T 部分在 `transformer` 模块。

MySQL 是原始数据层：`social_entity` 表按 `platform + entity_type + entity_id` 存放任意来源的原始 JSON。扩展点放在这一层，以后接入其他平台的人物画像数据（F-08），新增的是写入同一张表的导入程序。目前只接入了 OpenAlex。

```text
 OpenAlex snapshot (public AWS S3 bucket)
   {entity}/manifest.json
   {entity}/updated_date=YYYY-MM-DD/part_NNNN.gz   (JSON Lines, gzip)
        |
        |  importer:  F-01 plan --> F-02 import --> F-03 reconcile / status
        v
 +------------------------------ MySQL ---------------------------------+
 |  social_entity                 : one row per entity, raw JSON kept   |
 |                                  updated_at refreshed on every write |
 |  sync_job, file_task, dead_row : EL bookkeeping                      |
 |  sync_task, dead_letter        : T bookkeeping                       |
 +----------------------------------------------------------------------+
        |
        |  transformer:  F-04 full sync,  F-07 incremental sync (planned)
        |
        +---------------------------------+
        v                                 v
 +-------------------------+      +-----------------------------+
 | Elasticsearch     F-05  |      | Object storage (OSS)  F-06  |
 | authors search index    |      | raw JSON for training       |
 +-------------------------+      +-----------------------------+

 F-08 (future): importers for other platforms write the same social_entity table
```

## 功能需求

每个功能按同一结构展开：做什么、约束（非功能需求）、环境前提及其对这个功能的影响、依赖、设计文档、状态。状态截至 2026-10-05。

编号规则：

- `F-xx`：功能。
- `N-xx-y`：功能 F-xx 的约束。
- `ENV-OA-xx`：OpenAlex 的外部约束；`ENV-DEP-xx`：部署环境；`ENV-USE-xx`：下游使用方。同一条环境前提影响多个功能时，以同一编号出现在多处，每处写明对该功能的影响；来源见文末的"环境前提索引"。
- `G-xx`：不属于任何单个功能的全局约束。

验收标准之后写进 `openspec/specs/`，按这里的编号对应。

### F-01 规划导入（`importer plan`）

**做什么**：给定 entity，下载 OpenAlex 当前的 manifest，和本地已登记的文件对比，把需要导入的文件登记为一次导入计划。第一次运行时本地没有任何记录，会登记全部文件，也就是全量导入。

**约束**

- N-01-1 **原子性**：一次计划和它的全部文件要么一起登记，要么都不登记。
- N-01-2 **幂等**：同一个文件只登记一次。计划本身不保证幂等。
- N-01-3 **拒绝坏输入**：manifest 解析失败、各文件条数之和与总数不符、文件 URL 格式不对或 entity 不一致时，报错退出，不登记任何东西。
- N-01-4 **只登记新文件**：只登记比本地水位更新的文件。水位是已登记文件里最大的（日期，序号），与这些文件是否导入成功无关。

**环境前提**

- ENV-OA-01 每个 entity 有一份 manifest，列出全部文件的 URL、条数和大小，URL 里带分区日期和文件序号 → 规划只需下载 manifest（不到 100 KB），水位直接取自 URL。
- ENV-OA-02 记录按最后一次变化的日期分区；记录更新后会移到新分区，同一个 release 里每条记录只出现一次 → 只需登记比水位新的分区，已导入的旧分区不用重下。
- ENV-OA-05 大约每季度发布一次，最近一次发布有九成以上的记录更新 → 第一次之后的每次规划，登记的文件也接近全量。
- ENV-DEP-01 开发机放不下全量 authors → 开发环境需要只登记 authors 的一部分文件，目前没有这个选项。

**依赖**：无。**设计**：[plan 阶段设计](importer/docs/plan阶段设计.md)、[数据库设计](importer/docs/数据库设计.md)。

**状态**：已实现，有单元测试。待确认：最新分区的日期等于发布日期，可能只是不完整的一天；如果下次发布往这个分区已有的文件里补了记录，按 N-01-4 的规则会漏掉。

### F-02 执行导入（`importer work`）

**做什么**：领取 F-01 登记的文件，下载、解压、逐行解析，把每个实体的原始 JSON 写入 MySQL；解析失败或缺少 id 的行单独记录下来。

**约束**

- N-02-1 **可并行**：多线程、多进程同时导入，同一个文件同一时间只由一个 worker 处理。
- N-02-2 **崩溃可恢复**：导入中途崩溃后再次运行，最终数据完整、不重复。
- N-02-3 **幂等**：同一个文件重复导入，不产生重复记录。
- N-02-4 **坏数据隔离**：单行坏数据不影响整个文件；坏行能定位到文件和行号，重复导入时不会重复记录。
- N-02-5 **远端故障可重试**：网络或远端出错时，文件退回待导入，之后重试。
- N-02-6 **原样保存**：原始 JSON 不做任何改写。
- N-02-7 **性能**：越快越好，没有硬性时限。

**产出**（T 侧的 F-04 到 F-07 都依赖这两条）

- N-02-8 **一个实体一行**：每个实体在 MySQL 里只有一行，按 `(platform, entity_type, entity_id)` 区分；存储结构与数据来源无关。
- N-02-9 **可判断新旧**：每次写入都会刷新 `updated_at`，下游据此判断新旧。用导入时间还是源数据自己的更新时间，仍在讨论。

**环境前提**

- ENV-OA-03 文件是 gzip 压缩的 JSON Lines，每个文件最多 40 万条 → 流式处理，不必把整个文件读进内存。
- ENV-OA-04 远端文件在登记之后可能变化（旧分区会缩小）→ 实际读到的行数少于登记的条数不算错误，交给 F-03 核对。
- ENV-DEP-01 开发机只有几十 GB 硬盘，所有组件部署在同一台机器上，而 authors 压缩后就有 82.3 GB → 开发环境完整导入 sources，authors 只导入一小部分供 T 侧使用。
- ENV-DEP-02 MySQL 8 自建，有 root 权限 → 导入时可以关闭 binlog，减少写放大。

**依赖**：F-01。**设计**：[worker 阶段设计](importer/docs/worker阶段设计.md)。

**状态**：主流程已实现，没有单元测试。已知问题：N-02-5 只覆盖了下载请求本身（连接失败、HTTP 状态码不是 200），下载到一半断网会把文件标成失败；退回待导入的文件没有重试次数上限和退避。

### F-03 对账与进度（`importer reconcile` / `status`）

**做什么**：核对一次导入的结果，查看导入进度。

**约束**（目前没有现行设计，以下取自其他文档和代码里提到的要求）

- N-03-1 **进度一览**：一条命令就能看到一次导入的整体进度。
- N-03-2 **条数核对**：找出实际读到的行数少于登记条数的文件。
- N-03-3 **坏行分诊**：F-02 记录的坏行由人工分诊。属于解析误判的，修正后重新导入；确实是源数据损坏的，标记为忽略并保留记录。

**环境前提**

- ENV-OA-04 远端文件在登记之后可能变化 → 条数不一致可能是远端变了，不一定是导入出错，核对时要能区分。
- ENV-OA-06 删除日志只覆盖 works，而 sources 在两次发布之间少了 26,306 条 → 只靠导入记录发现不了被删除的实体。

**依赖**：F-01、F-02。**设计**：暂无现行设计。`importer` 用法说明里的"三层对账→推水位""验收 A6"是早期设计留下的文字。

**状态**：未实现，执行时返回码为 2。坏行的三种状态（待分诊、已解决、已忽略）已在代码中定义。

### F-04 全量同步（`transformer`）

**做什么**：把 MySQL 里的实体分批读出，交给各个下游写入（F-05 ES、F-06 对象存储）。

**约束**

- N-04-1 **不漏**：MySQL 里的每个实体都会被同步到；分批之间不重叠、没有缝隙。
- N-04-2 **中断可续**：中断后重新运行，从没完成的批次继续，不用从头开始。
- N-04-3 **可暂停**：运行中可以暂停同步，恢复后继续。
- N-04-4 **至少一次**：一批数据在所有下游都写成功后才算完成；失败的批次整批重做，所以下游必须幂等（N-05-1、N-06-2）。
- N-04-5 **坏数据隔离**：单条数据转换或写入失败不影响整批，记录下来留给重处理。
- N-04-6 **可并行**：多线程同时同步。

**环境前提**

- ENV-DEP-01 开发机放不下全量 authors → 开发环境只同步 authors 的一个子集。

**依赖**：F-02 的产出（N-02-8、N-02-9）。**设计**：[全量同步设计](transformer/docs/全量同步设计.md)。

**状态**：分批（分片）已实现，有单元测试；调度、分发、暂停开关只有骨架；触发方式（命令还是请求）待定。transformer 目前编译不通过。

### F-05 authors 检索索引（Elasticsearch）

**做什么**：把 OpenAlex 的 authors 转换成检索文档写入 ES，供搜索服务使用：按人名（含别名和拼写变体）、国家、研究主题、最近任职机构、曾经任职机构（含任职年份）、各类 id 检索；按作品数、被引数、h 指数等指标排序和过滤。

**约束**

- N-05-1 **幂等**：重复同步不产生重复文档。
- N-05-2 **不倒退**：版本更旧的数据不能覆盖更新的数据，全量同步和增量同步同时写同一条数据也一样。
- N-05-3 **不静默失败**：字段名写错、index 名写错这类问题必须暴露出来，不能请求成功却丢了数据或写错了地方。
- N-05-4 **字段受控**：只写入设计里列出的字段，OpenAlex 新增的字段不会自动进入索引。
- N-05-5 **换结构不停服**：字段结构改了以后，可以建新 index 再切换过去，期间检索不中断。
- N-05-6 **坏数据隔离**：单条数据有问题时记入死信，不影响整批；ES 暂时繁忙或不可用时退避重试。

**环境前提**

- ENV-USE-01 下游是搜索服务，按人名、历史就职机构、影响力等维度检索 → 决定了上面这些检索字段和排序指标。
- ENV-OA-07 OpenAlex 的 h 指数基于自有引文图，覆盖率低于 Scopus 和 WoS → 只能用作同一索引内的相对排序，不能当作权威数值对外展示。
- ENV-DEP-03 transformer 使用的 ES Java 客户端是 9.4.5 → 服务端用 ES 9.x。
- ENV-DEP-04 ES 磁盘用量超过 95% 时会把索引设成只读，写入返回 429 → 按 N-05-6 会一直重试；开发机磁盘小，要盯住磁盘用量。

**依赖**：F-04；F-02 的产出（N-02-8 决定文档 id，N-02-9 决定版本号）。**设计**：[ES 文档对象设计](transformer/docs/ES文档对象设计.md)。

**状态**：转换器和 mapping 已实现，有单元测试。ES 写入进行中（未提交），还缺版本号、死信和响应分类；设计文档要求的幂等单测还没写；index 需要手工创建（见"构建与运行"）。

### F-06 训练数据导出（对象存储）

**做什么**：把原始 JSON 写入对象存储，供模型训练使用，不做格式转换。

**约束**

- N-06-1 **原样导出**：写出的就是 MySQL 里保存的原始 JSON。
- N-06-2 **幂等**：同一批数据重复写入，不产生重复文件。
- 待定：同一实体有多个版本时，使用方怎么取到最新的那个。

**环境前提**

- ENV-USE-02 只知道数据用于训练，格式要求未知 → 先按每行一个原始 JSON 输出，具体格式待确认。
- ENV-DEP-05 对象存储尚未选型，之前用的 MinIO 社区版已停止维护，官方不再发布二进制和镜像 → 需要另选，例如自行构建 MinIO、换其他 S3 兼容存储，或直接用阿里云 OSS。

**依赖**：F-04；F-02 的产出。**设计**：目前只有 `OssWriter` 注释里的一句：每批写一个文件，每行一个 JSON，文件名用该批的 id 区间来保证幂等。

**状态**：未开始。

### F-07 增量同步

**做什么**：OpenAlex 新的 release 导入之后，把已有实体的变化同步到 ES 和对象存储。

**约束**

- N-07-1 **不漏更新**：每一处变化最终都要传到下游。
- N-07-2 **幂等、不倒退**：与 N-05-1、N-05-2 相同。
- N-07-3 **可并行**：多个消费者同时处理。
- N-07-4 **中断可续**：同步进度（水位、时间）要记录下来，中断后从断点继续。

**环境前提**

- ENV-OA-05 最近一次发布有九成以上的记录更新 → 每次"增量"的数据量和全量是同一个量级。
- ENV-DEP-02 MySQL 自建，可以按会话关闭 binlog → 当前计划是首次全量导入关 binlog、之后的导入开 binlog，由 binlog 驱动下游。但按 ENV-OA-05，每次导入都要把几乎全部数据写一遍 binlog，这个方案需要重新评估。
- ENV-DEP-06 Kafka、Canal 尚未选型 → 需求里不绑定具体技术。

**依赖**：F-02（导入新的 release）；写入 F-05、F-06 的下游，遵守它们的约束。**设计**：[增量同步设计](transformer/docs/增量同步设计.md)，目前只有思路：canal + kafka + 多消费者。

**状态**：未开始，方案在讨论中。

### F-08 接入其他平台的人物画像（远期）

**做什么**：接入其他社交平台的人物画像数据。可能新建一个 EL 程序，也可能改造现有的 importer。

**约束**

- N-08-1 **扩展点在数据库**：新平台的数据写入同一张 `social_entity` 表，遵守 F-02 的产出约定（N-02-8、N-02-9）；不在数据拉取阶段为扩展做额外设计。

**环境前提**：目前没有这些平台的数据。

**依赖**：F-02 的产出约定。

**状态**：不在当前范围内。

## 全局约束

- G-01 **不允许静默失败**：任何失败都要能被发现（报错、日志或计数），不能"运行成功"却丢了数据或写错了地方。
- G-02 **连接信息可配置**：数据库等连接信息通过配置提供，仓库里的账号密码只是本地开发默认值。

## 范围外（目前不做）

- 处理 OpenAlex 删除或合并的实体（见 ENV-OA-06）。
- 搜索服务本身：本项目只负责把检索索引建好（F-05）。
- 其他平台的数据（F-08）。

## 环境前提索引

以下内容核实于 2026-10-05。OpenAlex 的相关说明见[快照数据格式](https://help.openalex.org/download-all-data/snapshot-data-format)和[同步](https://help.openalex.org/access/sync/)两篇文档；"manifest 实测"指 2026-09-23 发布的 manifest，以及 `importer/src/test/resources/test.txt` 里 2026-06-26 发布的 sources manifest。

| 编号 | 内容 | 来源 | 影响的功能 |
|---|---|---|---|
| ENV-OA-01 | 每个 entity 有一份 manifest，列出全部文件的 URL、条数和大小；URL 形如 `s3://openalex/data/jsonl/{entity}/updated_date=YYYY-MM-DD/part_NNNN.gz`，可通过 HTTPS 公开访问 | OpenAlex 文档；manifest 实测 | F-01 |
| ENV-OA-02 | 记录按最后一次变化的日期分区；记录更新后移到新分区，同一个 release 里每条记录只出现一次 | OpenAlex 文档 | F-01 |
| ENV-OA-03 | 文件是 gzip 压缩的 JSON Lines，每个文件最多 40 万条 | OpenAlex 文档 | F-02 |
| ENV-OA-04 | 远端文件在登记之后可能变化：sources 的 `2026-02-09` 分区在两次发布之间从 14,442 条缩到 9,290 条 | OpenAlex 文档；manifest 实测 | F-02、F-03 |
| ENV-OA-05 | 大约每季度发布一次；2026-09-23 的发布相对上一次，92.6% 的 sources、99.9% 的 authors 有更新（只观测了这一个季度） | OpenAlex 文档；manifest 实测 | F-01、F-07 |
| ENV-OA-06 | 删除日志只覆盖 works；sources 在两次发布之间少了 26,306 条 | OpenAlex 文档；manifest 实测 | F-03、范围外 |
| ENV-OA-07 | h 指数基于 OpenAlex 自有引文图，覆盖率低于 Scopus 和 WoS | ES 文档对象设计 | F-05 |
| ENV-DEP-01 | 开发机为阿里云 VPS：几十 GB 硬盘、16 GB 内存、8 核，所有组件同机。数据规模：sources 256,981 条、压缩后 0.33 GB；authors 132,148,629 条、压缩后 82.3 GB | 开发环境现状；manifest 实测 | F-01、F-02、F-04 |
| ENV-DEP-02 | MySQL 8.x 自建，有 root 权限 | 开发环境现状 | F-02、F-07 |
| ENV-DEP-03 | ES 9.x；transformer 经 Spring Boot 4.1.1 引入的 ES Java 客户端是 9.4.5 | 开发环境现状；`transformer/pom.xml` | F-05 |
| ENV-DEP-04 | ES 磁盘用量超过 95% 时索引变为只读，写入返回 429 | ES 的磁盘水位机制 | F-05 |
| ENV-DEP-05 | 对象存储未选型；MinIO 社区版已停止维护，官方不再发布二进制和镜像 | 开发环境现状；[公开报道](https://stormdevelopments.ca/blog/minio-s-community-edition-is-archived-what-still-runs-in-2026/) | F-06 |
| ENV-DEP-06 | Kafka、Canal 未选型 | 开发环境现状 | F-07 |
| ENV-USE-01 | ES 用于搜索服务，按人名、历史就职机构、影响力等维度检索 | 项目约定；ES 文档对象设计 | F-05 |
| ENV-USE-02 | 对象存储里的数据用于训练，具体格式要求未知 | 项目约定 | F-06 |

## 模块

| 模块 | 说明 |
|---|---|
| `common` | 两个模块共享的领域对象（目前只有 `SocialEntity`），零依赖，不引入任何框架 |
| `importer` | EL（F-01 到 F-03）。纯 Java 21 命令行程序（不用 Spring），打成一个包含全部依赖的 jar，子命令为 `plan` / `work` / `reconcile` / `status` |
| `transformer` | T（F-04 到 F-07）。Spring Boot 4.1.1 应用 |
| 根 `pom.xml` | 只做聚合（artifactId `openalex-sync`），不做父继承，因为 `transformer` 已经继承了 `spring-boot-starter-parent` |

## 构建与运行

需要 JDK 21、Maven 3.9 以上、MySQL 8.x。

### 建表

仓库里还没有建表脚本，需要手工建表。代码依赖下面这些表，其中的唯一键是幂等约束的基础，建表时必须带上。

| 表 | 读写方 | 用途 | 对应约束 |
|---|---|---|---|
| `sync_job` | importer | 一次导入计划（entity 和快照日期） | 不保证幂等（N-01-2） |
| `file_task` | importer | 计划里的每个远端文件，含状态、进度和心跳 | 唯一键 `(entity, date, part)`（N-01-2） |
| `social_entity` | importer 写，transformer 读 | 每个实体一行，原样保存原始 JSON | 唯一键 `(platform, entity_type, entity_id)`（N-02-8） |
| `dead_row` | importer | 导入时的坏行 | 唯一键 `(file_id, line_no)`（N-02-4） |
| `sync_task` | transformer | 全量同步的分批（id 区间） | 靠分批水位避免重复切分（N-04-1） |
| `dead_letter` | transformer | 同步时被跳过的坏数据 | 按 `social_entity_id` 去重（N-04-5）；实体类已建，还没有读写代码 |

注意状态值的大小写：`plan` 写入 `PENDING`，`work` 按 `pending` 查询，能匹配上是因为 MySQL 默认的排序规则（如 `utf8mb4_0900_ai_ci`）不区分大小写。建表时不要把这类列设成区分大小写（如 `utf8mb4_bin`），否则 `work` 永远领不到任务。

### importer

打包：

```bash
mvn -pl importer -am package -DskipTests
```

产物是 `importer/target/importer-0.1.0-SNAPSHOT.jar`，已包含全部依赖。打包时跳过测试，原因见下面的"测试"一节。

先登记一次导入计划（F-01），再执行导入（F-02）：

```bash
java -jar importer/target/importer-0.1.0-SNAPSHOT.jar plan --entity sources
```

```bash
java -jar importer/target/importer-0.1.0-SNAPSHOT.jar work --entity sources
```

- `--entity` 目前支持 `sources` 和 `authors`。
- 数据库连接默认是 `jdbc:mysql://localhost:3306/openalex`。可以用系统属性 `-Dopenalex.db.url`、`-Dopenalex.db.user`、`-Dopenalex.db.password`，或环境变量 `OPENALEX_DB_URL`、`OPENALEX_DB_USER`、`OPENALEX_DB_PASSWORD` 覆盖，系统属性优先。注意 `work` 目前只读取 URL 的配置，用户名和密码固定为 `JdbcConnections` 里的本地开发默认值，这一点还不满足 G-02。
- `work` 的每个数据库连接都会执行 `SET sql_log_bin = 0`（不写 binlog），数据库账号需要有相应权限。
- 返回码：`0` 成功；`1` 运行出错，包括参数不合法；`2` 子命令尚未实现；`64` 没有给出子命令或子命令不存在。
- 在 Git Bash 里中文输出会乱码，可以在 `-jar` 前加上 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。

### 测试

```bash
mvn -pl importer -am test '-Dtest=!PlannerTest#downloadManifest' -Dsurefire.failIfNoSpecifiedTests=false
```

这条命令运行 importer 的 13 个测试，不需要数据库：DAO 测试用 JDK 动态代理伪造 JDBC。被排除的 `PlannerTest#downloadManifest` 不做断言，它会联网下载当前的 sources manifest，并覆盖已提交的 `importer/src/test/resources/test.txt`（那是 2026-06-26 发布的真实 manifest）。

### transformer

目前编译不通过（`SocialEntityDAO` 和 `EsWriter` 里有未完成的代码），也还没有触发同步的入口；它的测试要等编译通过后才能运行。连接配置在 `transformer/src/main/resources/application.yml`。

ES 的 index 需要手工创建，应用不会自动建（N-05-3：应用自动建 index 时，index 名写错会悄悄建出一个新 index）。以 `transformer/src/main/resources/es/mapping/openalex_authors.json` 作为请求体创建物理 index `openalex_authors_v1`。文件里已经包含别名 `openalex_authors`，写入方只认这个别名（N-05-5）。建 index 时要显式指定 `number_of_shards`，创建后就不能再改。

## 文档

现有设计文档按模块存放：

| 文档 | 内容 | 对应功能 |
|---|---|---|
| [项目需求](importer/docs/项目需求.md) | 项目目标（早期版本） | 全部 |
| [项目架构](importer/docs/项目架构.md) | EL 三阶段，以及 T 的全量、增量划分 | 全部 |
| [数据库设计](importer/docs/数据库设计.md) | `sync_job`、`file_task` 的字段设计 | F-01 |
| [plan 阶段设计](importer/docs/plan阶段设计.md) | plan 的契约、不变量和取舍 | F-01 |
| [worker 阶段设计](importer/docs/worker阶段设计.md) | work 的契约、异常分类、崩溃处理，以及 `SocialEntity` 和死信表设计 | F-02 |
| [全量同步设计](transformer/docs/全量同步设计.md) | 分片、断点续传、编排和分发 | F-04 |
| [ES 文档对象设计](transformer/docs/ES文档对象设计.md) | `openalex_authors` 的字段、mapping、写入策略和转换器契约，含修订记录 | F-05 |
| [增量同步设计](transformer/docs/增量同步设计.md) | 增量同步的思路 | F-07 |

知识库正在建设：根目录的 `docs/`（需求总览、当前设计、设计取舍、存档点）、`openspec/specs/`（功能需求和验收标准），以及术语表。完成之前，以上模块文档就是设计依据。
