package com.dm.backend.report;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.auth.CurrentUser;
import com.dm.backend.common.ApiException;
import com.dm.backend.entity.Sprint;
import com.dm.backend.repository.SprintRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 跨队总览（§9.2，仅跨队管理者/管理员）与统计报表（§9.8，本队成员可看本队）。
 */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService reportService;
    private final SprintRepository sprints;

    public ReportController(ReportService reportService, SprintRepository sprints) {
        this.reportService = reportService;
        this.sprints = sprints;
    }

    /** 各小队当前迭代进度、负载与延期风险（只读总览）。 */
    @GetMapping("/overview")
    public List<Map<String, Object>> overview() {
        requireSupervisor();
        return reportService.teamOverview();
    }

    /** 跨队汇总：完成率对比 + 各队延期风险 Top。 */
    @GetMapping("/reports/teams")
    public List<Map<String, Object>> teamComparison() {
        requireSupervisor();
        return reportService.teamComparison();
    }

    /** 单个迭代报告：完成率、按期完成率、状态分布、按天趋势、插队占比、阻塞时长、人均负载。 */
    @GetMapping("/reports/sprints/{sprintId}")
    public Map<String, Object> sprintReport(@PathVariable Long sprintId) {
        CurrentUser user = AuthContext.require();
        Sprint sprint = sprints.findById(sprintId).orElseThrow(() -> ApiException.notFound("迭代不存在"));
        user.requireTeamAccess(sprint.getTeamId());
        return reportService.sprintReport(sprintId);
    }

    private void requireSupervisor() {
        CurrentUser user = AuthContext.require();
        if (!user.isAdmin() && !user.isSupervisor()) {
            throw ApiException.forbidden("仅跨队管理者或系统管理员可访问");
        }
    }
}
