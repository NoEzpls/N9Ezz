package dev.n9ezz.cf;

import dev.n9ezz.loader.api.ModContext;
import dev.n9ezz.loader.api.N9ModInitializer;
import dev.n9ezz.loader.core.LifecycleEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native addon entry point; no Minecraft classes are linked into the API-only loader. */
public final class CfAssistant implements N9ModInitializer {
    private final AtomicBoolean attempted = new AtomicBoolean();
    private CfRuntime runtime;

    @Override
    public void onInitialize(ModContext context) {
        context.events().on(LifecycleEvent.READY, 0, ignored -> {
            if (!attempted.compareAndSet(false, true)) return;
            ClassLoader loader = CfAssistant.class.getClassLoader();
            if (!CfRuntime.available(loader)) {
                System.out.println("[CF] game runtime absent; API-only load, no game hooks installed");
                return;
            }
            try {
                // READY fires before Minecraft's constructor finishes. Installation
                // resolves classes and subscribes the bus only; it never reads player,
                // world, options or UI state until an actual game callback arrives.
                runtime = CfRuntime.install(context.gameDirectory().resolve("config/cf-assistant"), loader);
                System.out.println("[CF] hooks installed; use /cfbot help in game (automatic play is off)");
            } catch (Exception | LinkageError failure) {
                System.err.println("[CF] hooks unavailable; automatic play disabled: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        });
    }
}
