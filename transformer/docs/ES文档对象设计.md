## 需求

需要根据人名，国家，作品主题，最近任职单位，曾经任职单位，id，相关Id进行搜索匹配，

需要根据作品数，被引用数，指标字段，进行排序和过滤

updated at作为版本号

Entity id作为主键

---

## 设计

本节是 `openalex_authors` 这一个集合的字段规格。

上游约束来自 `全量同步设计.md`，本节在其留下的扩展点内展开，不改动任何契约、对象模型与关系模型：

- 「es的存储按照平台和entity type进行不同的index区分存储」→ index 命名与 bulk meta
- 「converter按照平台和entity type进行设计，每种集合设计一个」→ converter 契约与规范化规则
- 「幂等插入」「毒丸数据处理」→ `_id` 与版本策略、bulk 响应分类

index 名：`openalex_authors`（`{platform}_{entity_type}` 小写，由 converter 自报）。

### 需求覆盖对照

| 需求 | 落到哪些字段 |
|---|---|
| 人名 | `display_name`（+`.keyword`/`.prefix`）、`name_variants` |
| 国家 | `last_known_institutions.country_code`、`affiliations.country_code` |
| 作品主题 | `primary_topic.*`、`topics.*` |
| 最近任职单位 | `last_known_institutions.*`（object） |
| 曾经任职单位 | `affiliations.*`（nested） |
| id、相关 id | `entity_id`、`orcid`、`ids.*` |
| 作品数、被引用数、指标字段 | `works_count`、`cited_by_count`、`h_index`、`i10_index`、`mean_citedness_2y` |
| updated at 作为版本号 | bulk meta 的 `version` + `version_type: external_gte`；同时作文档字段 `updated_at` |
| Entity id 作为主键 | bulk meta 的 `_id`；同时作可检索字段 `entity_id` |

「国家」没有顶层字段：object 在 Lucene 层本就扁平，查 `*.country_code` 与查顶层字段的行为、性能完全一样。

### 文档字段 —— 标识

| 字段 | ES 类型 | raw 来源 | converter 动作 | 用途 |
|---|---|---|---|---|
| `entity_id` | keyword + `id_normalizer` | `id` | 剥 URL 前缀 → `A5053078380` | 精确检索、深分页 tiebreaker，同时作 `_id` |
| `orcid` | keyword + `id_normalizer` | `orcid` | 剥 `https://orcid.org/` | 精确检索 |
| `ids.mag` | keyword | `ids.mag` | — | 精确检索 |
| `ids.twitter` | keyword + `id_normalizer` | `ids.twitter` | — | 精确检索 |
| `ids.scopus` | keyword, `index:false` | `ids.scopus` | — | 仅返回 |
| `ids.wikipedia` | keyword, `index:false` | `ids.wikipedia` | — | 仅返回 |

`ids.openalex` 不要——和 `entity_id` 是同一个值。

ID 必须是 keyword 不能是 text：standard analyzer 会把 `0000-0001-6445-3672` 拆成 `[0000, 0001, 6445, 3672]`，完整 ORCID 反而匹配不上，而搜 `0000` 会命中海量文档。

剥 URL 前缀的理由不是省空间，是**一致性**：同一索引内若有的 id 带前缀、有的不带，调用方无法形成稳定预期。前缀在所有文档里相同，零区分度，且调用方不该被迫知道它。

### 文档字段 —— 人名

| 字段 | ES 类型 | raw 来源 | converter 动作 | 用途 |
|---|---|---|---|---|
| `display_name` | text (`name_analyzer`) | `display_name` | — | 主检索，boost 3 |
| `display_name.keyword` | keyword + normalizer | ↑ | — | 精确匹配、聚合 |
| `display_name.prefix` | search_as_you_type | ↑ | — | 自动补全 |
| `name_variants` | text (`name_analyzer`)，`norms:false`，从 `_source` 排除 | `display_name_alternatives` ∪ `raw_author_names` | 合并 → 按 lowercase 去重 → 剔除等于 `display_name` 的 → 截断 50 条 | 参与 matching，boost 1 |

`name_analyzer` = `lowercase` + `asciifolding`。

`display_name` 的两个 `.` 子字段是 multi-field——同一个值的不同索引视图，`_source` 只存一份，JSON 里仍只写 `display_name` 一个键。所以这里实际是 **2 个字段**，不是 4 个。

