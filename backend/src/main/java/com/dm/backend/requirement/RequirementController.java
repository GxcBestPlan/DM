package com.dm.backend.requirement;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.auth.CurrentUser;
import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.RequirementType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 需求池与需求流转接口（§8.1 / §8.2 / §8.3 / §8.4）。
 * 角色：录入/编辑/评审/定序 = PM；排期/插队/移出/阻塞/发布 = 开发管理者；测试流转 = 测试。
 */
@RestController
@RequestMapping("/api/requirements")
public class RequirementController {

    private final RequirementService requirementService;

    public RequirementController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    // ---------- 查询 ----------

    /** 不带 sprintId = 需求池；带 sprintId = 该迭代内需求；all=true 则跨迭代返回本队全部需求。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long teamId,
                                          @RequestParam(required = false) Long sprintId,
                                          @RequestParam(required = false, defaultValue = "false") boolean all,
                                          @RequestParam(required = false) RequirementType type,
                                          @RequestParam(required = false) RequirementStatus status,
                                          @RequestParam(required = false) Long createdBy,
                                          @RequestParam(required = false) String keyword) {
        CurrentUser user = AuthContext.require();
        Long scopeTeam = teamId != null ? teamId : user.requireTeamId();
        user.requireTeamAccess(scopeTeam);
        if (all) {
            return requirementService.listAll(scopeTeam, type, status, createdBy, keyword);
        }
        return requirementService.list(scopeTeam, sprintId, type, status, createdBy, keyword);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        CurrentUser user = AuthContext.require();
        user.requireTeamAccess(requirementService.get(id).getTeamId());
        return requirementService.detail(id);
    }

    // ---------- 需求池（PM） ----------

    @PostMapping
    public Requirement create(@RequestBody RequirementRequest request) {
        CurrentUser user = AuthContext.require();
        user.requireAnyRole(MemberRole.PM);
        return requirementService.create(user.requireTeamId(), user.getUserId(),
                request.getTitle(), request.getDescription(), request.getType(),
                request.getEstimate(), request.getAcceptanceCriteria(), request.getExternalNote());
    }

    @PutMapping("/{id}")
    public Requirement update(@PathVariable Long id, @RequestBody RequirementRequest request) {
        CurrentUser user = requireAccessible(id, MemberRole.PM);
        return requirementService.update(id, request.getTitle(), request.getDescription(),
                request.getType(), request.getEstimate(),
                request.getAcceptanceCriteria(), request.getExternalNote());
    }

    @PostMapping("/{id}/review")
    public Requirement review(@PathVariable Long id, @RequestBody ReviewRequest request) {
        CurrentUser user = requireAccessible(id, MemberRole.PM);
        return requirementService.review(id, Boolean.TRUE.equals(request.getApproved()),
                request.getComment(), user.getUserId());
    }

    @PutMapping("/pool-order")
    public void reorderPool(@RequestBody ReorderRequest request) {
        CurrentUser user = AuthContext.require();
        user.requireAnyRole(MemberRole.PM);
        requirementService.reorderPool(user.requireTeamId(), request.getIds());
    }

    // ---------- 排期与迭代内流转（开发管理者） ----------

    @PostMapping("/{id}/schedule")
    public Requirement schedule(@PathVariable Long id, @RequestBody ScheduleRequest request) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.schedule(id, request.getSprintId(),
                Boolean.TRUE.equals(request.getUrgent()), user.getUserId());
    }

    @PostMapping("/{id}/unschedule")
    public Requirement unschedule(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.unschedule(id, user.getUserId());
    }

    @PostMapping("/{id}/dev")
    public Requirement startDev(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.startDev(id, user.getUserId());
    }

    @PostMapping("/{id}/ready-for-test")
    public Requirement readyForTest(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.readyForTest(id, user.getUserId());
    }

    @PostMapping("/{id}/block")
    public Requirement block(@PathVariable Long id, @RequestBody ReasonRequest request) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.block(id, request.getReason(), user.getUserId());
    }

    @PostMapping("/{id}/unblock")
    public Requirement unblock(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.unblock(id, user.getUserId());
    }

    @PostMapping("/{id}/publish")
    public Requirement publish(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.DEV_MANAGER);
        return requirementService.publish(id, user.getUserId());
    }

    /** 池内由 PM 取消，迭代内由开发管理者取消。 */
    @PostMapping("/{id}/cancel")
    public Requirement cancel(@PathVariable Long id) {
        CurrentUser user = AuthContext.require();
        Requirement requirement = requirementService.get(id);
        user.requireTeamAccess(requirement.getTeamId());
        user.requireAnyRole(requirement.getSprintId() == null
                ? MemberRole.PM : MemberRole.DEV_MANAGER);
        return requirementService.cancel(id, user.getUserId());
    }

    // ---------- 测试流转（测试） ----------

    @PostMapping("/{id}/claim")
    public Requirement claim(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.TESTER);
        return requirementService.claimForTest(id, user.getUserId());
    }

    @PostMapping("/{id}/accept")
    public Requirement accept(@PathVariable Long id) {
        CurrentUser user = requireAccessible(id, MemberRole.TESTER);
        return requirementService.accept(id, user.getUserId());
    }

    @PostMapping("/{id}/reject")
    public Requirement reject(@PathVariable Long id, @RequestBody CommentRequest request) {
        CurrentUser user = requireAccessible(id, MemberRole.TESTER);
        return requirementService.reject(id, request.getComment(), user.getUserId());
    }

    // ---------- 内部 ----------

    private CurrentUser requireAccessible(Long id, MemberRole... roles) {
        CurrentUser user = AuthContext.require();
        Requirement requirement = requirementService.get(id);
        user.requireAnyRole(roles);
        user.requireTeamAccess(requirement.getTeamId());
        return user;
    }

    // ---------- 请求体 ----------

    public static class RequirementRequest {
        private String title;
        private String description;
        private RequirementType type;
        private BigDecimal estimate;
        private String acceptanceCriteria;
        private String externalNote;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public RequirementType getType() {
            return type;
        }

        public void setType(RequirementType type) {
            this.type = type;
        }

        public BigDecimal getEstimate() {
            return estimate;
        }

        public void setEstimate(BigDecimal estimate) {
            this.estimate = estimate;
        }

        public String getAcceptanceCriteria() {
            return acceptanceCriteria;
        }

        public void setAcceptanceCriteria(String acceptanceCriteria) {
            this.acceptanceCriteria = acceptanceCriteria;
        }

        public String getExternalNote() {
            return externalNote;
        }

        public void setExternalNote(String externalNote) {
            this.externalNote = externalNote;
        }
    }

    public static class ReviewRequest {
        private Boolean approved;
        private String comment;

        public Boolean getApproved() {
            return approved;
        }

        public void setApproved(Boolean approved) {
            this.approved = approved;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }

    public static class ScheduleRequest {
        private Long sprintId;
        private Boolean urgent;

        public Long getSprintId() {
            return sprintId;
        }

        public void setSprintId(Long sprintId) {
            this.sprintId = sprintId;
        }

        public Boolean getUrgent() {
            return urgent;
        }

        public void setUrgent(Boolean urgent) {
            this.urgent = urgent;
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

    public static class CommentRequest {
        private String comment;

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }

    public static class ReorderRequest {
        private List<Long> ids;

        public List<Long> getIds() {
            return ids;
        }

        public void setIds(List<Long> ids) {
            this.ids = ids;
        }
    }
}
