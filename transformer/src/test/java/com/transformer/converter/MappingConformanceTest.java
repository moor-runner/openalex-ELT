package com.transformer.converter;

import com.common.entity.SocialEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * converter 输出与 mapping 的一致性校验。
 * <p>
 * 这是 {@code docs/ES文档对象设计.md}「修订 2026-09-06：converter 输出改为 Map&lt;String,Object&gt;」中
 * 规定的<b>强制补偿单测</b>，与幂等性单测同级，不是可选项。
 * <p>
 * 存在理由：换成 Map 之后没有编译器兜底字段名，而失败模式是<b>静默</b>的——
 * key 拼错会触发 strict_dynamic_mapping_exception，按 bulk 响应分类归 Poisoned，
 * 于是每一条都进死信表，而 HTTP 全程 200、不抛异常，跑完千万级才从计数器上察觉。
 * <p>
 * 期望 key 集合<b>不手写</b>，直接从 mapping JSON 读——手写等于又造出第二份 schema 声明，
 * 一样会漂。这样检查的是「converter 输出符合 mapping」，两边都跑不掉。
 */
class MappingConformanceTest {

    private static final String MAPPING_RESOURCE = "es/mapping/openalex_authors.json";

    /** value 类型白名单，见修订中的表。 */
    private static final Set<Class<?>> ALLOWED_LEAF_TYPES =
            Set.of(String.class, Integer.class, Float.class, Short.class);

    private static Set<String> declaredPaths;

    private final AuthorConverter converter = new AuthorConverter();

    @BeforeAll
    static void loadMapping() throws Exception {
        try (InputStream in = MappingConformanceTest.class.getClassLoader()
                .getResourceAsStream(MAPPING_RESOURCE)) {
            assertNotNull(in, "classpath 上找不到 " + MAPPING_RESOURCE);
            JsonNode mapping = new ObjectMapper().readTree(in);
            declaredPaths = declaredPaths(mapping.path("mappings").path("properties"), "");
        }
        assertFalse(declaredPaths.isEmpty(), "mapping 里一个字段都没解析出来，解析逻辑有问题");
    }

    @Test
    @DisplayName("converter 输出的每个 key 路径都在 mapping 中声明")
    void everyOutputPathIsDeclared() {
        Set<String> outputPaths = new TreeSet<>();
        collectPaths(converter.convert(sample()), "", outputPaths, new ArrayList<>());

        Set<String> undeclared = new TreeSet<>(outputPaths);
        undeclared.removeAll(declaredPaths);

        assertTrue(undeclared.isEmpty(),
                "以下 key 未在 mapping 中声明，写入时会被 dynamic:strict 拒绝并全部落进死信表：" + undeclared);
    }

    @Test
    @DisplayName("完整样本产出设计文档规定的 15 个根字段，一个不多一个不少")
    void fullSampleProducesAllRootFields() {
        Set<String> expected = new LinkedHashSet<>(List.of(
                "entity_id", "orcid", "ids",
                "display_name", "name_variants",
                "last_known_institutions", "affiliations",
                "primary_topic", "topics",
                "works_count", "cited_by_count",
                "h_index", "i10_index", "mean_citedness_2y",
                "updated_at"));

        assertEquals(expected, converter.convert(sample()).keySet());
    }

    @Test
    @DisplayName("每个叶子值的运行时类型都在白名单内")
    void everyLeafValueTypeIsAllowed() {
        List<String> violations = new ArrayList<>();
        collectPaths(converter.convert(sample()), "", new TreeSet<>(), violations);

        assertTrue(violations.isEmpty(),
                "以下值的类型不在白名单内（JsonNode / 承载 JSON 的 String 等均被禁止）：" + violations);
    }

    @Test
    @DisplayName("multi-field 子字段不是合法的文档 key")
    void multiFieldSubFieldsAreNotDocumentKeys() {
        // 这条守的是 declaredPaths() 自身的递归逻辑：它只能递归 properties，不能递归 fields。
        // 一旦递归了 fields，上面那条「输出路径都已声明」的断言就会放过 display_name.keyword
        // 这种写法——而它在写入时会被当成对象路径解析，落到 text 字段上就是 mapper_parsing_exception。
        assertTrue(declaredPaths.contains("display_name"), "display_name 应当是声明过的字段");
        assertFalse(declaredPaths.contains("display_name.keyword"),
                "display_name.keyword 是 multi-field 的索引视图，不是可写入的 key");
        assertFalse(declaredPaths.contains("display_name.prefix"),
                "display_name.prefix 是 multi-field 的索引视图，不是可写入的 key");
        assertFalse(declaredPaths.contains("affiliations.display_name.text"),
                "affiliations.display_name.text 是 multi-field 的索引视图，不是可写入的 key");

        // 对照：子对象（properties）反过来必须被识别
        assertTrue(declaredPaths.contains("ids.mag"), "ids.mag 是子对象字段，应当被识别");
        assertTrue(declaredPaths.contains("affiliations.years"), "affiliations.years 应当被识别");
    }

