package com.sunasterisk.socialanalytics.web;

import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.service.metrics.MetricUpdateService;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The dashboard ({@code docs/02-thiet-ke-api.md}, "Trang web"): last update time of the metrics and the latest run
 * of the job, in the application time zone; admins also get a button that runs the job now. The button is a plain
 * form post with the {@code _csrf} hidden field, then a redirect back here with a message (post / redirect / get).
 */
@Controller
public class DashboardController {

    static final String STARTED = "Đã bắt đầu cập nhật số liệu (lần chạy #%d). Tải lại trang để xem kết quả.";
    static final String ALREADY_RUNNING = "Job đang chạy, chưa thể chạy thêm. Hãy thử lại khi chạy xong.";

    private final MetricUpdateService metricUpdateService;
    private final Clock clock;

    public DashboardController(MetricUpdateService metricUpdateService, Clock clock) {
        this.metricUpdateService = metricUpdateService;
        this.clock = clock;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication authentication, Model model) {
        model.addAttribute("view", DashboardView.of(metricUpdateService.latest(), clock.getZone()));
        model.addAttribute("username", authentication.getName());
        model.addAttribute("admin", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals));
        return "dashboard";
    }

    /** {@code ADMIN} only ({@code SecurityConfig}); 409 of the API becomes a message here. */
    @PostMapping("/dashboard/update-metrics")
    public String updateMetrics(RedirectAttributes redirect) {
        try {
            long runId = metricUpdateService.startManual();
            redirect.addFlashAttribute("message", STARTED.formatted(runId));
        } catch (ConflictException ex) {
            redirect.addFlashAttribute("error", ALREADY_RUNNING);
        }
        return "redirect:/dashboard";
    }
}
