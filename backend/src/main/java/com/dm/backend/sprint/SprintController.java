package com.dm.backend.sprint;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.auth.CurrentUser;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.requirement.RequirementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 迭代管理接口（§9.4）：开发管理者或 ADMIN 可操作。 */
@RestController
@RequestMapping("/api/sprints")
public class SprintController {

    private final SprintService sprintService;
    private final RequirementService requirementService;

    public SprintController(SprintService sprintService, RequirementService requirementService) {
        this.sprintService = sprintService;
        this.requirementService = requirementService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long teamId) {
        CurrentUser user = AuthContext.require();
        Long scopeTeam = teamId != null ? teamId : user.requireTeamId();
        user.requireTeamAccess(scopeTeam);
        return sprintService.list(scopeTeam);
    }

    /** 迭代详情 + 迭代内需求（迭代管理页与看板共用）。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        CurrentUser user = AuthContext.require();
        Sprint sprint = sprintService.get(id);
        user.requireTeamAccess(sprint.getTeamId());
        return Responses.map(
                "sprint", sprintService.view(sprint),
                "requirements", requirementService.list(sprint.getTeamId(), id, null, null, null, null));
    }

    @PostMapping
    public Sprint create(@RequestBody SprintRequest request) {
        CurrentUser user = requireManager();
        return sprintService.create(user.requireTeamId(), request.getName(),
                request.getStartDate(), request.getEndDate());
    }

    @PostMapping("/{id}/start")
    public Sprint start(@PathVariable Long id) {
        requireManagerOn(id);
        return sprintService.start(id);
    }

    @PostMapping("/{id}/close")
    public Sprint close(@PathVariable Long id) {
        requireManagerOn(id);
        return sprintService.close(id);
    }

    @PostMapping("/{id}/reopen")
    public Sprint reopen(@PathVariable Long id) {
        requireManagerOn(id);
        return sprintService.reopen(id);
    }

    private CurrentUser requireManager() {
        CurrentUser user = AuthContext.require();
        user.requireAnyRole(MemberRole.DEV_MANAGER);
        return user;
    }

    private void requireManagerOn(Long sprintId) {
        CurrentUser user = AuthContext.require();
        Sprint sprint = sprintService.get(sprintId);
        user.requireAnyRole(MemberRole.DEV_MANAGER);
        user.requireTeamAccess(sprint.getTeamId());
    }

    public static class SprintRequest {
        private String name;
        private LocalDate startDate;
        private LocalDate endDate;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public void setEndDate(LocalDate endDate) {
            this.endDate = endDate;
        }
    }
}
