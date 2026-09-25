package com.dm.backend.repository;

import com.dm.backend.entity.StatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusLogRepository extends JpaRepository<StatusLog, Long> {
}
