package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.ngx.DlssSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.OptionInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual option value sets and callbacks without a window, device or Minecraft loop. */
final class RtVideoOptionsTest {
    @Test
    void multiplierOptionClampsDisplayButPreservesStoredPreferenceUntilChanged() throws ReflectiveOperationException {
        var setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        int previous = setting.value();
        try {
            setting.set(5);
            OptionInstance<Integer> option = multiplier(DlssSettings.generatedFrameChoices(3));
            assertEquals(3, option.get());
            assertEquals(5, setting.value());
            assertTrue(option.values().validateValue(3).isPresent());
            assertTrue(option.values().validateValue(4).isEmpty());
            callback(option).valueChanged(2);
            assertEquals(2, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void standardFgHasOnlyThe2xValue() throws ReflectiveOperationException {
        OptionInstance<Integer> option = multiplier(DlssSettings.generatedFrameChoices(0));
        assertEquals(1, option.get());
        assertTrue(option.values().validateValue(1).isPresent());
        assertTrue(option.values().validateValue(2).isEmpty());
    }

    @Test
    void modelOptionBindsFAndAutoToTheRuntimeSetting() throws ReflectiveOperationException {
        var setting = CausticaConfig.Rt.DlssRr.PRESET;
        int previous = setting.value();
        try {
            Method factory = RtVideoOptions.class.getDeclaredMethod("dlssModel");
            factory.setAccessible(true);
            @SuppressWarnings("unchecked")
            OptionInstance<Integer> option = (OptionInstance<Integer>) factory.invoke(null);
            assertTrue(option.values().validateValue(6).isPresent());
            assertTrue(option.values().validateValue(12).isEmpty());
            assertTrue(option.values().validateValue(13).isEmpty());
            callback(option).valueChanged(6);
            assertEquals(6, setting.value());
            callback(option).valueChanged(0);
            assertEquals(0, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void fgSwitchUpdatesTheSettingWithoutDeviceRecreation() throws ReflectiveOperationException {
        var setting = CausticaConfig.Rt.Fg.ENABLED;
        boolean previous = setting.value();
        try {
            Method factory = RtVideoOptions.class.getDeclaredMethod("frameGenerationEnabled", boolean.class);
            factory.setAccessible(true);
            @SuppressWarnings("unchecked")
            OptionInstance<Boolean> option = (OptionInstance<Boolean>) factory.invoke(null, true);
            callback(option).valueChanged(true);
            assertTrue(setting.value());
            callback(option).valueChanged(false);
            assertFalse(setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static OptionInstance<Integer> multiplier(List<Integer> counts) throws ReflectiveOperationException {
        Method factory = RtVideoOptions.class.getDeclaredMethod("frameGenerationMultiplier", List.class, boolean.class);
        factory.setAccessible(true);
        return (OptionInstance<Integer>) factory.invoke(null, counts, true);
    }

    @SuppressWarnings("unchecked")
    private static <T> OptionInstance.ValueUpdateListener<T> callback(OptionInstance<T> option)
            throws ReflectiveOperationException {
        // OptionInstance.set needs a running Minecraft instance; invoke its stored listener directly.
        Field listener = OptionInstance.class.getDeclaredField("onValueUpdate");
        listener.setAccessible(true);
        return (OptionInstance.ValueUpdateListener<T>) listener.get(option);
    }
}
