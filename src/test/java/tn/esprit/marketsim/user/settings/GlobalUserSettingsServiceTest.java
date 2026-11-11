package tn.esprit.marketsim.user.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class GlobalUserSettingsServiceTest {

    @Test
    void acceptsDefaultRanges() {
        assertDoesNotThrow(() -> GlobalUserSettingsService.validate(5, 15));
        assertDoesNotThrow(() -> GlobalUserSettingsService.validate(3, 1));
        assertDoesNotThrow(() -> GlobalUserSettingsService.validate(10, 60));
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> GlobalUserSettingsService.validate(2, 15));
        assertThrows(IllegalArgumentException.class, () -> GlobalUserSettingsService.validate(11, 15));
        assertThrows(IllegalArgumentException.class, () -> GlobalUserSettingsService.validate(5, 0));
        assertThrows(IllegalArgumentException.class, () -> GlobalUserSettingsService.validate(5, 61));
    }

    @Test
    void bootstrapCreatesTheRowBeforeRequestsAreServed() {
        GlobalUserSettingsService service = mock(GlobalUserSettingsService.class);

        new GlobalUserSettingsBootstrap(service).afterSingletonsInstantiated();

        verify(service).getOrCreate();
    }
}
