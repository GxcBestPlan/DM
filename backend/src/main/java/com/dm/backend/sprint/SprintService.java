package com.dm.backend.sprint;

import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.SprintStatus;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 迭代管理（§9.4）：创建、开始、关闭、重新打开。 */
@Service
public class SprintService {

    private final SprintRepository sprints;
    private final RequirementRepository requirements;

    public SprintService(SprintRepository sprints, RequirementRepository requirements) {
        this.sprints = sprints;
        this.requirements = requirements;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long teamId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Sprint sprint : sprints.findByTeamIdOrderByStartDateDesc(teamId)) {
            result.add(view(sprint));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Sprint get(Long id) {
        return require(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(Sprint sprint) {
        List<Requirement> rows = requirements.findBySprintIdOrderByPoolSeqAsc(sprint.getId());
        long total = rows.stream().filter(r -> r.getStatus() != RequirementStatus.CANCELLED).count();
        long done = rows.stream().filter(r -> r.getStatus() == RequirementStatus.ACCEPTED
                || r.getStatus() == RequirementStatus.PUBLISHED).count();
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), sprint.getEndDate());
        return Responses.map(
                "id", sprint.getId(),
                "teamId", sprint.getTeamId(),
                "name", sprint.getName(),
                "startDate", sprint.getStartDate(),
                "endDate", sprint.getEndDate(),
                "status", sprint.getStatus(),
                "closedAt", sprint.getClosedAt(),
                "requirementCount", total,
                "doneCount", done,
                "daysLeft", Math.max(daysLeft, 0));
    }

    @Transactional
    public Sprint create(Long teamId, String name, LocalDate startDate, LocalDate endDate) {
        if (isBlank(name)) {
            throw ApiException.badRequest("迭代名称必填");
        }
        if (startDate == null || endDate == null) {
            throw ApiException.badRequest("请填写迭代起止日期");
        }
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("结束日期不能早于开始日期");
        }
        ensureNoActiveSprint(teamId);
        Sprint sprint = new Sprint();
        sprint.setTeamId(teamId);
        sprint.setName(name.trim());
        sprint.setStartDate(startDate);
        sprint.setEndDate(endDate);
        sprint.setStatus(SprintStatus.PLANNED);
        return sprints.save(sprint);
    }

    @Transactional
    public Sprint start(Long id) {
        Sprint sprint = require(id);
        if (sprint.getStatus() != SprintStatus.PLANNED) {
            throw ApiException.conflict("只有规划中的迭代可以开始");
        }
        ensureNoActiveSprint(sprint.getTeamId());
        sprint.setStatus(SprintStatus.ACTIVE);
        return sprints.save(sprint);
    }

    @Transactional
    public Sprint close(Long id) {
        Sprint sprint = require(id);
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw ApiException.conflict("只有进行中的迭代可以关闭");
        }
        sprint.setStatus(SprintStatus.CLOSED);
        sprint.setClosedAt(LocalDateTime.now());
        return sprints.save(sprint);
    }

    @Transactional
    public Sprint reopen(Long id) {
        Sprint sprint = require(id);
        if (sprint.getStatus() != SprintStatus.CLOSED) {
            throw ApiException.conflict("只有已关闭的迭代可以重新打开");
        }
        ensureNoActiveSprint(sprint.getTeamId());
        sprint.setStatus(SprintStatus.ACTIVE);
        sprint.setClosedAt(null);
        return sprints.save(sprint);
    }

    private void ensureNoActiveSprint(Long teamId) {
        if (sprints.existsByTeamIdAndStatus(teamId, SprintStatus.ACTIVE)) {
            throw ApiException.conflict("该小队已有进行中的迭代，请先关闭");
        }
    }

    private Sprint require(Long id) {
        return sprints.findById(id).orElseThrow(() -> ApiException.notFound("迭代不存在"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
