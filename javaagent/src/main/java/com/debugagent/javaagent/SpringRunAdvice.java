package com.debugagent.javaagent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice that fires AFTER {@code org.springframework.boot.SpringApplication.run()}
 * returns. It captures the returned {@code ConfigurableApplicationContext} and stores it
 * in {@link JavaAgentBootstrap#capturedContext} via reflection.
 *
 * <p><b>NO Spring imports here.</b> This class is loaded by the System ClassLoader
 * where Spring types don't exist. All interaction with the bootstrap is done via
 * raw {@code Object} + reflection.
 *
 * <p>ByteBuddy inlines this advice's bytecode directly into {@code SpringApplication.run()},
 * so at runtime it executes in the app's ClassLoader context. The reflection call
 * to find {@code JavaAgentBootstrap} succeeds because the System ClassLoader
 * (which loaded the agent) is a parent of the app's ClassLoader.
 */
public class SpringRunAdvice {

    @Advice.OnMethodExit
    public static void afterSpringRun(
            @Advice.Return Object returnValue) {

        if (returnValue == null) {
            return;
        }

        // Check if the return value looks like a Spring ApplicationContext
        String className = returnValue.getClass().getName();
        // ConfigurableApplicationContext implementations or their subclasses
        if (!className.contains("ApplicationContext") && !className.contains("context")) {
            return;
        }

        try {
            // Store in JavaAgentBootstrap via reflection (avoid classloader issues)
            Class<?> bootstrap = Class.forName("com.debugagent.javaagent.JavaAgentBootstrap");
            java.lang.reflect.Field field = bootstrap.getDeclaredField("capturedContext");
            field.setAccessible(true);

            // Only store the first context (avoid overwriting if run() is called multiple times)
            if (field.get(null) == null) {
                field.set(null, returnValue);

                // Also store the classloader from which Spring loaded this context
                java.lang.reflect.Field clField = bootstrap.getDeclaredField("appClassLoader");
                clField.setAccessible(true);
                if (clField.get(null) == null) {
                    clField.set(null, returnValue.getClass().getClassLoader());
                }
            }
        } catch (Throwable t) {
            // Swallow — must NEVER affect the host application
        }
    }
}
