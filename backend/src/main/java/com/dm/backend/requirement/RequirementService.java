package com.dm.backend.requirement;

import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.TaskDependency;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.RequirementType;
import com.dm.backend.entity.enums.SprintStatus;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TaskDependencyRepository;
import com.dm.backend.repository.TaskRepository;
import com.dm.backend.status.StatusLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求全生命周期：需求池、评审、排期/插队、迭代内状态机、阻塞覆盖层。
 * 所有状态变更必须经 applyTransition，保证写状态日志（设计文档 §5 校验清单）。
 */
@Service
public class RequirementService {

    private static final BigDecimal HALF_DAY = new BigDecimal("0.5");

    /** 需求状态机：允许的迁移。 */
    private static final Map<RequirementStatus, Set<RequirementStatus>> ALLOWED = allowedTransitions();

    private final RequirementRepository requirements;
    private final SprintRepository sprints;
    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final SysUserRepository users;
    private final StatusLogService statusLogs;

    public RequirementService(RequirementRepository requirements, SprintRepository sprints,
                              TaskRepository tasks, TaskDependencyRepository dependencies,
                              SysUserRepository users, StatusLogService statusLogs) {
        this.requirements = requirements;
        this.sprints = sprints;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.users = users;
        this.statusLogs = statusLogs;
    }

    // ---------- 查询 ----------

    /** sprintId 为空 = 需求池；否则为该迭代内需求。已取消的需求不出现在列表。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long teamId, Long sprintId, RequirementType type,
                                         RequirementStatus status, Long createdBy, String keyword) {
        if (sprintId != null) {
            Sprint sprint = requireSprint(sprintId);
            if (!sprint.getTeamId().equals(teamId)) {
                throw ApiException.notFound("迭代不存在");
            }
        }
        List<Requirement> rows = sprintId == null
                ? requirements.findByTeamIdAndSprintIdIsNullOrderByPoolSeqAsc(teamId)
                : requirements.findBySprintIdOrderByPoolSeqAsc(sprintId);
        List<Requirement> filtered = rows.stream()
                .filter(r -> r.getStatus() != RequirementStatus.CANCELLED)
                .filter(r -> type == null || r.getType() == type)
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> createdBy == null || createdBy.equals(r.getCreatedBy()))
                .filter(r -> matchesKeyword(r, keyword))
                .collect(Collectors.toList());
        return toViews(filtered);
    }

    @Transactional(readOnly = true)
    public Requirement get(Long id) {
        return require(id);
    }

    /** 本队全部需求（跨迭代，含池内）：测试队列、跨迭代筛选用。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll(Long teamId, RequirementType type, RequirementStatus status,
                                             Long createdBy, String keyword) {
        List<Requirement> filtered = requirements.findByTeamIdOrderByIdAsc(teamId).stream()
                .filter(r -> r.getStatus() != RequirementStatus.CANCELLED)
                .filter(r -> type == null || r.getType() == type)
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> createdBy == null || createdBy.equals(r.getCreatedBy()))
                .filter(r -> matchesKeyword(r, keyword))
                .collect(Collectors.toList());
        return toViews(filtered);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Requirement requirement = require(id);
        List<Task> taskRows = tasks.findByRequirementIdOrderBySortAsc(id);
        Map<String, Object> view = view(requirement, userNames(), taskRows, dependencyIdsByTask(taskRows));
        view.put("history", statusLogs.history(LogObjectType.REQUIREMENT, id).stream()
                .map(log -> Responses.map(
                        "fromStatus", log.getFromStatus(),
                        "toStatus", log.getToStatus(),
                        "comment", log.getComment(),
                        "operatorId", log.getOperatorId(),
                        "createdAt", log.getCreatedAt()))
                .collect(Collectors.toList()));
        return view;
    }

    // ---------- 需求池 ----------

    @Transactional
    public Requirement create(Long teamId, Long operatorId, String title, String description,
                              RequirementType type, BigDecimal estimate,
                              String acceptanceCriteria, String externalNote) {
        if (isBlank(title)) {
            throw ApiException.badRequest("需求标题必填");
        }
        if (type == null) {
            throw ApiException.badRequest("需求类型必填");
        }
        validateEstimate(estimate);
        Requirement requirement = new Requirement();
        requirement.setTeamId(teamId);
        requirement.setTitle(title.trim());
        requirement.setDescription(trimToNull(description));
        requirement.setType(type);
        requirement.setEstimate(estimate);
        requirement.setAcceptanceCriteria(trimToNull(acceptanceCriteria));
        requirement.setExternalNote(trimToNull(externalNote));
        requirement.setCreatedBy(operatorId);
        requirement.setStatus(RequirementStatus.PENDING_REVIEW);
        requirement.setPoolSeq(nextPoolSeq(teamId));
        Requirement saved = requirements.save(requirement);
        statusLogs.record(LogObjectType.REQUIREMENT, saved.getId(), null,
                RequirementStatus.PENDING_REVIEW.name(), "创建需求", operatorId);
        return saved;
    }

    /** 编辑需求：仅池内（待评审/已评审）可改。 */
    @Transactional
    public Requirement update(Long id, String title, String description, RequirementType type,
                              BigDecimal estimate, String acceptanceCriteria, String externalNote) {
        Requirement requirement = require(id);
        ensurePoolOnly(requirement, "需求已排入迭代，不能在需求池修改");
        if (!isBlank(title)) {
            requirement.setTitle(title.trim());
        }
        requirement.setDescription(trimToNull(description));
        if (type != null) {
            requirement.setType(type);
        }
        if (estimate != null) {
            validateEstimate(estimate);
            requirement.setEstimate(estimate);
        }
        requirement.setAcceptanceCriteria(trimToNull(acceptanceCriteria));
        requirement.setExternalNote(trimToNull(externalNote));
        return requirements.save(requirement);
    }