**为什么正名与变体必须分成两个字段**：`display_name` 需要 norms（正常的字段长度归一化），`name_variants` 需要 `norms: false`（变体多说明作者高产知名，长度惩罚方向是反的）。两批文本需要相反的打分处理，塞不进同一个字段；boost 也是字段级的，合并后无法区分正名命中与变体命中。另外 `_source` 的 excludes 是字段级的，合并后无法只排除变体、保留正名。

**为什么变体要存而不是靠 fuzzy**：变体是 `{姓前/名前} × {全名/缩写} × {ß/ss}` 的笛卡尔积。ß/ss 由 `asciifolding` 消化，姓名顺序由分词消化，只有「缩写」这一维 analyzer 和 fuzzy 都覆盖不了（`Nicola` ↔ `N` 编辑距离 5，超过 fuzziness 上限 2）。理论上只需存 1/9，但无法在转换期可靠判别某个变体属于哪一维（各国姓名规则不同），而全存成本接近零——倒排按 term 去重，9 条变体只产生 3 个 term。

**变体写数组不写拼接串**：ES 中任何字段天然可以是数组，text 字段会逐元素分析。数组元素间有 `position_increment_gap`（默认 100），拼接则会让相邻两个变体的边界 token 变成相邻，凭空造出 phrase 匹配；且变体本身含逗号，拼接后无法再拆回。

`display_name.prefix` 是全表唯一有实质索引开销的字段（内部生成 `._2gram`/`._3gram`/`._index_prefix`）。

fuzzy 的定位是 fallback，不作主路径：人名 term 短，`fuzziness: AUTO` 会捞进 nicole/nicolas 一类噪声，且千万级文档下 term 自动机展开开销大。主路径是 `asciifolding` + `name_variants` 的确定性匹配。

### 文档字段 —— 机构（最近任职，object，不 nested）

| 字段 | ES 类型 | raw 来源 | converter 动作 |
|---|---|---|---|
| `last_known_institutions.id` | keyword + `id_normalizer` | `last_known_institutions[].id` | 剥前缀 |
| `last_known_institutions.display_name` | keyword + `.text` 子字段 | ↑`.display_name` | — |
| `last_known_institutions.country_code` | keyword | ↑`.country_code` | — |
| `last_known_institutions.type` | keyword | ↑`.type` | — |
| `last_known_institutions.lineage` | keyword 数组 + `id_normalizer` | ↑`.lineage` | 剥前缀 |

该数组通常只有 1–2 个元素，cross-object matching 风险可忽略，用普通 object。

`lineage` 留下的理由是**不可替代**：没有它做不了「某大学体系下所有作者」，其余字段都推不出机构层级。

`display_name` 给 `.text` 子字段，因为机构名用户确实会自由输入（如「搜北大」），与主题名这种纯受控词表不同。

注意 `last_known_institutions` 是「最近已知的机构（可能多个）」，不是「历史机构」；它与 `affiliations` 的关系是「最近」对「全部历史」。

### 文档字段 —— 机构（曾经任职，`type: nested`）

| 字段 | ES 类型 | raw 来源 | converter 动作 |
|---|---|---|---|
| `affiliations.id` | keyword + `id_normalizer` | `affiliations[].institution.id` | 剥前缀 |
| `affiliations.display_name` | keyword + `.text` 子字段 | ↑`.display_name` | — |
| `affiliations.country_code` | keyword | ↑`.country_code` | — |
| `affiliations.type` | keyword | ↑`.type` | — |
| `affiliations.years` | short 数组 | `affiliations[].years` | 同机构合并后取并集、排序 |

整表按 institution id 去重（同机构多段年份合并）、截断 100 条（按最大年份降序保留）。不放 `lineage`、不放 `ror`。

`years` 保持数组，不拆成 `year_start`/`year_end`——任职年份有断档（2010–2012 在 A，2018–2020 又回到 A），区间会凭空造出错误事实。多值字段上的 `range` 查询语义正好是「任一年份落在区间内」。

**必须 nested 的原因**：`institution ↔ years` 是元素内关联，普通 object 会 cross-object matching（此人 2020 年在 A、2015 年在 B，查「2015 年在 A」会误命中）。代价是 Lucene 文档数约 `作者数 × 4`（人均 affiliations 均值 2–4，不是少数高产作者的 20–50）。`_source` 不受影响，只在根文档存一份。

