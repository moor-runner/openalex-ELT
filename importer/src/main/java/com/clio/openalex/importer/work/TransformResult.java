package com.clio.openalex.importer.work;

import java.util.List;

import com.common.entity.SocialEntity;

/**
 * transform 一个批次的产物：两条不同类型的通道。
 *
 * <p>transform 保持纯函数（data in → data out，不碰数据库），把"好行/坏行"用两个带名字、
 * 带类型的字段返回，由调用方（importFile）在同一个 chunk 事务里分别落库：
 * {@code entities} 走 social_entity，{@code deadRows} 走 dead_row。
 *
 * @param entities 可落库的好行
 * @param deadRows 单行 Poisoned 的坏行（解不开的JSON / 缺 id）
 */
public record TransformResult(List<SocialEntity> entities, List<DeadRow> deadRows) {
}