    /**
     * 登记评审结论（线下评审）。
     * 通过 → 已评审，且验收标准必填；退回 → 状态不变，意见必填（状态日志自环留痕）。
     */
    @Transactional
    public Requirement review(Long id, boolean approved, String comment, Long operatorId) {
        Requirement requirement = require(id);
        ensurePoolOnly(requirement, "只有需求池中的需求可以登记评审结果");
        if (requirement.getStatus() != RequirementStatus.PENDING_REVIEW) {
            throw ApiException.conflict("只有待评审的需求可以登记评审结果");
        }
        if (approved) {
            if (isBlank(requirement.getAcceptanceCriteria())) {
                throw ApiException.badRequest("请先填写验收标准，再登记评审通过");
            }
            applyTransition(requirement, RequirementStatus.REVIEWED, operatorId,
                    isBlank(comment) ? "评审通过" : "评审通过：" + comment.trim());
        } else {
            if (isBlank(comment)) {
                throw ApiException.badRequest("评审退回必须填写意见");
            }
            statusLogs.record(LogObjectType.REQUIREMENT, requirement.getId(),
                    requirement.getStatus().name(), requirement.getStatus().name(),
                    "评审退回：" + comment.trim(), operatorId);
        }
        return requirement;
    }

    /** 拖拽定序：ids 必须与当前池内需求完全一致，事务内重写 poolSeq = 1..n。 */
    @Transactional
    public void reorderPool(Long teamId, List<Long> ids) {
        Map<Long, Requirement> pool = requirements.findByTeamIdAndSprintIdIsNullOrderByPoolSeqAsc(teamId).stream()
                .filter(r -> r.getStatus() != RequirementStatus.CANCELLED)
                .collect(Collectors.toMap(Requirement::getId, r -> r, (a, b) -> a, LinkedHashMap::new));
        if (ids == null || !new LinkedHashSet<>(ids).equals(new LinkedHashSet<>(pool.keySet()))) {
            throw ApiException.badRequest("排序列表与当前需求池不一致，请刷新后重试");
        }
        int seq = 1;
        for (Long id : ids) {
            Requirement requirement = pool.get(id);
            requirement.setPoolSeq(seq++);
            requirements.save(requirement);
        }
    }

    // ---------- 排期与迭代内流转 ----------

