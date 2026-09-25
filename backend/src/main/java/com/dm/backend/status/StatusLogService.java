package com.dm.backend.status;

import com.dm.backend.entity.StatusLog;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.repository.StatusLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 状态日志唯一写入口：所有需求/任务的状态变更与留痕事件都走这里。 */
@Service
public class StatusLogService {

    private final StatusLogRepository logs;

    public StatusLogService(StatusLogRepository logs) {
        this.logs = logs;
    }

    public StatusLog record(LogObjectType objectType, Long objectId, String fromStatus, String toStatus,
                            String comment, Long operatorId) {
        StatusLog log = new StatusLog();
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setComment(comment);
        log.setOperatorId(operatorId);
        return logs.save(log);
    }

    public List<StatusLog> history(LogObjectType objectType, Long objectId) {
        return logs.findByObjectTypeAndObjectIdOrderByCreatedAtAsc(objectType, objectId);
    }
}
