package com.dm.backend.repository;

import com.dm.backend.entity.Sprint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    boolean existsByTeamId(Long teamId);
}
