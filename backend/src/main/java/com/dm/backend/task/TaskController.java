package com.dm.backend.task;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.auth.CurrentUser;
import com.dm.backend.common.ApiException;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.TaskType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 任务与管道接口（§9.5 / §9.6）。
 * 拆解/分配/排期/依赖 = 开发管理者；开始/完成/备注/阻塞 = 任务负责人或开发管理者。
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // ---------- 需求内任务 ----------

    @GetMapping("/requirements/{requirementId}/tasks")
    public List<Map<String, Object>> listByRequirement(@PathVariable Long requirementId) {
        CurrentUser user = AuthContext.require();
        user.requireTeamAccess(taskService.teamIdOfRequirement(requirementId));
        return taskService.listByRequirement(requirementId);
    }

    @PostMapping("/requirements/{requirementId}/tasks")
    public Task create(@PathVariable Long requirementId, @RequestBody TaskRequest request) {
        CurrentUser user = requireManager(taskService.teamIdOfRequirement(requirementId));
        return taskService.create(requirementId, request.getType(), request.getTitle(),
                request.getAssigneeId(), request.getSort(),
                request.getPlannedStartDate(), request.getPlannedEndDate(), user.getUserId());
    }

    @PutMapping("/tasks/{taskId}")
    public Task update(@PathVariable Long taskId, @RequestBody TaskRequest request) {
        CurrentUser user = requireManager(taskService.teamIdOfTask(taskId));
        return taskService.update(taskId, request.getTitle(), request.getAssigneeId(), request.getSort(),
                request.getPlannedStartDate(), request.getPlannedEndDate(), user.getUserId());
    }

    @DeleteMapping("/tasks/{taskId}")
    public void delete(@PathVariable Long taskId) {
        CurrentUser user = requireManager(taskService.teamIdOfTask(taskId));
        taskService.delete(taskId, user.getUserId());
    }

    // ---------- 依赖 ----------

    @PostMapping("/tasks/{taskId}/dependencies")
    public void addDependency(@PathVariable Long taskId, @RequestBody DependencyRequest request) {
        requireManager(taskService.teamIdOfTask(taskId));
        taskService.addDependency(taskId, request.getDependsOnTaskId());
    }

    @DeleteMapping("/tasks/{taskId}/dependencies/{dependsOnTaskId}")
    public void removeDependency(@PathVariable Long taskId, @PathVariable Long dependsOnTaskId) {
        requireManager(taskService.teamIdOfTask(taskId));
        taskService.removeDependency(taskId, dependsOnTaskId);
    }

    // ---------- 开发人员操作（负责人或开发管理者） ----------

    @PostMapping("/tasks/{taskId}/start")
    public Map<String, Object> start(@PathVariable Long taskId) {
        CurrentUser user = requireAssigneeOrManager(taskId);
        return taskService.start(taskId, user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Map<String, Object> complete(@PathVariable Long taskId) {
        CurrentUser user = requireAssigneeOrManager(taskId);
        return taskService.complete(taskId, user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Task writeProgress(@PathVariable Long taskId, @RequestBody NoteRequest request) {
        requireAssigneeOrManager(taskId);
        return taskService.writeProgress(taskId, request.getNote());
    }

    @PostMapping("/tasks/{taskId}/block")
    public Task block(@PathVariable Long taskId, @RequestBody ReasonRequest request) {
        CurrentUser user = requireAssigneeOrManager(taskId);
        return taskService.block(taskId, request.getReason(), user.getUserId());
    }

    @PostMapping("/tasks/{taskId}/unblock")
    public Task unblock(@PathVariable Long taskId) {
        CurrentUser user = requireAssigneeOrManager(taskId);
        return taskService.unblock(taskId, user.getUserId());
    }

    // ---------- 管道排期 ----------

    @PutMapping("/tasks/{taskId}/schedule")
    public Task schedule(@PathVariable Long taskId, @RequestBody ScheduleTaskRequest request) {
        requireManager(taskService.teamIdOfTask(taskId));
        return taskService.schedule(taskId, request.getAssigneeId(),
                request.getPlannedStartDate(), request.getPlannedEndDate());
    }

    @GetMapping("/sprints/{sprintId}/pipeline")
    public Map<String, Object> pipeline(@PathVariable Long sprintId) {
        CurrentUser user = AuthContext.require();
        user.requireTeamAccess(taskService.teamIdOfSprint(sprintId));
        return taskService.pipeline(sprintId);
    }

    // ---------- 我的任务 ----------

    /** scope=today 只返回今日待办（进行中 + 今日应开始），默认 all。 */
    @GetMapping("/tasks/mine")
    public List<Map<String, Object>> myTasks(@RequestParam(required = false, defaultValue = "all") String scope) {
        CurrentUser user = AuthContext.require();
        return taskService.myTasks(user.getUserId(), "today".equalsIgnoreCase(scope));
    }

    // ---------- 内部 ----------

    private CurrentUser requireManager(Long teamId) {
        CurrentUser user = AuthContext.require();
        user.requireAnyRole(MemberRole.DEV_MANAGER);
        user.requireTeamAccess(teamId);
        return user;
    }

    private CurrentUser requireAssigneeOrManager(Long taskId) {
        CurrentUser user = AuthContext.require();
        Task task = taskService.get(taskId);
        user.requireTeamAccess(taskService.teamIdOfTask(taskId));
        boolean manager = user.isAdmin() || user.hasTeamRole(MemberRole.DEV_MANAGER);
        if (!manager && !user.getUserId().equals(task.getAssigneeId())) {
            throw ApiException.forbidden("只有任务负责人或开发管理者可以操作");
        }
        return user;
    }

    // ---------- 请求体 ----------

    public static class TaskRequest {
        private TaskType type;
        private String title;
        private Long assigneeId;
        private Integer sort;
        private LocalDate plannedStartDate;
        private LocalDate plannedEndDate;

        public TaskType getType() {
            return type;
        }

        public void setType(TaskType type) {
            this.type = type;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Long getAssigneeId() {
            return assigneeId;
        }

        public void setAssigneeId(Long assigneeId) {
            this.assigneeId = assigneeId;
        }

        public Integer getSort() {
            return sort;
        }

        public void setSort(Integer sort) {
            this.sort = sort;
        }

        public LocalDate getPlannedStartDate() {
            return plannedStartDate;
        }

        public void setPlannedStartDate(LocalDate plannedStartDate) {
            this.plannedStartDate = plannedStartDate;
        }

        public LocalDate getPlannedEndDate() {
            return plannedEndDate;
        }

        public void setPlannedEndDate(LocalDate plannedEndDate) {
            this.plannedEndDate = plannedEndDate;
        }
    }

    public static class DependencyRequest {
        private Long dependsOnTaskId;

        public Long getDependsOnTaskId() {
            return dependsOnTaskId;
        }

        public void setDependsOnTaskId(Long dependsOnTaskId) {
            this.dependsOnTaskId = dependsOnTaskId;
        }
    }

    public static class NoteRequest {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    public static class ReasonRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class ScheduleTaskRequest {
        private Long assigneeId;
        private LocalDate plannedStartDate;
        private LocalDate plannedEndDate;

        public Long getAssigneeId() {
            return assigneeId;
        }

        public void setAssigneeId(Long assigneeId) {
            this.assigneeId = assigneeId;
        }

        public LocalDate getPlannedStartDate() {
            return plannedStartDate;
        }

        public void setPlannedStartDate(LocalDate plannedStartDate) {
            this.plannedStartDate = plannedStartDate;
        }

        public LocalDate getPlannedEndDate() {
            return plannedEndDate;
        }

        public void setPlannedEndDate(LocalDate plannedEndDate) {
            this.plannedEndDate = plannedEndDate;
        }
    }
}
