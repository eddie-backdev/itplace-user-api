package com.itplace.userapi.security.abuse;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.security.abuse-protection")
public class AuthenticationAbuseProtectionProperties {

    private boolean enabled = true;

    @NotNull
    private Duration verificationIssueCooldown = Duration.ofMinutes(1);

    @NotNull
    private Duration verificationIssueWindow = Duration.ofHours(1);

    @Min(1)
    private int verificationIssuePerIdentifier = 5;

    @Min(1)
    private int verificationIssuePerIp = 20;

    @NotNull
    private Duration verificationConfirmWindow = Duration.ofMinutes(10);

    @Min(1)
    private int verificationConfirmPerIdentifier = 10;

    @Min(1)
    private int verificationConfirmPerIp = 50;

    @NotNull
    private Duration loginIpWindow = Duration.ofMinutes(1);

    @Min(1)
    private int loginPerIp = 30;

    @NotNull
    private Duration loginFailureWindow = Duration.ofMinutes(15);

    @Min(1)
    private int loginFailureThreshold = 5;

    @NotNull
    private Duration loginInitialLock = Duration.ofSeconds(30);

    @NotNull
    private Duration loginMaxLock = Duration.ofMinutes(15);
}
