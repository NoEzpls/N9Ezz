/* Restored from the supplied 0.2.1 bytecode with CFR 0.152; see provenance.json. */
package dev.n9ezz.cf;

import dev.n9ezz.cf.CfRuntime;
import dev.n9ezz.loader.api.ModContext;
import dev.n9ezz.loader.api.N9ModInitializer;
import dev.n9ezz.loader.core.LifecycleEvent;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CfAssistant
implements N9ModInitializer {
    private final AtomicBoolean attempted = new AtomicBoolean();
    private CfRuntime runtime;

    public void onInitialize(ModContext modContext) {
        modContext.events().on(LifecycleEvent.READY, 0, lifecycleEvent -> {
            if (!this.attempted.compareAndSet(false, true)) {
                return;
            }
            ClassLoader classLoader = CfAssistant.class.getClassLoader();
            if (!CfRuntime.available(classLoader)) {
                System.out.println("[CF] game runtime absent; API-only load, no game hooks installed");
                return;
            }
            try {
                this.runtime = CfRuntime.install(modContext.gameDirectory().resolve("config/cf-assistant"), classLoader);
                System.out.println("[CF] hooks installed; use /cfbot help in game (automatic play is off)");
            }
            catch (Exception | LinkageError throwable) {
                System.err.println("[CF] hooks unavailable; automatic play disabled: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        });
    }
}

