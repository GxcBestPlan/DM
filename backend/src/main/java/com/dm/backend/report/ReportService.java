package com.dm.backend.report;

import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.StatusLog;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.SprintStatus;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.StatusLogRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TaskRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨队总览（§9.2）与统计报表（§9.8）。
 * MVP 口径：完成 = 已验收/已发布；负载 = 迭代内需求预估人日合计 /（开发人数 × 迭代工作日数）；
 * 延期高风险 = 未完成且（阻塞中 或 剩余天数 ≤ 2）。
 */
@Service
public class ReportService {

    private static final int RISK_DAYS_THRESHOLD = 2;
    private static final int RISK_TOP_LIMIT = 5;

    private final TeamRepository teams;
    private final SprintRepository sprints;
    private final TeamMemberRepository members;
    private final RequirementRepository requirements;
    private final TaskRepository tasks;
    private final StatusLogRepository logs;
    private final SysUserRepository users;

    public ReportService(TeamRepository teams, SprintRepository sprints, TeamMemberRepository members,
                         RequirementRepository requirements, TaskRepository tasks,
                         StatusLogRepository logs, SysUserRepository users) {
        this.teams = teams;
        this.sprints = sprints;
        this.members = members;
        this.requirements = requirements;
        this.tasks = tasks;
        this.logs = logs;
        this.users = users;
    }

