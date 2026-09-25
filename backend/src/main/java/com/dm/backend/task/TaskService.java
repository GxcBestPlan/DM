package com.dm.backend.task;

import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.TaskDependency;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.SprintStatus;
import com.dm.backend.entity.enums.TaskStatus;
import com.dm.backend.entity.enums.TaskType;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TaskDependencyRepository;
import com.dm.backend.repository.TaskRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.requirement.RequirementService;
import com.dm.backend.status.StatusLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务拆解、依赖、状态推进与管道排期。
 * 自动流转（D1/D2）：首个任务开始 → 需求转开发中；全部任务完成 → 需求转待测试（无需接口调用者关心）。
 */
@Service
public class TaskService {

    private final TaskRepository tasks;
    private final TaskDependencyRepository dependencies;
    private final RequirementRepository requirements;
    private final SprintRepository sprints;
    private final TeamMemberRepository members;
    private final SysUserRepository users;
    private final StatusLogService statusLogs;
    private final RequirementService requirementService;

    public TaskService(TaskRepository tasks, TaskDependencyRepository dependencies,
                       RequirementRepository requirements, SprintRepository sprints,
                       TeamMemberRepository members, SysUserRepository users,
                       StatusLogService statusLogs, RequirementService requirementService) {
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.requirements = requirements;
        this.sprints = sprints;
        this.members = members;
        this.users = users;
        this.statusLogs = statusLogs;
        this.requirementService = requirementService;
    }

    // ---------- 拆解 ----------

    @Transactional
    public Task create(Long requirementId, TaskType type, String title, Long assigneeId, Integer sort,
                       LocalDate plannedStart, LocalDate plannedEnd, Long operatorId) {
        Requirement requirement = requireRequirement(requirementId);
        Sprint sprint = requireMutableSprint(requirement);
        if (isBlank(title)) {
            throw ApiException.badRequest("任务标题必填");
        }
        if (type == null) {
            throw ApiException.badRequest("任务类型必填");
        }
        if (assigneeId != null) {
            requireTeamMember(sprint.getTeamId(), assigneeId);
        }
        validateDates(sprint, plannedStart, plannedEnd);

        Task task = new Task();
        task.setRequirementId(requirementId);
        task.setType(type);
        task.setTitle(title.trim());
        task.setAssigneeId(assigneeId);
        task.setSort(sort != null ? sort : nextSort(requirementId));
        task.setPlannedStartDate(plannedStart);
        task.setPlannedEndDate(plannedEnd);
        task.setStatus(TaskStatus.TODO);
        Task saved = tasks.save(task);
        statusLogs.record(LogObjectType.TASK, saved.getId(), null, TaskStatus.TODO.name(), "创建任务", operatorId);
        return saved;
    }

