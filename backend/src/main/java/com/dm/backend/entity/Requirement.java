package com.dm.backend.entity;

import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.RequirementType;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需求：sprintId 为空表示在需求池。 */
@Entity
@Table(name = "requirement")
public class Requirement extends BaseEntity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** 空 = 在需求池。 */
    @Column(name = "sprint_id")
    private Long sprintId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequirementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequirementStatus status = RequirementStatus.PENDING_REVIEW;

    /** 池内排序号，排入迭代后置空。 */
    @Column(name = "pool_seq")
    private Integer poolSeq;

    /** 预估人日：0.5 粒度、>= 0.5。 */
    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal estimate;

    @Column(nullable = false)
    private Boolean urgent = false;

    @Column(nullable = false)
    private Boolean blocked = false;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    /** 验收标准：登记"评审通过"前必填。 */
    @Column(name = "acceptance_criteria", columnDefinition = "TEXT")
    private String acceptanceCriteria;

    /** 外部依赖 / 跨队协调备注（选填）。 */
    @Column(name = "external_note", length = 500)
    private String externalNote;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Generated(GenerationTime.ALWAYS)
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public Long getSprintId() {
        return sprintId;
    }

    public void setSprintId(Long sprintId) {
        this.sprintId = sprintId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RequirementType getType() {
        return type;
    }

    public void setType(RequirementType type) {
        this.type = type;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public void setStatus(RequirementStatus status) {
        this.status = status;
    }

    public Integer getPoolSeq() {
        return poolSeq;
    }

    public void setPoolSeq(Integer poolSeq) {
        this.poolSeq = poolSeq;
    }

    public BigDecimal getEstimate() {
        return estimate;
    }

    public void setEstimate(BigDecimal estimate) {
        this.estimate = estimate;
    }

    public Boolean getUrgent() {
        return urgent;
    }

    public void setUrgent(Boolean urgent) {
        this.urgent = urgent;
    }

    public Boolean getBlocked() {
        return blocked;
    }

    public void setBlocked(Boolean blocked) {
        this.blocked = blocked;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public LocalDateTime getBlockedAt() {
        return blockedAt;
    }

    public void setBlockedAt(LocalDateTime blockedAt) {
        this.blockedAt = blockedAt;
    }

    public String getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(String acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria;
    }

    public String getExternalNote() {
        return externalNote;
    }

    public void setExternalNote(String externalNote) {
        this.externalNote = externalNote;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