    // ---------- 跨队总览 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teamOverview() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Team team : teams.findAll()) {
            result.add(teamStat(team));
        }
        return result;
    }

    private Map<String, Object> teamStat(Team team) {
        Sprint current = currentSprint(team.getId());
        List<Requirement> rows = current == null ? Collections.emptyList() : sprintRequirements(current.getId());
        long done = rows.stream().filter(r -> isDone(r.getStatus())).count();
        long blocked = rows.stream().filter(r -> Boolean.TRUE.equals(r.getBlocked())).count();
        long daysLeft = current == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), current.getEndDate()));
        long risk = rows.stream()
                .filter(r -> !isDone(r.getStatus()))
                .filter(r -> Boolean.TRUE.equals(r.getBlocked()) || daysLeft <= RISK_DAYS_THRESHOLD)
                .count();
        List<TeamMember> teamMembers = members.findByTeamId(team.getId());
        long devCount = teamMembers.stream()
                .filter(m -> m.getRole() == MemberRole.FRONTEND_DEV || m.getRole() == MemberRole.BACKEND_DEV)
                .map(TeamMember::getUserId).distinct().count();
        long memberCount = teamMembers.stream().map(TeamMember::getUserId).distinct().count();
        BigDecimal effort = rows.stream().map(Requirement::getEstimate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int workdays = current == null ? 0 : workdays(current.getStartDate(), current.getEndDate());
        double loadPercent = loadPercent(effort, devCount, workdays);

        return Responses.map(
                "teamId", team.getId(),
                "teamName", team.getName(),
                "memberCount", memberCount,
                "devCount", devCount,
                "sprintId", current == null ? null : current.getId(),
                "sprintName", current == null ? null : current.getName(),
                "sprintStatus", current == null ? null : current.getStatus(),
                "startDate", current == null ? null : current.getStartDate(),
                "endDate", current == null ? null : current.getEndDate(),
                "daysLeft", daysLeft,
                "requirementCount", rows.size(),
                "doneCount", done,
                "completionRate", rate(done, rows.size()),
                "blockedCount", blocked,
                "riskCount", risk,
                "effortDays", effort,
                "loadPercent", loadPercent,
                "overloaded", loadPercent >= 100);
    }

    // ---------- 跨队汇总 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teamComparison() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Team team : teams.findAll()) {
            Sprint current = currentSprint(team.getId());
            List<Requirement> rows = current == null ? Collections.emptyList() : sprintRequirements(current.getId());
            long done = rows.stream().filter(r -> isDone(r.getStatus())).count();
            long daysLeft = current == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), current.getEndDate()));
            List<Map<String, Object>> riskTop = rows.stream()
                    .filter(r -> !isDone(r.getStatus()))
                    .filter(r -> Boolean.TRUE.equals(r.getBlocked()) || daysLeft <= RISK_DAYS_THRESHOLD)
                    .sorted(Comparator.comparing(Requirement::getEstimate).reversed())
                    .limit(RISK_TOP_LIMIT)
                    .map(r -> Responses.map(
                            "id", r.getId(),
                            "title", r.getTitle(),
                            "status", r.getStatus(),
                            "estimate", r.getEstimate(),
                            "blocked", r.getBlocked(),
                            "daysLeft", daysLeft))
                    .collect(Collectors.toList());
            result.add(Responses.map(
                    "teamId", team.getId(),
                    "teamName", team.getName(),
                    "sprintName", current == null ? null : current.getName(),
                    "requirementCount", rows.size(),
                    "doneCount", done,
                    "completionRate", rate(done, rows.size()),
                    "riskCount", riskTop.size(),
                    "riskTop", riskTop));
        }
        return result;
    }

    // ---------- 迭代报告 ----------

    @Transactional(readOnly = true)
    public Map<String, Object> sprintReport(Long sprintId) {
        Sprint sprint = sprints.findById(sprintId).orElseThrow(() -> ApiException.notFound("迭代不存在"));
        List<Requirement> rows = sprintRequirements(sprintId);
        long total = rows.size();
        long done = rows.stream().filter(r -> isDone(r.getStatus())).count();
        long urgent = rows.stream().filter(r -> Boolean.TRUE.equals(r.getUrgent())).count();

        Map<Long, List<StatusLog>> logsByRequirement = requirementLogs(rows);
        LocalDate sprintEnd = sprint.getEndDate();
        LocalDateTime now = LocalDateTime.now();

        long onTime = 0;
        double blockedDays = 0;
        Map<RequirementStatus, Long> distribution = new EnumMap<>(RequirementStatus.class);
        List<LocalDateTime> doneTimes = new ArrayList<>();
        for (Requirement requirement : rows) {
            List<StatusLog> history = logsByRequirement.getOrDefault(requirement.getId(), Collections.emptyList());
            distribution.merge(requirement.getStatus(), 1L, Long::sum);
            blockedDays += blockedDays(requirement, history, now);
            LocalDateTime reached = reachedAt(history);
            if (reached != null) {
                doneTimes.add(reached);
                if (!reached.toLocalDate().isAfter(sprintEnd)) {
                    onTime++;
                }
            }
        }

        List<Map<String, Object>> statusDistribution = new ArrayList<>();
        for (Map.Entry<RequirementStatus, Long> entry : distribution.entrySet()) {
            statusDistribution.add(Responses.map("status", entry.getKey(), "count", entry.getValue()));
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate day = sprint.getStartDate(); !day.isAfter(sprintEnd); day = day.plusDays(1)) {
            final LocalDate current = day;
            long cumulative = doneTimes.stream()
                    .filter(time -> !time.toLocalDate().isAfter(current))
                    .count();
            trend.add(Responses.map("date", current, "doneCount", cumulative));
        }

        return Responses.map(
                "sprint", Responses.map(
                        "id", sprint.getId(), "teamId", sprint.getTeamId(), "name", sprint.getName(),
                        "startDate", sprint.getStartDate(), "endDate", sprint.getEndDate(),
                        "status", sprint.getStatus()),
                "requirementCount", total,
                "doneCount", done,
                "completionRate", rate(done, total),
                "onTimeCount", onTime,
                "onTimeRate", done == 0 ? null : rate(onTime, done),
                "urgentCount", urgent,
                "urgentRate", rate(urgent, total),
                "blockedDays", round1(blockedDays),
                "statusDistribution", statusDistribution,
                "dailyTrend", trend,
                "memberLoad", memberLoad(rows));
    }

    private List<Map<String, Object>> memberLoad(List<Requirement> rows) {
        List<Task> taskRows = rows.isEmpty() ? Collections.emptyList()
                : tasks.findByRequirementIdInOrderBySortAsc(rows.stream()
                        .map(Requirement::getId).collect(Collectors.toList()));
        Map<Long, Integer> taskCount = new LinkedHashMap<>();
        Map<Long, Set<Long>> requirementIdsByMember = new LinkedHashMap<>();
        for (Task task : taskRows) {
            if (task.getAssigneeId() == null) {
                continue;
            }
            taskCount.merge(task.getAssigneeId(), 1, Integer::sum);
            requirementIdsByMember.computeIfAbsent(task.getAssigneeId(), key -> new LinkedHashSet<>())
                    .add(task.getRequirementId());
        }
        Map<Long, Requirement> requirementById = rows.stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(user -> names.put(user.getId(), user.getName()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : taskCount.entrySet()) {
            BigDecimal effort = requirementIdsByMember.getOrDefault(entry.getKey(), Collections.emptySet())
                    .stream()
                    .map(requirementById::get)
                    .filter(Objects::nonNull)
                    .map(Requirement::getEstimate)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(Responses.map(
                    "userId", entry.getKey(),
                    "name", names.get(entry.getKey()),
                    "taskCount", entry.getValue(),
                    "effortDays", effort));
        }
        result.sort((a, b) -> Double.compare(((BigDecimal) b.get("effortDays")).doubleValue(),
                ((BigDecimal) a.get("effortDays")).doubleValue()));
        return result;
    }

    // ---------- 内部 ----------

    private Sprint currentSprint(Long teamId) {
        List<Sprint> rows = sprints.findByTeamIdOrderByStartDateDesc(teamId);
        for (Sprint sprint : rows) {
            if (sprint.getStatus() == SprintStatus.ACTIVE) {
                return sprint;
            }
        }
        for (Sprint sprint : rows) {
            if (sprint.getStatus() == SprintStatus.PLANNED) {
                return sprint;
            }
        }
        return null;
    }

    private List<Requirement> sprintRequirements(Long sprintId) {
        return requirements.findBySprintIdOrderByPoolSeqAsc(sprintId).stream()
                .filter(r -> r.getStatus() != RequirementStatus.CANCELLED)
                .collect(Collectors.toList());
    }

    private Map<Long, List<StatusLog>> requirementLogs(List<Requirement> rows) {
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        return logs.findByObjectTypeAndObjectIdInOrderByCreatedAtAsc(LogObjectType.REQUIREMENT,
                        rows.stream().map(Requirement::getId).collect(Collectors.toList()))
                .stream()
                .collect(Collectors.groupingBy(StatusLog::getObjectId, LinkedHashMap::new, Collectors.toList()));
    }

    private static LocalDateTime reachedAt(List<StatusLog> history) {
        for (StatusLog log : history) {
            if (RequirementStatus.ACCEPTED.name().equals(log.getToStatus())
                    || RequirementStatus.PUBLISHED.name().equals(log.getToStatus())) {
                return log.getCreatedAt();
            }
        }
        return null;
    }

    /** 阻塞时长：历史挂/解配对求和；仍阻塞的用 blockedAt 起算。 */
    private static double blockedDays(Requirement requirement, List<StatusLog> history, LocalDateTime now) {
        LocalDateTime blockedSince = null;
        double days = 0;
        for (StatusLog log : history) {
            String comment = log.getComment();
            if (comment == null) {
                continue;
            }
            if (comment.startsWith("挂阻塞")) {
                blockedSince = log.getCreatedAt();
            } else if (comment.startsWith("解除阻塞") && blockedSince != null) {
                days += hours(blockedSince, log.getCreatedAt()) / 24.0;
                blockedSince = null;
            }
        }
        if (blockedSince != null) {
            days += hours(blockedSince, now) / 24.0;
        } else if (Boolean.TRUE.equals(requirement.getBlocked()) && requirement.getBlockedAt() != null) {
            days += hours(requirement.getBlockedAt(), now) / 24.0;
        }
        return Math.max(days, 0);
    }

    private static double hours(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        return Duration.between(from, to).toMinutes() / 60.0;
    }

    private static double loadPercent(BigDecimal effort, long devCount, int workdays) {
        double capacity = devCount * (double) workdays;
        if (capacity <= 0) {
            return 0;
        }
        return round1(effort.doubleValue() * 100.0 / capacity);
    }

    private static double rate(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return round1(part * 100.0 / total);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int workdays(LocalDate start, LocalDate end) {
        int count = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private static boolean isDone(RequirementStatus status) {
        return status == RequirementStatus.ACCEPTED || status == RequirementStatus.PUBLISHED;
    }
}