    @Transactional
    public Requirement schedule(Long id, Long sprintId, boolean urgent, Long operatorId) {
        Requirement requirement = require(id);
        if (requirement.getStatus() != RequirementStatus.REVIEWED) {
            throw ApiException.conflict("只有已评审的需求可以排入迭代");
        }
        Sprint sprint = requireSprint(sprintId);
        if (!sprint.getTeamId().equals(requirement.getTeamId())) {
            throw ApiException.badRequest("迭代与需求不属于同一小队");
        }
        if (sprint.getStatus() == SprintStatus.CLOSED) {
            throw ApiException.conflict("迭代已关闭，不能再排入需求");
        }
        if (sprint.getStatus() == SprintStatus.ACTIVE && !urgent) {
            throw ApiException.badRequest("迭代进行中，排入需求请使用插队");
        }
        if (sprint.getStatus() == SprintStatus.PLANNED && urgent) {
            throw ApiException.badRequest("迭代尚未开始，无需插队");
        }
        requirement.setSprintId(sprint.getId());
        requirement.setPoolSeq(null);
        requirement.setUrgent(urgent);
        applyTransition(requirement, RequirementStatus.PLANNED, operatorId,
                (urgent ? "插队入迭代 " : "排入迭代 ") + sprint.getName());
        return requirement;
    }

    @Transactional
    public Requirement unschedule(Long id, Long operatorId) {
        Requirement requirement = require(id);
        if (requirement.getSprintId() == null) {
            throw ApiException.conflict("需求不在迭代中");
        }
        Sprint sprint = requireSprint(requirement.getSprintId());
        if (sprint.getStatus() != SprintStatus.PLANNED) {
            throw ApiException.conflict("迭代已开始，不能移出需求");
        }
        requirement.setSprintId(null);
        requirement.setUrgent(false);
        applyTransition(requirement, RequirementStatus.REVIEWED, operatorId, "移出迭代 " + sprint.getName());
        requirement.setPoolSeq(nextPoolSeq(requirement.getTeamId()));
        return requirements.save(requirement);
    }

    @Transactional
    public Requirement cancel(Long id, Long operatorId) {
        Requirement requirement = require(id);
        applyTransition(requirement, RequirementStatus.CANCELLED, operatorId, "取消需求");
        return requirement;
    }

    @Transactional
    public Requirement startDev(Long id, Long operatorId) {
        return move(id, RequirementStatus.DEV, operatorId, "开始开发");
    }

    @Transactional
    public Requirement readyForTest(Long id, Long operatorId) {
        return move(id, RequirementStatus.READY_FOR_TEST, operatorId, "开发完成，转待测试");
    }

    @Transactional
    public Requirement claimForTest(Long id, Long operatorId) {
        return move(id, RequirementStatus.TESTING, operatorId, "测试领取");
    }

    @Transactional
    public Requirement accept(Long id, Long operatorId) {
        return move(id, RequirementStatus.ACCEPTED, operatorId, "测试通过");
    }

    @Transactional
    public Requirement reject(Long id, String comment, Long operatorId) {
        if (isBlank(comment)) {
            throw ApiException.badRequest("测试退回必须填写原因");
        }
        return move(id, RequirementStatus.DEV, operatorId, "验收不通过：" + comment.trim());
    }

    @Transactional
    public Requirement publish(Long id, Long operatorId) {
        return move(id, RequirementStatus.PUBLISHED, operatorId, "标记已发布");
    }

    // ---------- 阻塞覆盖层 ----------

    @Transactional
    public Requirement block(Long id, String reason, Long operatorId) {
        Requirement requirement = require(id);
        if (Boolean.TRUE.equals(requirement.getBlocked())) {
            throw ApiException.conflict("需求已处于阻塞状态");
        }
        if (isBlank(reason)) {
            throw ApiException.badRequest("挂阻塞必须填写原因");
        }
        ensureSprintMutable(requirement);
        requirement.setBlocked(true);
        requirement.setBlockedReason(reason.trim());
        requirement.setBlockedAt(LocalDateTime.now());
        requirements.save(requirement);
        statusLogs.record(LogObjectType.REQUIREMENT, id, requirement.getStatus().name(),
                requirement.getStatus().name(), "挂阻塞：" + reason.trim(), operatorId);
        return requirement;
    }