若只需「曾经在 X 工作过」而不需年份关联，可退化为一个扁平的 `keyword` 数组（机构 id 去重），无文档膨胀。本设计选择保留年份能力。

查询侧注意：聚合必须 `nested` + `reverse_nested`，否则统计出的是任职记录数而非作者数；需要知道命中哪一段经历时用 `inner_hits`。

### 文档字段 —— 主题

| 字段 | ES 类型 | raw 来源 | converter 动作 |
|---|---|---|---|
| `primary_topic.id` | keyword + `id_normalizer` | `topics[0].id` | 剥前缀 |
| `primary_topic.display_name` | keyword + `.text` 子字段 | `topics[0].display_name` | — |
| `primary_topic.subfield` / `.field` / `.domain` | keyword | `topics[0].{subfield,field,domain}.display_name` | 三层对象压平成标量 |
| `topics.id` | keyword + `id_normalizer` | `topics[].id` | 剥前缀 |
| `topics.display_name` | keyword | `topics[].display_name` | — |
| `topics.subfield` / `.field` / `.domain` | keyword | ↑同上 | 压平 |

不存 `count`——元素内没有需要关联的数值，普通 object 即可，避开 nested。代价是做不了「在某非主方向主题上作品数 > N」，用 `primary_topic` + `works_count` 近似覆盖。`topics` 为空数组时 `primary_topic` 整个省略。

主题名是受控词表（约 4500 topic / 252 subfield / 26 field / 4 domain），所以主字段用 keyword 而非 text：text 会让「搜 Health」命中 Public Health、Health Policy 等无关主题，且无 doc_values 做不了 facet 聚合（开 `fielddata` 会打爆堆内存）。需要模糊搜时用 `.text` 子字段，方向是**主 keyword、副 text**——ES 动态映射的默认恰好相反，这是必须显式写 mapping 的原因之一。

层次字段冗余但要全存：否则查「所有 Psychology 领域的作者」需在查询期把 field 展开成几百个 topic id。倒排按 term 去重，26 个 field 值在全索引里只有 26 个 term。

每层同时存 id 与 display_name：过滤用 id（稳定），展示与聚合用 display_name（可能被 OpenAlex 改名）。

### 文档字段 —— 指标

| 字段 | ES 类型 | raw 来源 | 测的是什么 | 用途 |
|---|---|---|---|---|
| `works_count` | integer | `works_count` | 总产量 | 过滤、排序 |
| `cited_by_count` | integer | `cited_by_count` | 总被引，受单篇爆款主导 | 过滤、排序 |
| `h_index` | integer | `summary_stats.h_index` | 产量 × 影响力，抗单篇爆款 | 重名消歧主排序 |
| `i10_index` | integer | `summary_stats.i10_index` | 被引 ≥10 的论文数，补 h 指数小样本下的区分度 | 排序 |
| `mean_citedness_2y` | float | `summary_stats.2yr_mean_citedness` | 唯一有时间维度的：近两年作品的当年均引 | 排序 |

`summary_stats` 缺失时三个字段一起省略。字段名不跟 raw（`2yr_` 数字开头，Java 变量名非法；EsDoc 的字段名本就不必是 raw 的镜像）。

四个指标都留的理由：它们在重名消歧场景下会给出**相反的排序**。同名三人 A（引 1192 / 作 44 / h21）、B（引 8000 / 作 6 / h3）、C（引 900 / 作 200 / h5），按 `cited_by_count` 排 B 第一（千人合作论文的挂名），按 `works_count` 排 C 第一（高产无引），按 `h_index` 排 A 第一——A 才是要找的人。

OpenAlex 的 h 指数基于自有引文图，覆盖率低于 Scopus / WoS，绝对值偏低，不可作权威数值对外展示；作为同索引内的相对排序信号无问题。

排序还需一条 tiebreaker：按 `cited_by_count` 等排序时并列值海量，须以 `entity_id` 作第二排序键并配 `search_after`，否则深翻页会重复与丢数据。`from`/`size` 深分页不可用。

### 文档字段 —— 元数据

| 字段 | ES 类型 | raw 来源 | 用途 |
|---|---|---|---|
| `updated_at` | date | `social_entity.updated_at` | 对账、按更新时间排序 |

