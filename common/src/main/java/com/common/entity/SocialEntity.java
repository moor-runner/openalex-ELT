package com.common.entity;

import java.time.Instant;

public class SocialEntity {
    private Long    id;
    private String  platform;      // "openalex"
    private String  entityType;    // "authors"
    private String  entityId;      // "A5023888391"
    private String  data;          // 原始 JSON 原文，未经解析
    private Instant createdAt;
    private Instant updatedAt;
    
    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public SocialEntity(Long id, String entityType, String platform, String entityId, String data) {
        this.id = id;
        this.entityType = entityType;
        this.platform = platform;
        this.entityId = entityId;
        this.data = data;
    }

   
}