    @Transactional
    public Requirement unblock(Long id, Long operatorId) {
        Requirement requirement = require(id);
        if (!Boolean.TRUE.equals(requirement.getBlocked())) {
            throw ApiException.conflict("需求当前未处于阻塞状态");
        }
        ensureSprintMutable(requirement);
        String reason = requirement.getBlockedReason();
        requirement.setBlocked(false);
        requirement.setBlockedReason(null);
        requirement.setBlockedAt(null);
        requirements.save(requirement);
        statusLogs.record(LogObjectType.REQUIREMENT, id, requirement.getStatus().name(),
                requirement.getStatus().name(),
                "解除阻塞" + (reason == null ? "" : "（原原因：" + reason + "）"), operatorId);
        return requirement;
    }

    // ---------- 内部 ----------

    private Requirement move(Long id, RequirementStatus target, Long operatorId, String comment) {
        Requirement requirement = require(id);
        applyTransition(requirement, target, operatorId, comment);
        return requirement;
    }

    private void applyTransition(Requirement requirement, RequirementStatus target,
                                 Long operatorId, String comment) {
        RequirementStatus from = requirement.getStatus();
        if (!ALLOWED.getOrDefault(from, Collections.emptySet()).contains(target)) {
            throw ApiException.conflict("当前状态不允许该操作：" + from + " → " + target);
        }
        ensureSprintMutable(requirement);
        requirement.setStatus(target);
        if (target == RequirementStatus.CANCELLED) {
            requirement.setPoolSeq(null);
            requirement.setUrgent(false);
        }
        requirements.save(requirement);
        statusLogs.record(LogObjectType.REQUIREMENT, requirement.getId(),
                from.name(), target.name(), comment, operatorId);
    }

    /** 迭代关闭后需求只读（规则 9）。 */
    private void ensureSprintMutable(Requirement requirement) {
        if (requirement.getSprintId() == null) {
            return;
        }
        Sprint sprint = requireSprint(requirement.getSprintId());
        if (sprint.getStatus() == SprintStatus.CLOSED) {
            throw ApiException.conflict("迭代已关闭，需求只读；如需变更请先重新打开迭代");
        }
    }

    private void ensurePoolOnly(Requirement requirement, String message) {
        if (requirement.getSprintId() != null) {
            throw ApiException.conflict(message);
        }
        if (requirement.getStatus() == RequirementStatus.CANCELLED) {
            throw ApiException.conflict("需求已取消");
        }
    }

    private Requirement require(Long id) {
        return requirements.findById(id).orElseThrow(() -> ApiException.notFound("需求不存在"));
    }

    private Sprint requireSprint(Long sprintId) {
        if (sprintId == null) {
            throw ApiException.badRequest("请选择迭代");
        }
        return sprints.findById(sprintId).orElseThrow(() -> ApiException.notFound("迭代不存在"));
    }

