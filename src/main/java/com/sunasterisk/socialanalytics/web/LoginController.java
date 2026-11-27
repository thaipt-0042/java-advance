package com.sunasterisk.socialanalytics.web;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.security.DemoLoginProperties;
import com.sunasterisk.socialanalytics.security.SocialLoginProperties;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The login page: one button per configured provider (Facebook, X), the demo form when it is on (dev profile), and
 * a message after a failed login or a logout. With neither, the page says no login method is configured.
 */
@Controller
public class LoginController {

    private final SocialLoginProperties socialLogin;
    private final DemoLoginProperties demoLogin;

    public LoginController(SocialLoginProperties socialLogin, DemoLoginProperties demoLogin) {
        this.socialLogin = socialLogin;
        this.demoLogin = demoLogin;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, @RequestParam(required = false) String logout,
            Model model) {
        model.addAttribute("providers", socialLogin.enabledPlatforms().stream().map(LoginButton::of).toList());
        model.addAttribute("demoLogin", demoLogin.enabled());
        model.addAttribute("error", error != null);
        model.addAttribute("logout", logout != null);
        return "login";
    }

    /** A provider button: link to Spring Security's authorization endpoint. */
    public record LoginButton(String url, String label) {

        static LoginButton of(SocialPlatform platform) {
            String id = platform.name().toLowerCase(Locale.ROOT);
            return new LoginButton("/oauth2/authorization/" + id,
                    platform == SocialPlatform.FACEBOOK ? "Đăng nhập bằng Facebook" : "Đăng nhập bằng X");
        }
    }
}
