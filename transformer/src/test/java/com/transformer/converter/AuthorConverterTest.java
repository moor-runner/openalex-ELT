package com.transformer.converter;

import com.common.entity.SocialEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthorConverter} 的规范化规则单测。
 * <p>
 * {@code docs/ES文档对象设计.md}「converter 契约」要求规范化规则每条对应一个单测，
 * 本类按规则 1–6 逐条组织。
 */
class AuthorConverterTest {

    private final AuthorConverter converter = new AuthorConverter();

    @Nested
    @DisplayName("规则 1：所有 id 剥 URL 前缀")
    class Rule1StripUrlPrefix {

        @Test
        @DisplayName("entity_id 与 orcid 剥掉各自的前缀")
        void stripsTopLevelIds() {
            Map<String, Object> doc = convert("""
                    {"id": "https://openalex.org/A5053078380",
                     "orcid": "https://orcid.org/0000-0001-6445-3672"}
                    """);

            assertEquals("A5053078380", doc.get("entity_id"));
            assertEquals("0000-0001-6445-3672", doc.get("orcid"));
        }

        @Test
        @DisplayName("机构 id、lineage、topic id 一并剥前缀")
        void stripsNestedIds() {
            Map<String, Object> doc = convert("""
                    {"last_known_institutions": [
                       {"id": "https://openalex.org/I63966007",
                        "lineage": ["https://openalex.org/I63966007", "https://openalex.org/I4210",
                                    "not-a-url"]}],
                     "topics": [{"id": "https://openalex.org/T12345"}]}
                    """);

            Map<String, Object> inst = firstOf(doc, "last_known_institutions");
            assertEquals("I63966007", inst.get("id"));
            assertEquals(List.of("I63966007", "I4210", "not-a-url"), inst.get("lineage"));
            assertEquals("T12345", firstOf(doc, "topics").get("id"));
        }

        @Test
        @DisplayName("scopus 与 wikipedia 是带路径的 URL，不能剥")
        void doesNotStripUrlValuedIds() {
            Map<String, Object> doc = convert("""
                    {"ids": {"scopus": "http://www.scopus.com/inward/authorDetails.url?authorID=7404",
                             "wikipedia": "https://en.wikipedia.org/wiki/Nicola_Jones",
                             "mag": "5053078380"}}
                    """);

            Map<String, Object> ids = asMap(doc.get("ids"));
            assertEquals("http://www.scopus.com/inward/authorDetails.url?authorID=7404", ids.get("scopus"));
            assertEquals("https://en.wikipedia.org/wiki/Nicola_Jones", ids.get("wikipedia"));
            assertEquals("5053078380", ids.get("mag"));
        }

        @Test
        @DisplayName("ids.openalex 与 ids.orcid 冗余，不进文档")
        void dropsRedundantIds() {
            Map<String, Object> doc = convert("""
                    {"ids": {"openalex": "https://openalex.org/A5053078380",
                             "orcid": "https://orcid.org/0000-0001-6445-3672",
                             "mag": "5053078380"}}
                    """);

            assertEquals(Map.of("mag", "5053078380"), doc.get("ids"));
        }
    }

    @Nested
    @DisplayName("规则 2：name_variants 合并、去重、剔除正名、截断")
    class Rule2NameVariants {

        @Test
        @DisplayName("两源合并，按 lowercase 去重，保留首次出现的原始大小写")
        void mergesAndDedupesIgnoringCase() {
            Map<String, Object> doc = convert("""
                    {"display_name": "Nicola Jones",
                     "display_name_alternatives": ["Jones, Nicola", "N. Jones"],
                     "raw_author_names": ["JONES, NICOLA", "N Jones"]}
                    """);

            // "JONES, NICOLA" 与已有的 "Jones, Nicola" 同 lowercase，丢弃后者、保留先出现的原始写法
            assertEquals(List.of("Jones, Nicola", "N. Jones", "N Jones"), doc.get("name_variants"));
        }

        @Test
        @DisplayName("lowercase 等于 display_name 的变体被剔除")
        void dropsVariantsEqualToDisplayName() {
            Map<String, Object> doc = convert("""
                    {"display_name": "Nicola Jones",
                     "display_name_alternatives": ["nicola jones", "NICOLA JONES", "Jones, Nicola"],
                     "raw_author_names": ["Nicola Jones"]}
                    """);

            assertEquals(List.of("Jones, Nicola"), doc.get("name_variants"));
        }

        @Test
        @DisplayName("截断到 50 条")
        void truncatesAtFifty() {
            String variants = IntStream.range(0, 60)
                    .mapToObj(i -> "\"Variant " + i + "\"")
                    .collect(Collectors.joining(","));
            Map<String, Object> doc = convert(
                    "{\"display_name\": \"Nicola Jones\", \"display_name_alternatives\": [" + variants + "]}");

            assertEquals(50, asList(doc.get("name_variants")).size());
        }

        @Test
        @DisplayName("两源都缺失时整个字段省略")
        void omittedWhenNoVariants() {
            assertFalse(convert("{\"display_name\": \"Nicola Jones\"}").containsKey("name_variants"));
        }
    }