与 bulk meta 里的 `version` 同源但身份不同：`version` 是控制参数、不进 `_source`，此字段是文档内容。两处都要。

`SocialEntity` 的构造函数不含 `updatedAt`，`SocialEntityDAO.selectBatch` 需用 setter 补上，否则版本号取不到。

### bulk meta（不是文档字段）

bulk 报文中每个操作占两行，第一行是 meta（控制参数），第二行是 document source。meta 不进 `_source`、不进倒排索引。

| 项 | 值 | 作用 |
|---|---|---|
| `_index` | `openalex_authors` | `platform` + `entity_type` 全在这里，一个字都不进文档 |
| `_id` | `doc.entityId()` | 幂等落点，重跑不产生重复 |
| `version` | `se.getUpdatedAt().toEpochMilli()` | 防全量与增量互相覆盖 |
| `version_type` | `external_gte` | `gte` 而非 `external`：`updated_at` 秒级精度下同秒双写不会被误拒 |
| `op_type` | `index` | upsert 语义。`create` 会让第二次全量全部 409，且与外部版本控制互斥 |

四项均无服务端默认值可依赖，必须逐 operation 指定。SyncTask 分片按 `social_entity.id` 区间切分，一批内可能混有不同 `(platform, entity_type)`，故 `_index` 也要逐 operation 带——混合批次仍可合并进同一个 BulkRequest，不必按 index 拆多次请求。

**`_id` 省略是静默故障**：ES 会自动生成随机 UUID 且不报错，重跑一次全量数据翻倍，全程 HTTP 200。必须有幂等性单测（同一批数据 `write()` 两次，断言文档数与内容不变）兜住。

`_id` 不是可映射字段，永远精确、大小写敏感——按 `_id` 做 GET 用原始大小写，按字段做 search 走 `id_normalizer`，两条路径行为不同。

### bulk 响应分类

bulk 即使有 item 失败，HTTP 状态码仍是 200，失败信息在响应体内。须先 `resp.errors()` 判断，再遍历 `resp.items()` 逐项分类。`items()` 顺序与请求中操作顺序严格一致，这是同序列表 `srcs[i] ↔ docs[i] ↔ items[i]` 成立的基础。

| 状态 | 错误类型 | 含义 | 处理 |
|---|---|---|---|
| 409 | `version_conflict_engine_exception` | ES 中已有更新版本 | **正常跳过**，计数，不写死信 |
| 400 | `mapper_parsing_exception`、`strict_dynamic_mapping_exception` | 数据或 converter 问题 | 写 DeadLetter（Poisoned） |
| 429 | `es_rejected_execution_exception` | 集群背压 | 退避重试（Retryable） |
| 503 | `unavailable_shards_exception` 等 | 集群不可用 | 退避重试（Retryable） |

409 是版本机制正常工作的信号，不是故障：全量线程持有的旧快照被增量写入的新版本挡下，正是设计意图。此分类与 importer 的 Retryable / Poisoned / Fatal 三分类保持一致。

### converter 契约

`AuthorDoc convert(SocialEntity)`，纯函数、无副作用、可单测。输出用 `record`，不可变——Scheduler 是 16 线程并发。

converter 是**显式投影**而非 raw 透传——OpenAlex 新增字段传不到 ES，故根上的 `dynamic: strict` 是安全的；它的作用是兜住 converter 自身的字段名拼写错误（立即 400 而非静默多出一个字段）。嵌套对象同理用全字段 `record`（如 `Institution`）而非 `JsonNode`，保持这一性质。

规范化规则（每条对应一个单测）：

1. 所有 id（`entity_id` / `orcid` / 机构 id / `lineage` / topic id）剥 URL 前缀
2. `name_variants`：两源合并 → 按 lowercase 去重（写入保留首次出现的原始大小写）→ 剔除 lowercase 等于 `display_name` 的 → 截断 50 条
3. `topics`：三层对象压平成标量、去掉 `count`；`primary_topic` 取 `topics[0]`，空数组时整字段省略
4. `affiliations`：按 institution id 去重、`years` 取并集排序、截断 100 条
5. `summary_stats` 缺失时三个指标字段一起省略
6. 所有缺失字段一律省略，不写 `null`（`exists` 查询天然可用）

