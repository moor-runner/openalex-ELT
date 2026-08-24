package com.transformer.entity;

import java.time.LocalDateTime;

/**
 * 同步任务表（分片任务）。
 * <p>
 * {@link com.transformer.full.shard.Shard} 把 social_entity 表按主键 id 区间切分后，每一片作为一行落在这里，
 * 供多线程 worker 领取消费。一片 = 一段主键区间 [{@link #min}, {@link #max}]，重跑时靠区间做幂等。
 */
public class SyncTask {

    /** 主键。 */
    private Long id;

    /** 任务状态：Pending / Running / Finished / Failed。 */
    private String status;

    /** 分片主键区间下界。 */
    private Long min;

    /** 分片主键区间上界。 */
    private Long max;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /**
     * 更新时间。Running 期间每写完一批更新一次，兼作心跳：
     * {@code now - updatedAt} 超过超时阈值即判定为僵尸任务。
     */
    private LocalDateTime updatedAt;

    /**
     * 整片失败的原因（重试耗尽 / Fatal / 远端故障），仅在 status=Failed 时有意义。
     * 单行有毒（Poisoned）走 SKIP + 死信表，不落在这里。
     */
    private String errorMsg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMin() {
        return min;
    }

    public void setMin(Long min) {
        this.min = min;
    }

    public Long getMax() {
        return max;
    }

    public void setMax(Long max) {
        this.max = max;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    @Override
    public String toString() {
        return "SyncTask{" +
                "id=" + id +
                ", status='" + status + '\'' +
                ", min=" + min +
                ", max=" + max +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", errorMsg='" + errorMsg + '\'' +
                '}';
    }
}
