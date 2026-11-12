package tn.esprit.marketsim.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;

import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.level.UserLevelService;
import tn.esprit.marketsim.user.score.SecurityScoreService;

@ExtendWith(MockitoExtension.class)
class UserControllerSelfActionTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private ActivityLogService activityLogService;
    @Mock
    private SecurityScoreService securityScoreService;
    @Mock
    private UserLevelService userLevelService;
    @InjectMocks
    private UserController controller;

    private final User admin = User.builder().id(1L).role(Role.ADMIN).enabled(true).build();

    @Test
    void adminCannotDisableOwnAccount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.setEnabled(admin, 1L, false));

        assertEquals("Vous ne pouvez pas desactiver votre propre compte", ex.getMessage());
        verify(activityLogService).recordImmediate(
                eq(1L), eq(1L), eq(ActivityAction.ADMIN_SET_ENABLED), eq(ActivityOutcome.FAILURE),
                eq("SELF_ACTION_FORBIDDEN"), any(TraceDetails.class));
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotDeleteOwnAccount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.delete(admin, 1L));

        assertEquals("Vous ne pouvez pas supprimer votre propre compte", ex.getMessage());
        verify(activityLogService).recordImmediate(
                eq(1L), eq(1L), eq(ActivityAction.ADMIN_DELETE_USER), eq(ActivityOutcome.FAILURE),
                eq("SELF_ACTION_FORBIDDEN"), any(TraceDetails.class));
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void adminCanStillDisableAnotherUser() {
        User other = User.builder().id(2L).role(Role.USER).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.save(other)).thenReturn(other);

        User saved = controller.setEnabled(admin, 2L, false);

        assertFalse(saved.isEnabled());
        verify(activityLogService).recordAfterCommit(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_SET_ENABLED), eq("DISABLED"), any(TraceDetails.class));
    }

    @Test
    void deletingUserWithDependentDataIsRefusedAndTraced() {
        when(userRepository.existsById(2L)).thenReturn(true);
        doThrow(new DataIntegrityViolationException("fk trade_order.owner_id"))
                .when(userRepository).deleteById(2L);

        ConflictException ex = assertThrows(ConflictException.class, () -> controller.delete(admin, 2L));

        assertTrue(ex.getMessage().contains("Desactivez-le"));
        verify(activityLogService).recordImmediate(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_DELETE_USER), eq(ActivityOutcome.FAILURE),
                eq("USER_HAS_DEPENDENT_DATA"), any(TraceDetails.class));
        verify(activityLogService, never()).recordAfterCommit(
                anyLong(), anyLong(), any(ActivityAction.class), anyString(), any(TraceDetails.class));
    }

    @Test
    void adminCannotChangeOwnRole() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.setRole(admin, 1L, Role.USER));

        assertEquals("Vous ne pouvez pas modifier votre propre role", ex.getMessage());
        verifyRoleFailure(1L, "SELF_ACTION_FORBIDDEN");
        verify(userRepository, never()).save(any());
    }

    @Test
    void unknownUserRoleChangeIsTracedAsNotFound() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> controller.setRole(admin, 9L, Role.ADMIN));
        verifyRoleFailure(9L, "USER_NOT_FOUND");
    }

    @Test
    void promotingAUserIsSavedAndTraced() {
        User other = User.builder().id(2L).role(Role.USER).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.save(other)).thenReturn(other);

        User saved = controller.setRole(admin, 2L, Role.ADMIN);

        assertEquals(Role.ADMIN, saved.getRole());
        verify(activityLogService).recordAfterCommit(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_SET_ROLE), eq("PROMOTED"), any(TraceDetails.class));
    }

    @Test
    void demotingAnotherAdminWorksWhileAnotherActiveAdminRemains() {
        User other = User.builder().id(2L).role(Role.ADMIN).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.countByRoleAndEnabled(Role.ADMIN, true)).thenReturn(2L);
        when(userRepository.save(other)).thenReturn(other);

        assertEquals(Role.USER, controller.setRole(admin, 2L, Role.USER).getRole());
        verify(activityLogService).recordAfterCommit(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_SET_ROLE), eq("DEMOTED"), any(TraceDetails.class));
    }

    @Test
    void lastActiveAdminCannotBeDemoted() {
        User other = User.builder().id(2L).role(Role.ADMIN).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.countByRoleAndEnabled(Role.ADMIN, true)).thenReturn(1L);

        ConflictException ex = assertThrows(ConflictException.class, () -> controller.setRole(admin, 2L, Role.USER));

        assertEquals("Impossible de retirer le dernier administrateur actif", ex.getMessage());
        assertEquals(Role.ADMIN, other.getRole());
        verifyRoleFailure(2L, "LAST_ADMIN");
        verify(userRepository, never()).save(any());
    }

    @Test
    void sameRoleIsANoOpButStillTraced() {
        User other = User.builder().id(2L).role(Role.USER).enabled(true).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));

        controller.setRole(admin, 2L, Role.USER);

        verify(userRepository, never()).save(any());
        verify(activityLogService).recordImmediate(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_SET_ROLE), eq(ActivityOutcome.SUCCESS),
                eq("UNCHANGED"), any(TraceDetails.class));
    }

    private void verifyRoleFailure(Long target, String reason) {
        verify(activityLogService).recordImmediate(
                eq(1L), eq(target), eq(ActivityAction.ADMIN_SET_ROLE), eq(ActivityOutcome.FAILURE),
                eq(reason), any(TraceDetails.class));
    }

    @Test
    void adminCanStillDeleteAnotherUser() {
        when(userRepository.existsById(2L)).thenReturn(true);

        controller.delete(admin, 2L);

        verify(userRepository).deleteById(2L);
        verify(activityLogService).recordAfterCommit(
                eq(1L), eq(2L), eq(ActivityAction.ADMIN_DELETE_USER), eq("OK"), any(TraceDetails.class));
    }
}