    @Test
    @DisplayName("mapping 中未声明 ids.openalex 与 ids.orcid")
    void redundantIdsAreNotDeclared() {
        // raw 的 ids 对象里有这两个，converter 必须丢掉：
        // ids.openalex 与 entity_id 同值，ids.orcid 已是顶层字段
        assertFalse(declaredPaths.contains("ids.openalex"));
        assertFalse(declaredPaths.contains("ids.orcid"));
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 从 mapping 的 properties 递归出所有合法的文档字段路径。
     * <p>
     * <b>只递归 {@code properties}，绝不递归 {@code fields}</b>：
     * 前者是子对象（nested 与普通 object 都算），路径可写；
     * 后者是 multi-field，是 ES 从同一个值派生的索引视图，写入侧不存在。
     */
    private static Set<String> declaredPaths(JsonNode properties, String prefix) {
        Set<String> out = new TreeSet<>();
        Iterator<String> names = properties.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            out.add(path);

            JsonNode nested = properties.path(name).path("properties");
            if (nested.isObject()) {
                out.addAll(declaredPaths(nested, path));
            }
        }
        return out;
    }

    /**
     * 遍历 converter 的输出，收集 key 路径并校验叶子类型。
     * <p>
     * 数组不产生新的路径层级——ES 里任何字段天然可以是数组，
     * {@code affiliations} 是数组这件事在 mapping 中并无体现。
     */
    private static void collectPaths(Object value, String prefix,
                                     Set<String> paths, List<String> typeViolations) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String path = prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k;
                paths.add(path);
                collectPaths(v, path, paths, typeViolations);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectPaths(item, prefix, paths, typeViolations));
        } else if (value == null) {
            typeViolations.add(prefix + " -> null（规则 6 要求缺失一律省略，不写 null）");
        } else if (!ALLOWED_LEAF_TYPES.contains(value.getClass())) {
            typeViolations.add(prefix + " -> " + value.getClass().getName());
        }
    }

    // ---------------------------------------------------------------- 样本

    private static SocialEntity sample() {
        SocialEntity se = new SocialEntity(1L, "authors", "openalex", "A5053078380", SAMPLE_RAW);
        se.setUpdatedAt(Instant.parse("2026-09-06T10:15:30Z"));
        return se;
    }

    /**
     * 覆盖全部 15 个根字段的样本。
     * <p>
     * <b>注意：这份 raw 是照设计文档「raw 来源」列手工构造的，不是真实的 OpenAlex 响应。</b>
     * 上线前须用一条真实 author 记录替换，核对字段名——尤其是
     * {@code raw_author_names} 与 {@code last_known_institutions} 这两个。
     */
    private static final String SAMPLE_RAW = """
            {
              "id": "https://openalex.org/A5053078380",
              "orcid": "https://orcid.org/0000-0001-6445-3672",
              "display_name": "Nicola Jones",
              "display_name_alternatives": ["Jones, Nicola", "N. Jones", "nicola jones"],
              "raw_author_names": ["N Jones", "Nicola Jones"],
              "works_count": 44,
              "cited_by_count": 1192,
              "ids": {
                "openalex": "https://openalex.org/A5053078380",
                "orcid": "https://orcid.org/0000-0001-6445-3672",
                "mag": "5053078380",
                "twitter": "nicolakjones",
                "scopus": "http://www.scopus.com/inward/authorDetails.url?authorID=7404",
                "wikipedia": "https://en.wikipedia.org/wiki/Nicola_Jones"
              },
              "summary_stats": {
                "2yr_mean_citedness": 1.85,
                "h_index": 21,
                "i10_index": 30
              },
              "last_known_institutions": [
                {
                  "id": "https://openalex.org/I63966007",
                  "ror": "https://ror.org/042nb2s44",
                  "display_name": "Massachusetts Institute of Technology",
                  "country_code": "US",
                  "type": "education",
                  "lineage": ["https://openalex.org/I63966007"]
                }
              ],
              "affiliations": [
                {
                  "institution": {
                    "id": "https://openalex.org/I63966007",
                    "display_name": "Massachusetts Institute of Technology",
                    "country_code": "US",
                    "type": "education"
                  },
                  "years": [2012, 2010, 2011]
                },
                {
                  "institution": {
                    "id": "https://openalex.org/I63966007",
                    "display_name": "Massachusetts Institute of Technology",
                    "country_code": "US",
                    "type": "education"
                  },
                  "years": [2018]
                },
                {
                  "institution": {
                    "id": "https://openalex.org/I121332591",
                    "display_name": "Universitat Zurich",
                    "country_code": "CH",
                    "type": "education"
                  },
                  "years": [2015]
                }
              ],
              "topics": [
                {
                  "id": "https://openalex.org/T12345",
                  "display_name": "Ocean Acidification",
                  "count": 12,
                  "subfield": {"id": "https://openalex.org/subfields/1910", "display_name": "Oceanography"},
                  "field": {"id": "https://openalex.org/fields/19", "display_name": "Earth and Planetary Sciences"},
                  "domain": {"id": "https://openalex.org/domains/3", "display_name": "Physical Sciences"}
                },
                {
                  "id": "https://openalex.org/T67890",
                  "display_name": "Climate Communication",
                  "count": 5,
                  "subfield": {"display_name": "Communication"},
                  "field": {"display_name": "Social Sciences"},
                  "domain": {"display_name": "Social Sciences"}
                }
              ]
            }
            """;
}