规约只做剥前缀与去重，**不在写入端做 analyzer 的工作**（lowercase / asciifolding / 分词）。理由有二：一是 Java 端复刻 Lucene 的 `ASCIIFoldingFilter` 极易出偏差——最直觉的 `NFD + 去变音符号` 写法处理不了 `ß`、`ø`、`æ`，而这些在作者名里高频，两端不一致会静默漏匹配且不报错；二是规约不可逆，把分析规则烧进历史数据后，想调 analyzer 只能重跑管道，丧失 reindex 的余地。

死信映射不进文档：EsWriter 内部维护一对严格同序的 `List<SocialEntity> srcs` 与 `List<AuthorDoc> docs`，转换失败的元素两个列表都不进（当场落 DeadLetter），配对关系不会错位。`social_entity` 的自然键 `(platform, entity_type, entity_id)` 已由 index 名 + `entity_id` 完整承载，代理键 `social_entity.id` 无需进文档——它是内部实现细节，且源表重建后会变成误导性数据。

无对应 converter 的 `(platform, entity_type)`：显式配置本次同步支持的白名单，不在白名单内的按正常路径跳过；在白名单内却找不到 converter 的抛 Fatal（配置错误，不进死信表）。

### 修订 2026-09-06：converter 输出改为 `Map<String,Object>`

上一节原文一字不动保留，作为推导轨迹。自本节起，converter 契约以此节为准。

**新契约**：`Map<String,Object> convert(SocialEntity)`。仍为纯函数、无副作用、可单测。

#### 原理由中经复核不成立的部分

| 原文/相关理由 | 复核结论 |
|---|---|
| 「输出用 `record`，不可变——Scheduler 是 16 线程并发」 | **不成立**。converter 的输出在单个线程内创建、当场序列化、不跨线程共享，不可变性在这条路径上换不到任何实际的并发安全。 |
| 「record 能在编译期挡住字段名拼写错误」（原文未写，为免日后重议一并记录） | **只在特定前提下成立**。record 组件名是 camelCase，JSON key 是 snake_case，只有配 snake_case 命名策略、不写 `@JsonProperty` 时才构成编译期检查；一旦逐字段写 `@JsonProperty("entity_id")`，该字符串与 `map.put("entity_id", …)` 是同一种风险。 |

#### 改用 Map 的理由

1. **key 所见即所得**。`put` 什么，`_source` 里就是什么。没有命名策略这层间接，也不必确认 `JacksonJsonpMapper` 内部用的是哪个 `ObjectMapper`、有没有配 snake_case。
2. **「缺失字段一律省略」表达更直接**。规范化规则第 6 条在 Map 里就是「不 put」，不依赖 `@JsonInclude(NON_NULL)` 生效。
3. 省掉 `AuthorDoc` / `Ids` / `Institution` / `Affiliation` / `Topic` 五个类型的编写与维护。

#### 不随之放松的部分（仍是契约）

**「显式投影而非 raw 透传」不变。** 换 Map 只改变载体，不改变投影语义——这是根上 `dynamic: strict` 能成立的前提。

Map 的 value **只允许**以下类型：

| 目标字段形态 | value 类型 |
|---|---|
| 标量字符串 | `String` |
| 标量数值 | `Integer` / `Float`（不用基本类型；缺失时不 put） |
| 日期（`updated_at`） | ISO-8601 `String`；或确认 `JacksonJsonpMapper` 已注册 `JavaTimeModule` 后用 `Instant` |
| 字符串数组（`name_variants`、`lineage`） | `List<String>` |
| 年份数组（`affiliations[].years`） | `List<Short>` |
| 单个子对象（`ids`、`primary_topic`） | `Map<String,Object>` |
| 对象数组（`last_known_institutions`、`affiliations`、`topics`） | `List<Map<String,Object>>` |

**明确禁止**两种 value：

- **`JsonNode`（含 `ObjectNode` / `ArrayNode`）**——等于在子对象层把 raw 透传放回来。已逐字段核对：本设计中**没有任何一个字段的 raw 子树形状等于目标形状**。`ids` 多出 `openalex` / `orcid`（strict 下报错），机构 id 带 URL 前缀且多出 `ror`，`affiliations` 的 raw 形状是 `[{institution:{…}, years}]`，`topics` 是三层对象且带 `count`。且 `ObjectNode` 可变。
- **承载 JSON 的 `String`**（如 `SocialEntity.getData()` 的原文）——会被序列化成 JSON 字符串字面量而非对象。strict 下该项 400；非 strict 下被静默索引成 `text`，mapping 定死后只能 reindex。

