package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoLoginPropertiesTests {

    @Test
    void enabledNeedsBothPasswordsOfAtLeastEightCharacters() {
        assertThatThrownBy(() -> new DemoLoginProperties(true, null, "user-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Demo login is enabled: set app.demo-login.admin-password (APP_DEMO_ADMIN_PASSWORD), "
                        + "at least 8 characters");
        assertThatThrownBy(() -> new DemoLoginProperties(true, "admin-password", "   "))
                .hasMessageContaining("APP_DEMO_USER_PASSWORD");
        assertThatThrownBy(() -> new DemoLoginProperties(true, "1234567", "user-password"))
                .hasMessageContaining("APP_DEMO_ADMIN_PASSWORD");
        assertThat(new DemoLoginProperties(true, "12345678", "user-password").enabled()).isTrue();
    }

    @Test
    void disabledNeedsNoPassword() {
        assertThat(new DemoLoginProperties(false, null, null).enabled()).isFalse();
    }
}
