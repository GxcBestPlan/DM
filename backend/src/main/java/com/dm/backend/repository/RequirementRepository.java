package com.dm.backend.repository;

import com.dm.backend.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {

    /** 需求池（sprintId 为空）按池内顺序。 */
    List<Requirement> findByTeamIdAndSprintIdIsNullOrderByPoolSeqAsc(Long teamId);

    /** 某迭代内的需求。 */
    List<Requirement> findBySprintIdOrderByPoolSeqAsc(Long sprintId);

    /** 本队全部需求（含池内与各迭代），测试队列等跨迭代查询用。 */
    List<Requirement> findByTeamIdOrderByIdAsc(Long teamId);

    boolean existsByTeamId(Long teamId);
}