规范化规则 1–6 全部不变，只是作用在 Map 的构造过程上。

#### 代价与强制补偿

丢掉的是编译器对字段名与类型的兜底。失败模式是**静默的**：key 拼错 → `strict_dynamic_mapping_exception` → 按上文 bulk 响应分类归 Poisoned → 每一条都进死信表，而 HTTP 全程 200、不抛异常，跑完千万级才从计数器上察觉。

**故新增一条必过单测，与幂等性单测同级，不是可选项**：

> 取一条样本 `SocialEntity`，`convert` 后断言 `doc.keySet()` 等于预期 key 全集（子对象逐层断言），并断言每个 value 的运行时类型落在上表允许范围内。

这条单测是本次改动的**前提条件**而非后续优化——没有它，Map 方案不具备任何字段名保障。

#### 连带影响

| 位置 | 原文 | 改为 |
|---|---|---|
| `### bulk meta` 的 `_id` 一行 | `doc.entityId()` | `doc.get("entity_id")` |
| 上一节死信同序列表 | `List<AuthorDoc> docs` | `List<Map<String,Object>> docs` |

其余各节——字段规格、ES 类型、mapping、bulk meta 四项、响应分类、不入索引清单、建 index 决定——**均不受影响**：它们描述的是索引与报文，与 Java 侧载体无关。

### 明确不入索引的（记录决定，免得以后重议）

| 字段 | 原因 |
|---|---|
| `platform` / `entity_type` | index 名已承载，要过滤用 `_index` 元字段 |
| `social_entity_id` | 自然键已完整，代理键无新信息；死信映射走 EsWriter 内存中的同序列表 |
| `country_codes` 顶层字段 | object 在 Lucene 层本就扁平，查 `*.country_code` 行为完全一样 |
| `ror` | 推不出来但暂无场景。**触发条件**：需与 Crossref / 资助机构库对接时再加 |
| `x_concepts` | OpenAlex 已废弃，被 `topics` 取代 |
| `topic_share` | 与 `topics` 高度重合 |
| `counts_by_year` | 数组，入索引要面对 nested，收益低 |
| `works_api_url` | 零检索价值 |
| `last_known_institution`（单数） | 已被复数数组取代 |
| raw 原文 | OSS 已是 raw 的归宿，ES 再存是第三份；需要回源时按 `entity_id` 查 MySQL |

取舍原则：**能从别的字段推出来 → 不留；推不出来且对应真实场景 → 留；推不出来但暂无场景 → 先不留**。第三条的依据是 raw 在 MySQL 与 OSS 中均在，重跑管道随时可加回——加的代价是一次性的，留着的代价是持续的。

### 建 index 的一次性决定

- **alias + 物理 index**：`openalex_authors`（alias）→ `openalex_authors_v1`（实体）。mapping 改不动、必须 reindex 是常态，alias 让切换不停服。此项在建 index 当时不做，日后代价翻倍。
- **`dynamic: strict`**：raw 中 `ids` / `summary_stats` / `counts_by_year` 一旦被动态映射会炸字段数；strict 还能让 converter 写错字段名时立即报错。
- **`id_normalizer`** = `lowercase`。normalizer 是 keyword 版的 analyzer，索引期与查询期都会应用，两端自动对齐。
- `name_variants` 从 `_source` 排除后无法靠 reindex 重建（reindex 读 `_source`），改 mapping 需重跑管道——与「raw 不入 ES、需要就重跑管道」的取舍一致。
- keyword 的 `ignore_above` 是静默失败：超长值直接不被索引且不报错。ID 类字段要么不写此参数，要么给宽松值。

字段存储的四个开关相互正交，本设计各字段按此组合：`index`（倒排，决定能否 matching）、`doc_values`（列存，决定能否排序聚合）、`_source`（决定能否返回）、`store`（一般不用）。`name_variants` 是「index 开、`_source` 排除」，`ids.scopus` 是「index 关、`_source` 留」。


### 修订 2026-09-06：新增 `name_normalizer`，及建 index 时一并确定的项

上一节原文保留。本节补齐上一节留下的空缺，并记录首次建 index 时定下的若干项。

