package com.skyvault.user;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppUserPlanTests {

    private AppUser newUser() {
        return new AppUser(
                "plan-test@example.com",
                "verifier-hash",
                null,
                "salt",
                "wrapped-key",
                "wrapped-iv",
                5L * 1024 * 1024 * 1024);
    }

    @Test
    void newAccountStartsWithAFreeThirtyDayPlan() {
        AppUser user = newUser();

        assertEquals("free", user.getPlan());
        assertEquals(30, user.getPlanExpiresAt().toLocalDate()
                .toEpochDay() - user.getPlanStartedAt().toLocalDate().toEpochDay());
        assertTrue(user.isPlanActive(OffsetDateTime.now()));
    }

    @Test
    void planIsInactiveAfterItsExpiryDate() {
        AppUser user = newUser();
        OffsetDateTime expiry = user.getPlanExpiresAt();

        assertTrue(user.isPlanActive(expiry.minusSeconds(1)));
        assertFalse(user.isPlanActive(expiry));
        assertFalse(user.isPlanActive(expiry.plusDays(1)));
    }

    @Test
    void changingPlanStartsANewThirtyDayPeriod() {
        AppUser user = newUser();
        OffsetDateTime start = OffsetDateTime.now().minusDays(90);
        OffsetDateTime expiry = OffsetDateTime.now().minusDays(60);
        user.changePlan("pro", 1024L * 1024 * 1024 * 1024, start, expiry);

        assertEquals("pro", user.getPlan());
        assertFalse(user.isPlanActive(OffsetDateTime.now()));

        OffsetDateTime renewedAt = OffsetDateTime.now();
        user.changePlan("free", 5L * 1024 * 1024 * 1024, renewedAt, renewedAt.plusDays(30));

        assertTrue(user.isPlanActive(renewedAt.plusDays(29)));
        assertFalse(user.isPlanActive(renewedAt.plusDays(30)));
    }
}
