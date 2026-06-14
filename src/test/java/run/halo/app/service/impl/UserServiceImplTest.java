package run.halo.app.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import run.halo.app.exception.BadRequestException;
import run.halo.app.exception.ForbiddenException;
import run.halo.app.exception.NotFoundException;
import run.halo.app.exception.ServiceException;
import run.halo.app.model.entity.User;
import run.halo.app.model.enums.MFAType;
import run.halo.app.repository.UserRepository;
import run.halo.app.utils.BCrypt;

/**
 * Unit tests for {@link UserServiceImpl}.
 *
 * <p>Halo 1.x is a single-administrator blog, so "user role management" is expressed through
 * UserService's access-control behaviors: the single-user creation constraint, account expiry
 * (forbidden) checks, identity verification, credential management and multi-factor auth.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    UserRepository userRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    UserServiceImpl userService;

    private User userWithPassword(String plainPassword) {
        User user = new User();
        user.setId(1);
        user.setUsername("admin");
        user.setNickname("Admin");
        user.setEmail("admin@halo.run");
        user.setPassword(BCrypt.hashpw(plainPassword, BCrypt.gensalt()));
        return user;
    }

    // ---------------------------------------------------------------------
    // Single-administrator constraint (the core permission rule)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("create() allows the first (admin) user and publishes an event")
    void createFirstUserSucceeds() {
        User user = userWithPassword("secret");
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.save(any(User.class))).thenReturn(user);

        User created = userService.create(user);

        assertSame(user, created);
        verify(userRepository).save(user);
        verify(eventPublisher, atLeastOnce()).publishEvent(any());
    }

    @Test
    @DisplayName("create() rejects a second user when one already exists")
    void createSecondUserRejected() {
        when(userRepository.count()).thenReturn(1L);

        assertThrows(BadRequestException.class, () -> userService.create(new User()));

        verify(userRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // Account expiry / forbidden access control
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("mustNotExpire() forbids access for a disabled (future-dated) account")
    void mustNotExpireThrowsWhenExpiryInFuture() {
        User user = new User();
        user.setExpireTime(new Date(System.currentTimeMillis() + 600_000L));

        assertThrows(ForbiddenException.class, () -> userService.mustNotExpire(user));
    }

    @Test
    @DisplayName("mustNotExpire() permits access when expire time is null or in the past")
    void mustNotExpirePassesWhenNotExpired() {
        User nullExpiry = new User();
        nullExpiry.setExpireTime(null);
        assertDoesNotThrow(() -> userService.mustNotExpire(nullExpiry));

        User pastExpiry = new User();
        pastExpiry.setExpireTime(new Date(System.currentTimeMillis() - 600_000L));
        assertDoesNotThrow(() -> userService.mustNotExpire(pastExpiry));
    }

    // ---------------------------------------------------------------------
    // Identity verification
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("verifyUser() returns true only for the matching username/email")
    void verifyUserMatchesIdentity() {
        User user = new User();
        user.setUsername("admin");
        user.setEmail("admin@halo.run");
        when(userRepository.findAll()).thenReturn(List.of(user));

        assertTrue(userService.verifyUser("admin", "admin@halo.run"));
        assertFalse(userService.verifyUser("admin", "wrong@halo.run"));
    }

    @Test
    @DisplayName("verifyUser() throws when no administrator exists yet")
    void verifyUserThrowsWhenNoUser() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        assertThrows(ServiceException.class, () -> userService.verifyUser("admin", "x"));
    }

    // ---------------------------------------------------------------------
    // Password management guards
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("updatePassword() rejects identical old and new passwords")
    void updatePasswordRejectsSamePassword() {
        assertThrows(BadRequestException.class,
            () -> userService.updatePassword("same", "same", 1));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updatePassword() rejects an incorrect old password")
    void updatePasswordRejectsWrongOldPassword() {
        User user = userWithPassword("correctOld");
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        assertThrows(BadRequestException.class,
            () -> userService.updatePassword("wrongOld", "newPass", 1));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updatePassword() stores the new hashed password on success")
    void updatePasswordSucceeds() {
        User user = userWithPassword("oldPass");
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userService.updatePassword("oldPass", "newPass", 1);

        assertTrue(BCrypt.checkpw("newPass", updated.getPassword()));
        assertFalse(BCrypt.checkpw("oldPass", updated.getPassword()));
        verify(eventPublisher, atLeastOnce()).publishEvent(any());
    }

    @Test
    @DisplayName("passwordMatch() validates plain passwords against the stored hash")
    void passwordMatchBehaviors() {
        User user = userWithPassword("secret");

        assertTrue(userService.passwordMatch(user, "secret"));
        assertFalse(userService.passwordMatch(user, "wrong"));
        assertFalse(userService.passwordMatch(user, ""));
        assertFalse(userService.passwordMatch(user, null));
    }

    @Test
    @DisplayName("setPassword() hashes the password and resets MFA")
    void setPasswordHashesAndResetsMfa() {
        User user = new User();
        user.setMfaType(MFAType.TFA_TOTP);
        user.setMfaKey("existing-key");

        userService.setPassword(user, "plainPass");

        assertTrue(BCrypt.checkpw("plainPass", user.getPassword()));
        assertEquals(MFAType.NONE, user.getMfaType());
        assertNull(user.getMfaKey());
    }

    // ---------------------------------------------------------------------
    // Multi-factor authentication
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("updateMFA() enables TOTP and stores the key")
    void updateMfaEnablesTotp() {
        User user = new User();
        user.setId(1);
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userService.updateMFA(MFAType.TFA_TOTP, "totp-key", 1);

        assertEquals(MFAType.TFA_TOTP, updated.getMfaType());
        assertEquals("totp-key", updated.getMfaKey());
    }

    @Test
    @DisplayName("updateMFA() clears the key when MFA is disabled")
    void updateMfaDisableClearsKey() {
        User user = new User();
        user.setId(1);
        user.setMfaType(MFAType.TFA_TOTP);
        user.setMfaKey("totp-key");
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userService.updateMFA(MFAType.NONE, "ignored", 1);

        assertEquals(MFAType.NONE, updated.getMfaType());
        assertNull(updated.getMfaKey());
    }

    // ---------------------------------------------------------------------
    // Non-null lookups
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getByUsernameOfNonNull() throws when the username is absent")
    void getByUsernameOfNonNullThrowsWhenMissing() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
            () -> userService.getByUsernameOfNonNull("ghost"));
    }

    @Test
    @DisplayName("getByEmailOfNonNull() returns the user when present")
    void getByEmailOfNonNullReturnsUser() {
        User user = userWithPassword("secret");
        when(userRepository.findByEmail("admin@halo.run")).thenReturn(Optional.of(user));

        assertSame(user, userService.getByEmailOfNonNull("admin@halo.run"));
    }
}
