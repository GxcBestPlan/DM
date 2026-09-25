package com.dm.backend.entity.enums;

/**
 * 需求状态：池内 PENDING_REVIEW/REVIEWED，迭代内 PLANNED..PUBLISHED，终态 CANCELLED。
 * 阻塞不是状态，由 requirement.blocked 标记。
 */
public enum RequirementStatus {
    PENDING_REVIEW, REVIEWED, PLANNED, DEV, READY_FOR_TEST, TESTING, ACCEPTED, PUBLISHED, CANCELLED
}
