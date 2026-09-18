package com.metallum.client.renderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Thread-confined render-pass context for nested offscreen work. */
public enum MetallumRenderContext {
    MAIN_PASS,
    REFLECTION_PASS,
    SHADOW_PASS;

    private static final ThreadLocal<Deque<MetallumRenderContext>> CONTEXT_STACK =
            ThreadLocal.withInitial(() -> {
                Deque<MetallumRenderContext> stack = new ArrayDeque<>(4);
                stack.push(MAIN_PASS);
                return stack;
            });

    public static MetallumRenderContext current() {
        MetallumRenderContext context = CONTEXT_STACK.get().peek();
        return context != null ? context : MAIN_PASS;
    }

    public static boolean isReflectionPass() {
        return current() == REFLECTION_PASS;
    }

    public static Scope push(final MetallumRenderContext context) {
        CONTEXT_STACK.get().push(Objects.requireNonNull(context, "context"));
        return Scope.INSTANCE;
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope INSTANCE = new Scope();

        private Scope() {
        }

        @Override
        public void close() {
            Deque<MetallumRenderContext> stack = CONTEXT_STACK.get();
            if (stack.size() > 1) {
                stack.pop();
            }
        }
    }
}
