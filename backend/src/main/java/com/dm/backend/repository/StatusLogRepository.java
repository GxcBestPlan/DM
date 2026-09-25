package com.dm.backend.repository;

import com.dm.backend.entity.StatusLog;
import com.dm.backend.entity.enums.LogObjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusLogRepository extends JpaRepository<StatusLog, Long> {

    List<StatusLog> findByObjectTypeAndObjectIdOrderByCreatedAtAsc(LogObjectType objectType, Long objectId);
}
