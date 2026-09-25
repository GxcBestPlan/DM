package com.dm.backend.repository;

import com.dm.backend.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    List<TeamMember> findByTeamId(Long teamId);

    List<TeamMember> findByUserId(Long userId);

    List<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    boolean existsByTeamId(Long teamId);
}