    @Transactional
    public Task update(Long taskId, String title, Long assigneeId, Integer sort,
                       LocalDate plannedStart, LocalDate plannedEnd, Long operatorId) {
        Task task = requireTask(taskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        Sprint sprint = requireMutableSprint(requirement);
        if (!isBlank(title)) {
            task.setTitle(title.trim());
        }
        if (assigneeId != null && !assigneeId.equals(task.getAssigneeId())) {
            requireTeamMember(sprint.getTeamId(), assigneeId);
            String fromName = userName(task.getAssigneeId());
            task.setAssigneeId(assigneeId);
            statusLogs.record(LogObjectType.TASK, taskId, task.getStatus().name(), task.getStatus().name(),
                    "改派：" + (fromName == null ? "未分配" : fromName) + " → " + userName(assigneeId), operatorId);
        }
        if (sort != null) {
            task.setSort(sort);
        }
        if (plannedStart != null || plannedEnd != null) {
            validateDates(sprint, plannedStart, plannedEnd);
            task.setPlannedStartDate(plannedStart);
            task.setPlannedEndDate(plannedEnd);
        }
        return tasks.save(task);
    }

    @Transactional
    public void delete(Long taskId, Long operatorId) {
        Task task = requireTask(taskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        requireMutableSprint(requirement);
        dependencies.deleteAll(dependencies.findByTaskId(taskId));
        dependencies.deleteAll(dependencies.findByDependsOnTaskId(taskId));
        tasks.delete(task);
        statusLogs.record(LogObjectType.TASK, taskId, task.getStatus().name(), task.getStatus().name(),
                "删除任务：" + task.getTitle(), operatorId);
    }

    // ---------- 依赖（仅前端任务依赖后端任务，仅提示不阻断） ----------

    @Transactional
    public void addDependency(Long taskId, Long dependsOnTaskId) {
        if (taskId.equals(dependsOnTaskId)) {
            throw ApiException.badRequest("任务不能依赖自己");
        }
        Task task = requireTask(taskId);
        Task dependsOn = requireTask(dependsOnTaskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        requireMutableSprint(requirement);
        if (!task.getRequirementId().equals(dependsOn.getRequirementId())) {
            throw ApiException.badRequest("只能依赖同一需求下的任务");
        }
        if (task.getType() != TaskType.FRONTEND || dependsOn.getType() != TaskType.BACKEND) {
            throw ApiException.badRequest("仅允许前端任务依赖后端任务");
        }
        if (dependencies.existsByTaskIdAndDependsOnTaskId(taskId, dependsOnTaskId)) {
            throw ApiException.conflict("该依赖已存在");
        }
        if (reaches(dependsOnTaskId, taskId, new HashSet<>())) {
            throw ApiException.badRequest("存在循环依赖");
        }
        TaskDependency dependency = new TaskDependency();
        dependency.setTaskId(taskId);
        dependency.setDependsOnTaskId(dependsOnTaskId);
        dependencies.save(dependency);
    }

    @Transactional
    public void removeDependency(Long taskId, Long dependsOnTaskId) {
        TaskDependency dependency = dependencies.findByTaskId(taskId).stream()
                .filter(d -> d.getDependsOnTaskId().equals(dependsOnTaskId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("依赖不存在"));
        dependencies.delete(dependency);
    }

    // ---------- 状态推进 ----------

    @Transactional
    public Map<String, Object> start(Long taskId, Long operatorId) {
        Task task = requireTask(taskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        Sprint sprint = requireSprint(requirement.getSprintId());
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw ApiException.conflict("迭代尚未开始，任务不能开始");
        }
        if (task.getStatus() != TaskStatus.TODO) {
            throw ApiException.conflict("只有待开始的任务可以开始");
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        tasks.save(task);
        statusLogs.record(LogObjectType.TASK, taskId, TaskStatus.TODO.name(), TaskStatus.IN_PROGRESS.name(),
                "开始任务", operatorId);

        // D1：首个任务开始，需求自动从"已排期"转"开发中"
        if (requirement.getStatus() == RequirementStatus.PLANNED) {
            requirementService.startDev(requirement.getId(), null);
        }
        return Responses.map("task", view(task, requirement, sprintName(requirement), userNames(),
                dependencyIdsByTask(Collections.singletonList(task))));
    }

    @Transactional
    public Map<String, Object> complete(Long taskId, Long operatorId) {
        Task task = requireTask(taskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        Sprint sprint = requireSprint(requirement.getSprintId());
        if (sprint.getStatus() != SprintStatus.ACTIVE) {
            throw ApiException.conflict("迭代尚未开始，任务不能完成");
        }
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw ApiException.conflict("只有进行中的任务可以完成");
        }
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(LocalDateTime.now());
        tasks.save(task);
        statusLogs.record(LogObjectType.TASK, taskId, TaskStatus.IN_PROGRESS.name(), TaskStatus.DONE.name(),
                "完成任务", operatorId);

        // 依赖仅提示不阻断（规则 5）
        List<Task> pending = unmetDependencies(task);
        String warning = pending.isEmpty() ? null
                : "依赖任务尚未完成：" + pending.stream().map(Task::getTitle).collect(Collectors.joining("、"));

        // D2：全部任务完成，需求自动转"待测试"
        if (requirement.getStatus() == RequirementStatus.DEV && allTasksDone(requirement.getId())) {
            requirementService.readyForTest(requirement.getId(), null);
        }
        return Responses.map("task", view(task, requirement, sprintName(requirement), userNames(),
                dependencyIdsByTask(Collections.singletonList(task))), "warning", warning);
    }

    @Transactional
    public Task writeProgress(Long taskId, String note) {
        if (isBlank(note)) {
            throw ApiException.badRequest("进展备注不能为空");
        }
        Task task = requireTask(taskId);
        requireMutableSprint(requireRequirement(task.getRequirementId()));
        task.setProgressNote(note.trim());
        return tasks.save(task);
    }

    @Transactional
    public Task block(Long taskId, String reason, Long operatorId) {
        Task task = requireTask(taskId);
        requireMutableSprint(requireRequirement(task.getRequirementId()));
        if (Boolean.TRUE.equals(task.getBlocked())) {
            throw ApiException.conflict("任务已处于阻塞状态");
        }
        if (isBlank(reason)) {
            throw ApiException.badRequest("挂阻塞必须填写原因");
        }
        task.setBlocked(true);
        task.setBlockedReason(reason.trim());
        task.setBlockedAt(LocalDateTime.now());
        tasks.save(task);
        statusLogs.record(LogObjectType.TASK, taskId, task.getStatus().name(), task.getStatus().name(),
                "挂阻塞：" + reason.trim(), operatorId);
        return task;
    }

    @Transactional
    public Task unblock(Long taskId, Long operatorId) {
        Task task = requireTask(taskId);
        requireMutableSprint(requireRequirement(task.getRequirementId()));
        if (!Boolean.TRUE.equals(task.getBlocked())) {
            throw ApiException.conflict("任务当前未处于阻塞状态");
        }
        String reason = task.getBlockedReason();
        task.setBlocked(false);
        task.setBlockedReason(null);
        task.setBlockedAt(null);
        tasks.save(task);
        statusLogs.record(LogObjectType.TASK, taskId, task.getStatus().name(), task.getStatus().name(),
                "解除阻塞" + (reason == null ? "" : "（原原因：" + reason + "）"), operatorId);
        return task;
    }

    // ---------- 管道排期 ----------

    @Transactional
    public Task schedule(Long taskId, Long assigneeId, LocalDate plannedStart, LocalDate plannedEnd) {
        Task task = requireTask(taskId);
        Requirement requirement = requireRequirement(task.getRequirementId());
        Sprint sprint = requireMutableSprint(requirement);
        if (assigneeId == null) {
            throw ApiException.badRequest("请选择负责人");
        }
        requireTeamMember(sprint.getTeamId(), assigneeId);
        validateDates(sprint, plannedStart, plannedEnd);
        task.setAssigneeId(assigneeId);
        task.setPlannedStartDate(plannedStart);
        task.setPlannedEndDate(plannedEnd);
        return tasks.save(task);
    }

    /** 人员 × 日期管道视图：含未排期任务与"同一人同日多任务"冲突。 */
    @Transactional(readOnly = true)
    public Map<String, Object> pipeline(Long sprintId) {
        Sprint sprint = requireSprint(sprintId);
        List<Requirement> rows = requirements.findBySprintIdOrderByPoolSeqAsc(sprintId).stream()
                .filter(r -> r.getStatus() != RequirementStatus.CANCELLED)
                .collect(Collectors.toList());
        Map<Long, Requirement> requirementById = rows.stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r, (a, b) -> a, LinkedHashMap::new));
        List<Task> all = rows.isEmpty() ? Collections.emptyList()
                : tasks.findByRequirementIdInOrderBySortAsc(requirementById.keySet());
        Map<Long, List<Long>> dependencyIds = dependencyIdsByTask(all);
        Map<Long, String> names = userNames();

        Set<Long> people = members.findByTeamId(sprint.getTeamId()).stream()
                .filter(m -> m.getRole() == MemberRole.FRONTEND_DEV || m.getRole() == MemberRole.BACKEND_DEV)
                .map(TeamMember::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        all.stream().map(Task::getAssigneeId).filter(Objects::nonNull).forEach(people::add);

        List<Map<String, Object>> peopleViews = new ArrayList<>();
        for (Long userId : people) {
            List<Map<String, Object>> taskViews = all.stream()
                    .filter(t -> userId.equals(t.getAssigneeId()))
                    .filter(TaskService::isScheduled)
                    .map(t -> view(t, requirementById.get(t.getRequirementId()), sprint.getName(), names, dependencyIds))
                    .collect(Collectors.toList());
            peopleViews.add(Responses.map(
                    "userId", userId,
                    "name", names.get(userId),
                    "tasks", taskViews));
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Long userId : people) {
            for (LocalDate day = sprint.getStartDate(); !day.isAfter(sprint.getEndDate()); day = day.plusDays(1)) {
                final LocalDate currentDay = day;
                List<Long> idsOnDay = all.stream()
                        .filter(t -> userId.equals(t.getAssigneeId()))
                        .filter(TaskService::isScheduled)
                        .filter(t -> covers(t, currentDay))
                        .map(Task::getId).collect(Collectors.toList());
                if (idsOnDay.size() > 1) {
                    conflicts.add(Responses.map("userId", userId, "date", currentDay, "taskIds", idsOnDay));
                }
            }
        }

        List<Map<String, Object>> unplanned = all.stream()
                .filter(t -> !isScheduled(t))
                .map(t -> view(t, requirementById.get(t.getRequirementId()), sprint.getName(), names, dependencyIds))
                .collect(Collectors.toList());

        List<String> days = new ArrayList<>();
        for (LocalDate day = sprint.getStartDate(); !day.isAfter(sprint.getEndDate()); day = day.plusDays(1)) {
            days.add(day.toString());
        }

        return Responses.map(
                "sprint", Responses.map(
                        "id", sprint.getId(), "name", sprint.getName(),
                        "startDate", sprint.getStartDate(), "endDate", sprint.getEndDate(),
                        "status", sprint.getStatus()),
                "days", days,
                "people", peopleViews,
                "conflicts", conflicts,
                "unplanned", unplanned);
    }

    // ---------- 我的任务 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myTasks(Long userId, boolean todayOnly) {
        List<Task> mine = tasks.findByAssigneeIdOrderByPlannedStartDateAsc(userId);
        if (mine.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Requirement> requirementById = requirements.findAllById(mine.stream()
                        .map(Task::getRequirementId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));
        Set<Long> sprintIds = requirementById.values().stream()
                .map(Requirement::getSprintId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Sprint> sprintById = sprintIds.isEmpty() ? Collections.emptyMap()
                : sprints.findAllById(sprintIds).stream().collect(Collectors.toMap(Sprint::getId, s -> s));
        Map<Long, String> names = userNames();
        Map<Long, List<Long>> dependencyIds = dependencyIdsByTask(mine);
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Task task : mine) {
            Requirement requirement = requirementById.get(task.getRequirementId());
            if (requirement == null || requirement.getSprintId() == null) {
                continue;
            }
            Sprint sprint = sprintById.get(requirement.getSprintId());
            if (sprint == null || sprint.getStatus() == SprintStatus.CLOSED) {
                continue;
            }
            if (todayOnly && !isTodayTodo(task, today)) {
                continue;
            }
            result.add(view(task, requirement, sprint.getName(), names, dependencyIds));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByRequirement(Long requirementId) {
        Requirement requirement = requireRequirement(requirementId);
        List<Task> rows = tasks.findByRequirementIdOrderBySortAsc(requirementId);
        return rows.stream()
                .map(t -> view(t, requirement, sprintName(requirement), userNames(), dependencyIdsByTask(rows)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Task get(Long taskId) {
        return requireTask(taskId);
    }

    @Transactional(readOnly = true)
    public Long teamIdOfTask(Long taskId) {
        return requireRequirement(requireTask(taskId).getRequirementId()).getTeamId();
    }

    @Transactional(readOnly = true)
    public Long teamIdOfRequirement(Long requirementId) {
        return requireRequirement(requirementId).getTeamId();
    }

    @Transactional(readOnly = true)
    public Long teamIdOfSprint(Long sprintId) {
        return requireSprint(sprintId).getTeamId();
    }

    // ---------- 内部 ----------

    private List<Task> unmetDependencies(Task task) {
        List<Long> dependsOnIds = dependencies.findByTaskId(task.getId()).stream()
                .map(TaskDependency::getDependsOnTaskId).collect(Collectors.toList());
        if (dependsOnIds.isEmpty()) {
            return Collections.emptyList();
        }
        return tasks.findAllById(dependsOnIds).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE)
                .collect(Collectors.toList());
    }

    private boolean allTasksDone(Long requirementId) {
        long total = tasks.countByRequirementId(requirementId);
        return total > 0 && tasks.countByRequirementIdAndStatus(requirementId, TaskStatus.DONE) == total;
    }

    /** 依赖成环校验：沿 task → dependsOn 方向看能否回到起点。 */
    private boolean reaches(Long fromTaskId, Long targetTaskId, Set<Long> visited) {
        if (fromTaskId.equals(targetTaskId)) {
            return true;
        }
        if (!visited.add(fromTaskId)) {
            return false;
        }
        for (TaskDependency dependency : dependencies.findByTaskId(fromTaskId)) {
            if (reaches(dependency.getDependsOnTaskId(), targetTaskId, visited)) {
                return true;
            }
        }
        return false;
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

    private Map<String, Object> view(Task task, Requirement requirement, String sprintName,
                                     Map<Long, String> names, Map<Long, List<Long>> dependencyIds) {
        return Responses.map(
                "id", task.getId(),
                "requirementId", task.getRequirementId(),
                "requirementTitle", requirement == null ? null : requirement.getTitle(),
                "requirementStatus", requirement == null ? null : requirement.getStatus(),
                "requirementUrgent", requirement != null && Boolean.TRUE.equals(requirement.getUrgent()),
                "sprintId", requirement == null ? null : requirement.getSprintId(),
                "sprintName", sprintName,
                "type", task.getType(),
                "title", task.getTitle(),
                "status", task.getStatus(),
                "assigneeId", task.getAssigneeId(),
                "assigneeName", task.getAssigneeId() == null ? null : names.get(task.getAssigneeId()),
                "sort", task.getSort(),
                "progressNote", task.getProgressNote(),
                "plannedStartDate", task.getPlannedStartDate(),
                "plannedEndDate", task.getPlannedEndDate(),
                "blocked", task.getBlocked(),
                "blockedReason", task.getBlockedReason(),
                "blockedAt", task.getBlockedAt(),
                "completedAt", task.getCompletedAt(),
                "dependsOnTaskIds", dependencyIds.getOrDefault(task.getId(), Collections.emptyList()));
    }

    private Map<Long, String> userNames() {
        Map<Long, String> names = new HashMap<>();
        users.findAll().forEach(user -> names.put(user.getId(), user.getName()));
        return names;
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId).map(SysUser::getName).orElse(null);
    }

    private String sprintName(Requirement requirement) {
        if (requirement.getSprintId() == null) {
            return null;
        }
        return sprints.findById(requirement.getSprintId()).map(Sprint::getName).orElse(null);
    }

    private void requireTeamMember(Long teamId, Long userId) {
        users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (members.findByTeamIdAndUserId(teamId, userId).isEmpty()) {
            throw ApiException.badRequest("该成员不在本小队");
        }
    }

    private void validateDates(Sprint sprint, LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return;
        }
        if (start == null || end == null) {
            throw ApiException.badRequest("计划起止日期需同时填写");
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("计划结束日期不能早于开始日期");
        }
        if (start.isBefore(sprint.getStartDate()) || end.isAfter(sprint.getEndDate())) {
            throw ApiException.badRequest("计划日期需在迭代周期内");
        }
    }

    private Sprint requireMutableSprint(Requirement requirement) {
        if (requirement.getStatus() == RequirementStatus.CANCELLED) {
            throw ApiException.conflict("需求已取消，任务只读");
        }
        if (requirement.getSprintId() == null) {
            throw ApiException.conflict("任务必须属于已排入迭代的需求，请先排期");
        }
        Sprint sprint = requireSprint(requirement.getSprintId());
        if (sprint.getStatus() == SprintStatus.CLOSED) {
            throw ApiException.conflict("迭代已关闭，任务只读；如需变更请先重新打开迭代");
        }
        return sprint;
    }

    private Sprint requireSprint(Long sprintId) {
        if (sprintId == null) {
            throw ApiException.conflict("需求尚未排入迭代");
        }
        return sprints.findById(sprintId).orElseThrow(() -> ApiException.notFound("迭代不存在"));
    }

    private Requirement requireRequirement(Long requirementId) {
        return requirements.findById(requirementId)
                .orElseThrow(() -> ApiException.notFound("需求不存在"));
    }

    private Task requireTask(Long taskId) {
        return tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("任务不存在"));
    }

    private int nextSort(Long requirementId) {
        return tasks.findByRequirementIdOrderBySortAsc(requirementId).stream()
                .map(Task::getSort).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
    }

    private static boolean isScheduled(Task task) {
        return task.getPlannedStartDate() != null && task.getPlannedEndDate() != null;
    }

    private static boolean covers(Task task, LocalDate day) {
        return !day.isBefore(task.getPlannedStartDate()) && !day.isAfter(task.getPlannedEndDate());
    }

    private static boolean isTodayTodo(Task task, LocalDate today) {
        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            return true;
        }
        return task.getStatus() == TaskStatus.TODO
                && task.getPlannedStartDate() != null
                && !task.getPlannedStartDate().isAfter(today);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
