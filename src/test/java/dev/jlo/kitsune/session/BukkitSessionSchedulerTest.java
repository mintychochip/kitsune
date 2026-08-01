package dev.jlo.kitsune.session;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BukkitSessionSchedulerTest {
    @Test
    void roundsPositiveDurationsUpToWholeServerTicks() {
        SchedulerHarness harness = SchedulerHarness.create();
        Runnable action = () -> { };

        SessionTask first = harness.scheduler().schedule(Duration.ofNanos(1), action);
        assertEquals(1L, harness.delayTicks().get());
        assertSame(action, harness.action().get());

        harness.scheduler().schedule(Duration.ofMillis(50), action);
        assertEquals(1L, harness.delayTicks().get());

        harness.scheduler().schedule(Duration.ofNanos(50_000_001L), action);
        assertEquals(2L, harness.delayTicks().get());

        first.cancel();
        assertEquals(1, harness.cancelCalls().get());
    }

    @Test
    void rejectsNonPositiveDurations() {
        BukkitSessionScheduler scheduler = SchedulerHarness.create().scheduler();
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule(Duration.ZERO, () -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule(Duration.ofNanos(-1), () -> { }));
    }

    private record SchedulerHarness(
        BukkitSessionScheduler scheduler,
        AtomicLong delayTicks,
        AtomicReference<Runnable> action,
        AtomicInteger cancelCalls
    ) {
        private static SchedulerHarness create() {
            AtomicLong delayTicks = new AtomicLong();
            AtomicReference<Runnable> action = new AtomicReference<>();
            AtomicInteger cancelCalls = new AtomicInteger();
            BukkitTask task = proxy(BukkitTask.class, (method, arguments) -> {
                if (method.getName().equals("cancel")) {
                    cancelCalls.incrementAndGet();
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
            BukkitScheduler bukkitScheduler = proxy(BukkitScheduler.class, (method, arguments) -> {
                if (method.getName().equals("runTaskLater") && arguments.length == 3) {
                    action.set((Runnable) arguments[1]);
                    delayTicks.set((long) arguments[2]);
                    return task;
                }
                return defaultValue(method.getReturnType());
            });
            Server server = proxy(Server.class, (method, arguments) ->
                method.getName().equals("getScheduler")
                    ? bukkitScheduler
                    : defaultValue(method.getReturnType())
            );
            Plugin plugin = proxy(Plugin.class, (method, arguments) ->
                method.getName().equals("getServer")
                    ? server
                    : defaultValue(method.getReturnType())
            );
            return new SchedulerHarness(
                new BukkitSessionScheduler(plugin),
                delayTicks,
                action,
                cancelCalls
            );
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (_ignored, method, arguments) -> invocation.invoke(method, arguments)
        ));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Void.class || type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        throw new AssertionError("Unknown primitive type: " + type);
    }
}