    private int nextPoolSeq(Long teamId) {
        return requirements.findByTeamIdAndSprintIdIsNullOrderByPoolSeqAsc(teamId).stream()
                .map(Requirement::getPoolSeq)
                .filter(seq -> seq != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private void validateEstimate(BigDecimal estimate) {
        if (estimate == null || estimate.compareTo(HALF_DAY) < 0) {
            throw ApiException.badRequest("预估人日必填，且不小于 0.5");
        }
        if (estimate.remainder(HALF_DAY).compareTo(BigDecimal.ZERO) != 0) {
            throw ApiException.badRequest("预估人日必须为 0.5 的倍数");
        }
    }

    private List<Map<String, Object>> toViews(List<Requirement> rows) {
        Map<Long, String> names = userNames();
        List<Task> allTasks = rows.isEmpty() ? Collections.emptyList()
                : tasks.findByRequirementIdInOrderBySortAsc(rows.stream()
                        .map(Requirement::getId).collect(Collectors.toList()));
        Map<Long, List<Task>> tasksByRequirement = allTasks.stream()
                .collect(Collectors.groupingBy(Task::getRequirementId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Long>> dependencyIds = dependencyIdsByTask(allTasks);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Requirement requirement : rows) {
            result.add(view(requirement, names,
                    tasksByRequirement.getOrDefault(requirement.getId(), Collections.emptyList()),
                    dependencyIds));
        }
        return result;
    }

    private Map<Long, List<Long>> dependencyIdsByTask(List<Task> taskRows) {
        if (taskRows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<Long>> map = new HashMap<>();
        for (TaskDependency dependency : dependencies.findByTaskIdIn(
                taskRows.stream().map(Task::getId).collect(Collectors.toList()))) {
            map.computeIfAbsent(dependency.getTaskId(), key -> new ArrayList<>())
                    .add(dependency.getDependsOnTaskId());
        }
        return map;
    }

    private Map<String, Object> view(Requirement requirement, Map<Long, String> names,
                                     List<Task> taskRows, Map<Long, List<Long>> dependencyIds) {
        return Responses.map(
                "id", requirement.getId(),
                "teamId", requirement.getTeamId(),
                "title", requirement.getTitle(),
                "description", requirement.getDescription(),
                "type", requirement.getType(),
                "status", requirement.getStatus(),
                "estimate", requirement.getEstimate(),
                "urgent", requirement.getUrgent(),
                "blocked", requirement.getBlocked(),
                "blockedReason", requirement.getBlockedReason(),
                "blockedAt", requirement.getBlockedAt(),
                "poolSeq", requirement.getPoolSeq(),
                "sprintId", requirement.getSprintId(),
                "acceptanceCriteria", requirement.getAcceptanceCriteria(),
                "externalNote", requirement.getExternalNote(),
                "createdBy", requirement.getCreatedBy(),
                "createdByName", names.get(requirement.getCreatedBy()),
                "createdAt", requirement.getCreatedAt(),
                "tasks", taskRows.stream().map(task -> Responses.map(
                        "id", task.getId(),
                        "title", task.getTitle(),
                        "type", task.getType(),
                        "status", task.getStatus(),
                        "assigneeId", task.getAssigneeId(),
                        "assigneeName", task.getAssigneeId() == null ? null : names.get(task.getAssigneeId()),
                        "blocked", task.getBlocked(),
                        "progressNote", task.getProgressNote(),
                        "plannedStartDate", task.getPlannedStartDate(),
                        "plannedEndDate", task.getPlannedEndDate(),
                        "completedAt", task.getCompletedAt(),
                        "dependsOnTaskIds", dependencyIds.getOrDefault(task.getId(), Collections.emptyList())))
                        .collect(Collectors.toList()));
    }

    private Map<Long, String> userNames() {
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(user -> names.put(user.getId(), user.getName()));
        return names;
    }

    private static boolean matchesKeyword(Requirement requirement, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return true;
        }
        return requirement.getTitle().toLowerCase().contains(keyword.trim().toLowerCase());
    }

    private static Map<RequirementStatus, Set<RequirementStatus>> allowedTransitions() {
        Map<RequirementStatus, Set<RequirementStatus>> map = new EnumMap<>(RequirementStatus.class);
        map.put(RequirementStatus.PENDING_REVIEW,
                EnumSet.of(RequirementStatus.REVIEWED, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.REVIEWED,
                EnumSet.of(RequirementStatus.PLANNED, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.PLANNED,
                EnumSet.of(RequirementStatus.DEV, RequirementStatus.REVIEWED, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.DEV,
                EnumSet.of(RequirementStatus.READY_FOR_TEST, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.READY_FOR_TEST,
                EnumSet.of(RequirementStatus.TESTING, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.TESTING,
                EnumSet.of(RequirementStatus.ACCEPTED, RequirementStatus.DEV, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.ACCEPTED,
                EnumSet.of(RequirementStatus.PUBLISHED, RequirementStatus.CANCELLED));
        map.put(RequirementStatus.PUBLISHED, EnumSet.noneOf(RequirementStatus.class));
        map.put(RequirementStatus.CANCELLED, EnumSet.noneOf(RequirementStatus.class));
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