#### 新增 `name_normalizer`

上一节只定义了 `id_normalizer`（lowercase），而「文档字段 —— 人名」一节中 `display_name.keyword` 写的是「keyword + normalizer」，未指明是哪一个。

**定为**：`name_normalizer` = `lowercase` + `asciifolding`，filter 链与 `name_analyzer` 一致。`display_name.keyword` 用它，不复用 `id_normalizer`。

理由：**同一个值的两个索引视图行为必须一致**。`display_name` 走 `name_analyzer` 已做 asciifolding——主检索输 `Nicola` 能搜到 `Nicolá Jones`；若 `.keyword` 只 lowercase，同一字段上就会出现「模糊搜能中、精确匹配反而不中」。且重名消歧需在 `.keyword` 上做 terms 聚合找出同名作者，不 fold 会把 `Müller` 与 `Muller` 分成两个桶——二者本就同名，应合并。

normalizer 只作用于倒排索引中的 term，不影响 `_source`；返回给调用方的仍是原始拼写。

沿用 `id_normalizer` 的字段不变：`entity_id`、`orcid`、`ids.twitter`、各机构 `id`、`lineage`、topic `id`。ID 不做 asciifolding——本就不含变音符，folding 无作用，保持最小定义。

#### 建 index 时一并确定的项

| 项 | 决定 | 理由 |
|---|---|---|
| 机构与 `primary_topic` 的 `display_name.text` 子字段用哪个 analyzer | `name_analyzer` | lowercase + asciifolding 是通用文本处理，机构名同样有变音符（`Université`、`Zürich`），不必另建一个 |
| `display_name.prefix` 的 analyzer | `name_analyzer` | 与主字段对齐；否则自动补全吃不了变音符，与主检索行为不一致 |
| `subfield` / `field` / `domain` / `topics.display_name` 是否加 normalizer | 不加 | 它们只用于聚合与展示，过滤走 id（见「文档字段 —— 主题」末段），聚合不需要 normalizer |
| keyword 的 `ignore_above` | 一律不写 | 上一节已定性为静默失败；显式 mapping 的 keyword 默认无此参数，动态映射模板的 256 默认值不适用 |

#### 建 index 执行方式（不进 Java 主流程）

mapping 文件位置：`transformer/src/main/resources/es/mapping/openalex_authors.json`，按 `{platform}_{entity_type}.json` 命名，与 index 名一致。

文件进仓库的理由：它是本集合 schema 的**唯一声明**（converter 侧已无 Java 类型，见「修订 2026-09-06：converter 输出改为 `Map<String,Object>`」）；一致性单测要读它；mapping 变更需可 diff、可追溯，配合 alias v1→v2 的 reindex 流程。

但**建 index 的动作不由 Scheduler / EsWriter 在运行时执行**，一次性手工执行或走独立运维入口。三条理由：

1. 16 线程并发，创建时序不确定，会互相撞。
2. shard 数、replica 数、`refresh_interval` 是部署参数，不该由数据写入程序决定。
3. **最关键**：应用若能自动建 index，则 index 名打错时会静默建出一个新 index 而非报错——与「`_id` 省略是静默故障」同类，全程 HTTP 200，数据落进无人知晓的 index。

`aliases` 写在建 index 请求体内（本文件已含），使上一节「alias 在建 index 当时不做、日后代价翻倍」不可能被遗漏。

**`number_of_shards` 不写进本文件**：它属部署参数，但**建 index 后不可更改**，须在执行建 index 时按数据规模显式决定，不可依赖 ES 默认值。
### 待定项

| 项 | 现状 | 触发条件 |
|---|---|---|
| `display_name.prefix` | 已列入 | 若自动补全非必需可砍（全表最大一笔索引开销）；量大时考虑独立轻量补全索引（仅 id + 名字），不挂主索引 |
| `ror` | 不入 | 需与外部机构库对接时加入 |
| `topics.count` | 不入 | 确有「在某非主方向主题上作品数 > N」需求时加入，届时 `topics` 需转 nested |
| 「按作品检索作者」 | 不在本 index | author 对象无作品列表，且 converter 是单行纯函数无法 join。需独立 `openalex_works` index + 应用层两段式查询 |
| 「按机构本体检索」 | 不在本 index | 需独立 `openalex_institutions` index |

---

## 实现
