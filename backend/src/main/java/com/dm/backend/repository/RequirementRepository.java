package com.dm.backend.repository;

import com.dm.backend.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    boolean existsByTeamId(Long teamId);
}
