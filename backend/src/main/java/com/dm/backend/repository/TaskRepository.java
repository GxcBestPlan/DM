package com.dm.backend.repository;

import com.dm.backend.entity.Task;
import com.dm.backend.entity.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByRequirementIdOrderBySortAsc(Long requirementId);

    List<Task> findByRequirementIdInOrderBySortAsc(Collection<Long> requirementIds);

    long countByRequirementId(Long requirementId);

    long countByRequirementIdAndStatus(Long requirementId, TaskStatus status);
}