    @Nested
    @DisplayName("规则 3：topics 三层压平、去 count，primary_topic 取 topics[0]")
    class Rule3Topics {

        @Test
        @DisplayName("三层对象压平成标量，count 不进文档")
        void flattensAndDropsCount() {
            Map<String, Object> doc = convert("""
                    {"topics": [{"id": "https://openalex.org/T12345",
                                 "display_name": "Ocean Acidification",
                                 "count": 12,
                                 "score": 0.9,
                                 "subfield": {"id": "x", "display_name": "Oceanography"},
                                 "field": {"id": "y", "display_name": "Earth and Planetary Sciences"},
                                 "domain": {"id": "z", "display_name": "Physical Sciences"}}]}
                    """);

            assertEquals(Map.of(
                    "id", "T12345",
                    "display_name", "Ocean Acidification",
                    "subfield", "Oceanography",
                    "field", "Earth and Planetary Sciences",
                    "domain", "Physical Sciences"), doc.get("primary_topic"));
        }

        @Test
        @DisplayName("primary_topic 取 topics[0]")
        void primaryTopicIsFirst() {
            Map<String, Object> doc = convert("""
                    {"topics": [{"id": "https://openalex.org/T1", "display_name": "First"},
                                {"id": "https://openalex.org/T2", "display_name": "Second"}]}
                    """);

            assertEquals(doc.get("primary_topic"), firstOf(doc, "topics"));
            assertEquals(2, asList(doc.get("topics")).size());
        }

        @Test
        @DisplayName("topics 为空数组时 primary_topic 与 topics 一起省略")
        void bothOmittedWhenTopicsEmpty() {
            Map<String, Object> doc = convert("{\"topics\": []}");

            assertFalse(doc.containsKey("primary_topic"));
            assertFalse(doc.containsKey("topics"));
        }

        @Test
        @DisplayName("primary_topic 与 topics[0] 共用实例，且不可变")
        void primaryTopicIsImmutableAndShared() {
            Map<String, Object> doc = convert("{\"topics\": [{\"id\": \"https://openalex.org/T1\"}]}");

            // 共用实例本身无害——前提是不可变，否则改一处会波及另一处
            assertSame(doc.get("primary_topic"), firstOf(doc, "topics"));
            assertThrows(UnsupportedOperationException.class,
                    () -> asMap(doc.get("primary_topic")).put("id", "tampered"));
        }
    }

    @Nested
    @DisplayName("规则 4：affiliations 按机构去重、years 取并集、截断 100")
    class Rule4Affiliations {

        @Test
        @DisplayName("同机构多段年份合并为一条，years 取并集并升序")
        void mergesSameInstitutionAndUnionsYears() {
            Map<String, Object> doc = convert("""
                    {"affiliations": [
                       {"institution": {"id": "https://openalex.org/I1", "display_name": "MIT"},
                        "years": [2012, 2010, 2011]},
                       {"institution": {"id": "https://openalex.org/I1", "display_name": "MIT"},
                        "years": [2018, 2010]}]}
                    """);

            List<Object> affiliations = asList(doc.get("affiliations"));
            assertEquals(1, affiliations.size());
            assertEquals(List.of((short) 2010, (short) 2011, (short) 2012, (short) 2018),
                    asMap(affiliations.get(0)).get("years"));
        }

        @Test
        @DisplayName("years 是 Short 不是 Integer")
        void yearsAreShort() {
            Map<String, Object> doc = convert("""
                    {"affiliations": [{"institution": {"id": "https://openalex.org/I1"}, "years": [2010]}]}
                    """);

            Object year = asList(firstOf(doc, "affiliations").get("years")).get(0);
            assertInstanceOf(Short.class, year);
        }

        @Test
        @DisplayName("截断按最大年份降序保留")
        void truncatesByLatestYearDescending() {
            String entries = IntStream.range(0, 120)
                    .mapToObj(i -> "{\"institution\": {\"id\": \"https://openalex.org/I" + i + "\"},"
                            + " \"years\": [" + (1900 + i) + "]}")
                    .collect(Collectors.joining(","));
            Map<String, Object> doc = convert("{\"affiliations\": [" + entries + "]}");

            List<Object> affiliations = asList(doc.get("affiliations"));
            assertEquals(100, affiliations.size());
            // 年份最大的 I119（2019）应排第一，最小的 I0–I19 被截掉
            assertEquals("I119", asMap(affiliations.get(0)).get("id"));
            assertEquals("I20", asMap(affiliations.get(99)).get("id"));
        }

        @Test
        @DisplayName("没有 institution id 的整条丢弃：无法去重也无法被检索")
        void dropsEntriesWithoutInstitutionId() {
            Map<String, Object> doc = convert("""
                    {"affiliations": [{"institution": {"display_name": "无 id 的机构"}, "years": [2010]}]}
                    """);

            assertFalse(doc.containsKey("affiliations"));
        }

