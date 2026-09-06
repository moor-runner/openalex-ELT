package com.transformer.converter;

import com.common.entity.SocialEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * openalex / authors 集合的 converter，产出 index {@code openalex_authors} 的文档。
 * <p>
 * 字段规格见 {@code docs/ES文档对象设计.md}；本类实现该文档「converter 契约」一节的规范化规则 1–6。
 * 规约只做<b>剥前缀与去重</b>，不在写入端做 analyzer 的工作（lowercase / asciifolding / 分词）——
 * 一是 Java 端复刻 Lucene 的 ASCIIFoldingFilter 极易在 ß / ø / æ 上出偏差且不报错，
 * 二是规约不可逆，把分析规则烧进历史数据后想调 analyzer 只能重跑管道。
 * <p>
 * 唯一的例外是 {@link #lower(String)}，它只用于<b>去重时的比较</b>，不写进输出。
 */
@Component
public class AuthorConverter implements Converter {

    /** ObjectMapper 的读取是线程安全的，Scheduler 16 线程共用一个实例即可。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 规则 2：变体截断上限。 */
    private static final int NAME_VARIANTS_LIMIT = 50;

    /** 规则 4：任职经历截断上限。 */
    private static final int AFFILIATIONS_LIMIT = 100;

    @Override
    public String platform() {
        return "openalex";
    }

    @Override
    public String entityType() {
        return "authors";
    }

    @Override
    public Map<String, Object> convert(SocialEntity se) {
        JsonNode raw = parse(se.getData());

        // LinkedHashMap 而非 HashMap：key 顺序稳定，_source 可读，单测 diff 好看
        Map<String, Object> doc = new LinkedHashMap<>();

        // ---- 标识 ----
        putIfPresent(doc, "entity_id", stripUrlPrefix(text(raw, "id")));
        putIfPresent(doc, "orcid", stripUrlPrefix(text(raw, "orcid")));
        putIfNotEmpty(doc, "ids", ids(raw.path("ids")));

        // ---- 人名 ----
        putIfPresent(doc, "display_name", text(raw, "display_name"));
        putIfNotEmpty(doc, "name_variants", nameVariants(raw));

        // ---- 机构 ----
        putIfNotEmpty(doc, "last_known_institutions", lastKnownInstitutions(raw));
        putIfNotEmpty(doc, "affiliations", affiliations(raw));

        // ---- 主题 ----
        // 规则 3：primary_topic 取 topics[0]；topics 为空数组时两个字段一起省略
        List<Map<String, Object>> topics = topics(raw);
        if (!topics.isEmpty()) {
            doc.put("primary_topic", topics.get(0));
            doc.put("topics", topics);
        }

        // ---- 指标 ----
        putIfPresent(doc, "works_count", integer(raw, "works_count"));
        putIfPresent(doc, "cited_by_count", integer(raw, "cited_by_count"));
        summaryStats(raw, doc);

        // ---- 元数据 ----
        // 值来自 social_entity 而非 raw。为 null 说明 SocialEntityDAO.selectBatch 没用 setter 补
        // updatedAt（SocialEntity 的构造函数不含该字段）——那么 bulk meta 的 version 同样取不到，
        // 属上游缺陷而非数据缺失，须由 EsWriter 在组装报文时发现
        if (se.getUpdatedAt() != null) {
            // 直接给 ISO-8601 字符串，绕开「JacksonJsonpMapper 内部那个 ObjectMapper 是否注册了
            // JavaTimeModule」的不确定性——没注册的话 Instant 会变成 epoch 数字或直接抛异常
            doc.put("updated_at", se.getUpdatedAt().toString());
        }

        return doc;
    }

    // ---------------------------------------------------------------- 分段构造

    /**
     * {@code ids.openalex} 与 {@code ids.orcid} 不取：前者和 entity_id 同值，后者已是顶层字段。
     * 两者都未在 mapping 中声明，写进去就是 strict_dynamic_mapping_exception。
     * <p>
     * scopus / wikipedia 本身是带路径的 URL（{@code index: false}，仅返回），不能剥前缀。
     */
    private Map<String, Object> ids(JsonNode ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "mag", text(ids, "mag"));
        putIfPresent(m, "twitter", text(ids, "twitter"));
        putIfPresent(m, "scopus", text(ids, "scopus"));
        putIfPresent(m, "wikipedia", text(ids, "wikipedia"));
        return unmodifiable(m);
    }

    /**
     * 规则 2：{@code display_name_alternatives} ∪ {@code raw_author_names}
     * → 按 lowercase 去重（写入保留首次出现的原始大小写）→ 剔除 lowercase 等于 display_name 的
     * → 截断 50 条。
     * <p>
     * 写数组不写拼接串：数组元素间有 position_increment_gap，拼接会让相邻变体的边界 token
     * 变成相邻，凭空造出 phrase 匹配；且变体本身含逗号，拼接后拆不回来。
     */
    private List<String> nameVariants(JsonNode raw) {
        String displayNameLower = lower(text(raw, "display_name"));
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();

        for (JsonNode source : List.of(raw.path("display_name_alternatives"), raw.path("raw_author_names"))) {
            // path() 缺失时返回 MissingNode，迭代零次，无需判空
            for (JsonNode node : source) {
                String v = node.isTextual() ? node.asText() : null;
                if (v == null || v.isBlank()) {
                    continue;
                }
                String key = lower(v);
                if (key.equals(displayNameLower) || !seen.add(key)) {
                    continue;
                }
                out.add(v);
                if (out.size() == NAME_VARIANTS_LIMIT) {
                    return out;
                }
            }
        }
        return out;
    }

    /** 最近已知机构（可能多个），普通 object；带 lineage，用于「某大学体系下所有作者」。 */
    private List<Map<String, Object>> lastKnownInstitutions(JsonNode raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode inst : raw.path("last_known_institutions")) {
            Map<String, Object> m = new LinkedHashMap<>();
            putIfPresent(m, "id", stripUrlPrefix(text(inst, "id")));
            putIfPresent(m, "display_name", text(inst, "display_name"));
            putIfPresent(m, "country_code", text(inst, "country_code"));
            putIfPresent(m, "type", text(inst, "type"));
            putIfNotEmpty(m, "lineage", strippedIdArray(inst.path("lineage")));
            if (!m.isEmpty()) {
                out.add(unmodifiable(m));
            }
        }
        return out;
    }

    /**
     * 规则 4：按 institution id 去重（同机构多段年份合并取并集、升序）、截断 100 条（按最大年份降序保留）。
     * <p>
     * years 保持数组不拆成 year_start / year_end——任职年份有断档（2010–2012 在 A，2018–2020 又回 A），
     * 区间会凭空造出错误事实。
     * <p>
     * 不放 lineage、不放 ror（见文档「明确不入索引的」）。
     */
    private List<Map<String, Object>> affiliations(JsonNode raw) {
        Map<String, JsonNode> institutionById = new LinkedHashMap<>();
        Map<String, TreeSet<Short>> yearsById = new LinkedHashMap<>();

        for (JsonNode affiliation : raw.path("affiliations")) {
            JsonNode institution = affiliation.path("institution");
            String id = stripUrlPrefix(text(institution, "id"));
            if (id == null || id.isBlank()) {
                // 没有 institution id 就无法去重、也无法被检索，整条丢弃
                continue;
            }
            institutionById.putIfAbsent(id, institution);
            TreeSet<Short> years = yearsById.computeIfAbsent(id, k -> new TreeSet<>());
            for (JsonNode year : affiliation.path("years")) {
                if (year.isNumber()) {
                    years.add((short) year.asInt());
                }
            }
        }

        return institutionById.keySet().stream()
                .sorted(Comparator.comparing((String id) -> latestYear(yearsById.get(id))).reversed())
                .limit(AFFILIATIONS_LIMIT)
                .map(id -> affiliation(id, institutionById.get(id), yearsById.get(id)))
                .toList();
    }

    private Map<String, Object> affiliation(String id, JsonNode institution, TreeSet<Short> years) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        putIfPresent(m, "display_name", text(institution, "display_name"));
        putIfPresent(m, "country_code", text(institution, "country_code"));
        putIfPresent(m, "type", text(institution, "type"));
        putIfNotEmpty(m, "years", List.copyOf(years));
        return unmodifiable(m);
    }

    /** 无年份的排在最后：截断按最大年份降序保留，缺年份的信息量最低。 */
    private static short latestYear(TreeSet<Short> years) {
        return years.isEmpty() ? Short.MIN_VALUE : years.last();
    }

    /**
     * 规则 3：三层对象压平成标量、去掉 count。
     * <p>
     * 不存 count——元素内没有需要关联的数值，普通 object 即可，避开 nested。
     */
    private List<Map<String, Object>> topics(JsonNode raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode topic : raw.path("topics")) {
            Map<String, Object> m = new LinkedHashMap<>();
            putIfPresent(m, "id", stripUrlPrefix(text(topic, "id")));
            putIfPresent(m, "display_name", text(topic, "display_name"));
            putIfPresent(m, "subfield", text(topic.path("subfield"), "display_name"));
            putIfPresent(m, "field", text(topic.path("field"), "display_name"));
            putIfPresent(m, "domain", text(topic.path("domain"), "display_name"));
            if (!m.isEmpty()) {
                out.add(unmodifiable(m));
            }
        }
        return out;
    }

    /**
     * 规则 5：{@code summary_stats} 缺失时三个字段一起省略。
     * <p>
     * 字段名不跟 raw：{@code 2yr_mean_citedness} 数字开头，且文档字段名本就不必是 raw 的镜像。
     */
    private void summaryStats(JsonNode raw, Map<String, Object> doc) {
        JsonNode stats = raw.path("summary_stats");
        if (!stats.isObject()) {
            return;
        }
        putIfPresent(doc, "h_index", integer(stats, "h_index"));
        putIfPresent(doc, "i10_index", integer(stats, "i10_index"));
        putIfPresent(doc, "mean_citedness_2y", floating(stats, "2yr_mean_citedness"));
    }

    // ---------------------------------------------------------------- 通用工具

    private static JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("social_entity.data 为空，无法转换");
        }
        try {
            return MAPPER.readTree(rawJson);
        } catch (JsonProcessingException e) {
            // 数据问题，重试无意义：调用方按 Poisoned 落 DeadLetter
            throw new IllegalArgumentException("social_entity.data 不是合法 JSON", e);
        }
    }

    /**
     * 规则 1：剥 URL 前缀，{@code https://openalex.org/A5053078380} 得到 {@code A5053078380}。
     * <p>
     * 取最后一个斜杠之后的部分，对 openalex.org 与 orcid.org 两种前缀都成立。
     * <b>只用于 id 类字段</b>——ids.scopus / ids.wikipedia 本身是含路径的 URL，走这里会被截断。
     * <p>
     * 理由不是省空间是一致性：同一索引内若有的 id 带前缀、有的不带，调用方无法形成稳定预期。
     */
    private static String stripUrlPrefix(String value) {
        if (value == null) {
            return null;
        }
        int slash = value.lastIndexOf(47);
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static List<String> strippedIdArray(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String v = stripUrlPrefix(node.isTextual() ? node.asText() : null);
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 只认字符串节点：MissingNode / NullNode / 数字一律当缺失。
     * 比 {@code asText(null)} 严——后者会把数字悄悄转成字符串，与「显式投影」相悖。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Float floating(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? (float) value.asDouble() : null;
    }

    /**
     * 固定用 {@link Locale#ROOT}：土耳其语环境下大写 I 转小写得到的是点上无点的那个字符而非 i，
     * 会让去重结果随 JVM 默认 locale 变化。
     */
    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** 规则 6：缺失一律省略，不写 null（{@code exists} 查询才有意义）。空串同样视为缺失。 */
    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        map.put(key, value);
    }

    private static void putIfNotEmpty(Map<String, Object> map, String key, Collection<?> value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> map, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    /**
     * 用 unmodifiableMap 而非 Map.copyOf：后者不保留插入顺序。
     * 不可变是因为 primary_topic 与 topics[0] 共用同一个实例，任何一方被改都会波及另一方。
     */
    private static Map<String, Object> unmodifiable(Map<String, Object> map) {
        return Collections.unmodifiableMap(map);
    }
}
