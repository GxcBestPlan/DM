package com.dm.backend.entity;

import com.dm.backend.entity.enums.LogObjectType;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;

/**
 * 状态日志：需求/任务的全量流转留痕。
 * 自环（fromStatus == toStatus）用于不换状态的事件：评审退回、挂/解阻塞等，原因写 comment。
 */
@Entity
@Table(name = "status_log")
public class StatusLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 20)
    private LogObjectType objectType;

    @Column(name = "object_id", nullable = false)
    private Long objectId;

    /** 空 = 对象创建。 */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    /** 评审意见 / 退回原因 / 阻塞原因等。 */
    @Column(name = "comment", length = 500)
    private String comment;

    /** 空 = 系统自动推进。 */
    @Column(name = "operator_id")
    private Long operatorId;

    public LogObjectType getObjectType() {
        return objectType;
    }

    public void setObjectType(LogObjectType objectType) {
        this.objectType = objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public void setObjectId(Long objectId) {
        this.objectId = objectId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }
}