        @Test
        @DisplayName("不放 lineage、不放 ror")
        void omitsLineageAndRor() {
            Map<String, Object> doc = convert("""
                    {"affiliations": [{"institution": {"id": "https://openalex.org/I1",
                                                       "ror": "https://ror.org/042nb2s44",
                                                       "lineage": ["https://openalex.org/I1"]},
                                       "years": [2010]}]}
                    """);

            Map<String, Object> affiliation = firstOf(doc, "affiliations");
            assertFalse(affiliation.containsKey("ror"));
            assertFalse(affiliation.containsKey("lineage"));
        }
    }

    @Nested
    @DisplayName("规则 5：summary_stats 缺失时三个指标一起省略")
    class Rule5SummaryStats {

        @Test
        @DisplayName("存在时展开并把 2yr_mean_citedness 改名为 mean_citedness_2y")
        void expandsAndRenames() {
            Map<String, Object> doc = convert("""
                    {"summary_stats": {"2yr_mean_citedness": 1.85, "h_index": 21, "i10_index": 30}}
                    """);

            assertEquals(21, doc.get("h_index"));
            assertEquals(30, doc.get("i10_index"));
            assertEquals(1.85f, (Float) doc.get("mean_citedness_2y"), 0.0001f);
            assertFalse(doc.containsKey("2yr_mean_citedness"));
        }

        @Test
        @DisplayName("整个 summary_stats 缺失时三个字段一起不出现")
        void allThreeOmittedTogether() {
            Map<String, Object> doc = convert("{\"works_count\": 44}");

            assertFalse(doc.containsKey("h_index"));
            assertFalse(doc.containsKey("i10_index"));
            assertFalse(doc.containsKey("mean_citedness_2y"));
            assertEquals(44, doc.get("works_count"));
        }
    }

    @Nested
    @DisplayName("规则 6：缺失一律省略，不写 null")
    class Rule6OmitMissing {

        @Test
        @DisplayName("空文档只产出 updated_at，且不含任何 null 值")
        void emptyRawProducesNoNulls() {
            Map<String, Object> doc = convert("{}");

            assertEquals(java.util.Set.of("updated_at"), doc.keySet());
            assertFalse(doc.containsValue(null));
        }

        @Test
        @DisplayName("显式 null 与空串都按缺失处理")
        void nullAndBlankTreatedAsMissing() {
            Map<String, Object> doc = convert("""
                    {"id": "https://openalex.org/A1", "orcid": null, "display_name": "   "}
                    """);

            assertFalse(doc.containsKey("orcid"));
            assertFalse(doc.containsKey("display_name"));
            assertEquals("A1", doc.get("entity_id"));
        }

        @Test
        @DisplayName("类型不符的值按缺失处理，不做隐式转换")
        void wrongTypesTreatedAsMissing() {
            // asText() 会把数字悄悄转成字符串，与「显式投影」相悖，故只认字符串节点
            Map<String, Object> doc = convert("""
                    {"display_name": 12345, "works_count": "44"}
                    """);

            assertFalse(doc.containsKey("display_name"));
            assertFalse(doc.containsKey("works_count"));
        }
    }

    @Nested
    @DisplayName("契约边界")
    class ContractBoundary {

        @Test
        @DisplayName("index 名由 converter 自报")
        void reportsIndexName() {
            assertEquals("openalex_authors", converter.indexName());
        }

        @Test
        @DisplayName("raw 不是合法 JSON 时抛 IllegalArgumentException，由调用方按 Poisoned 处理")
        void throwsOnMalformedJson() {
            assertThrows(IllegalArgumentException.class, () -> convert("{不是 JSON"));
            assertThrows(IllegalArgumentException.class, () -> convert(""));
        }

        @Test
        @DisplayName("updated_at 取自 social_entity 而非 raw，输出 ISO-8601 字符串")
        void updatedAtComesFromSocialEntity() {
            Map<String, Object> doc = convert("{}");

            assertEquals("2026-09-06T10:15:30Z", doc.get("updated_at"));
            assertInstanceOf(String.class, doc.get("updated_at"));
        }

        @Test
        @DisplayName("updatedAt 为 null 时省略——上游 DAO 没补 setter，EsWriter 取 version 时也会取不到")
        void updatedAtOmittedWhenNull() {
            SocialEntity se = new SocialEntity(1L, "authors", "openalex", "A1", "{}");
            assertFalse(converter.convert(se).containsKey("updated_at"));
        }

        @Test
        @DisplayName("platform 与 entity_type 不进文档：index 名已完整承载")
        void platformAndEntityTypeNotInDocument() {
            Map<String, Object> doc = convert("{\"id\": \"https://openalex.org/A1\"}");

            assertFalse(doc.containsKey("platform"));
            assertFalse(doc.containsKey("entity_type"));
            assertFalse(doc.containsKey("social_entity_id"));
        }
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> convert(String raw) {
        SocialEntity se = new SocialEntity(1L, "authors", "openalex", "A5053078380", raw);
        se.setUpdatedAt(Instant.parse("2026-09-06T10:15:30Z"));
        return converter.convert(se);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> firstOf(Map<String, Object> doc, String key) {
        List<Object> list = asList(doc.get(key));
        assertTrue(list != null && !list.isEmpty(), key + " 不应为空");
        return asMap(list.get(0));
    }
}
