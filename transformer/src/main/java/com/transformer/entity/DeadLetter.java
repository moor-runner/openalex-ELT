package com.transformer.entity;

import java.time.LocalDateTime;

/**
 * 死信表。
 * <p>
 * T 阶段全量同步时，整片继续但被 SKIP 掉的单行坏数据（Poisoned）落在这里，供 reconcile 对账 / 重处理。
 * 幂等键为 {@link #socialEntityId}（源表 social_entity 主键）：一条坏源行一条死信，
 * 同一行反复失败用 upsert 刷新原因与时间，不新增。
 */
public class DeadLetter {

    /** 死信表自身主键。 */
    private Long id;

    /** 出错源行在 social_entity 表的主键；幂等键，可 JOIN 回源表。 */
    private Long socialEntityId;

    /** 产生该死信的分片任务 id（{@link SyncTask#getId()}）。 */
    private Long syncId;

    /** 出错源行的原始内容快照，便于离线重处理而不必回源表捞。 */
    private String rawLine;

    /** 处理状态：标识这条死信是否已被重处理 / 解决。 */
    private String status;

    /** 出错原因摘要（异常类名 + message）；完整堆栈进日志，不塞这里。 */
    private String errorMsg;

    /** 记录时间。 */
    private LocalDateTime createdTime;

    /** 最后处理时间。 */
    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSocialEntityId() {
        return socialEntityId;
    }

    public void setSocialEntityId(Long socialEntityId) {
        this.socialEntityId = socialEntityId;
    }

    public Long getSyncId() {
        return syncId;
    }

    public void setSyncId(Long syncId) {
        this.syncId = syncId;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }

    @Override
    public String toString() {
        return "DeadLetter{" +
                "id=" + id +
                ", socialEntityId=" + socialEntityId +
                ", syncId=" + syncId +
                ", status='" + status + '\'' +
                ", errorMsg='" + errorMsg + '\'' +
                ", createdTime=" + createdTime +
                ", updatedTime=" + updatedTime +
                ", rawLineLength=" + (rawLine == null ? 0 : rawLine.length()) +
                '}';
    }
}
