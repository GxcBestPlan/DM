package com.dm.backend.repository;

import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.enums.SprintStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    List<Sprint> findByTeamIdOrderByStartDateDesc(Long teamId);

    boolean existsByTeamId(Long teamId);

    boolean existsByTeamIdAndStatus(Long teamId, SprintStatus status);
}
