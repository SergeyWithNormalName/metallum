package com.metallum.client.display;

public final class NativeFullscreenBenchmarkTests {
    private NativeFullscreenBenchmarkTests() {
    }

    public static void main(final String[] args) {
        if (!NativeFullscreenStartup.allowsGlfwModeChange()) {
            throw new AssertionError(
                    "METALLUM_BENCHMARK=1 must preserve vanilla GLFW fullscreen mode changes"
            );
        }
        System.out.println("Native fullscreen benchmark environment test passed");
    }
}
