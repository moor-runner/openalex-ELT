package com.transformer.converter;

import com.common.entity.SocialEntity;

import java.util.Locale;
import java.util.Map;

/**
 * 单个集合（platform + entity_type）的 raw → ES 文档转换。
 * <p>
 * 契约见 {@code docs/ES文档对象设计.md} 的「converter 契约」及其 2026-09-06 修订：
 * 纯函数、无副作用、可单测；输出 {@code Map<String,Object>}，key 与 mapping 中声明的字段名逐字一致。
 * <p>
 * 输出 Map 的 value 只允许白名单内的类型：
 * {@code String} / {@code Integer} / {@code Float} /
 * {@code List<String>} / {@code List<Short>} /
 * {@code Map<String,Object>} / {@code List<Map<String,Object>>}。
 * <p>
 * 禁止 {@code JsonNode} 与承载 JSON 的 {@code String}——那等于把 raw 透传放了回来，
 * 而根上的 {@code dynamic: strict} 正建立在「显式投影」之上。
 * <p>
 * 另注意 multi-field 的子字段（如 {@code display_name.keyword}）<b>不是</b>合法的文档 key：
 * 它们是 ES 从同一个值派生出的索引视图，写入侧不存在。带点的 key 会被当成对象路径解析，
 * 落到一个非 object 的字段上就是 {@code mapper_parsing_exception}。
 */
public interface Converter {

    /** 如 {@code openalex}，与 social_entity.platform 取值一致。 */
    String platform();

    /** 如 {@code authors}，与 social_entity.entity_type 取值一致。 */
    String entityType();

    /**
     * index 名由 converter 自报，形如 {@code openalex_authors}。
     * <p>
     * 返回的是 <b>alias</b> 名；物理 index 是带 {@code _v1} 后缀的那个，
     * 写入侧只认 alias，这样 reindex 切版本时不必改代码。
     */
    default String indexName() {
        return (platform() + "_" + entityType()).toLowerCase(Locale.ROOT);
    }

    /**
     * @param se 一行 social_entity，{@link SocialEntity#getData()} 是未经解析的 raw JSON 原文
     * @return 可直接作为 bulk document source 的 Map。缺失字段一律不 put，不写 {@code null}，
     *         以保证 {@code exists} 查询的语义
     * @throws IllegalArgumentException raw 无法解析。调用方（EsWriter）应按 Poisoned 落 DeadLetter、
     *         不重试——这是数据问题，重试多少次结果都一样
     */
    Map<String, Object> convert(SocialEntity se);
}
