package com.debugagent.javaagent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Custom ClassLoader that loads {@code com.debugagent.*} classes from itself first
 * (child-first), while delegating everything else (Spring, JDK, ByteBuddy, etc.)
 * to the parent ClassLoader.
 *
 * <h3>Why child-first for agent classes?</h3>
 * The javaagent JAR is loaded by the System ClassLoader. When the agent captures
 * the Spring {@code ApplicationContext} and wants to create inspectors, those
 * inspector classes reference Spring types (e.g. {@code ApplicationContext},
 * {@code @Component}). Spring types live in the app's ClassLoader (e.g. Spring
 * Boot's {@code LaunchedURLClassLoader}), not the System ClassLoader.
 *
 * <p>If we loaded inspector classes from the System ClassLoader, they would fail
 * with {@code NoClassDefFoundError} because Spring isn't visible there. By using
 * a child-first CL whose parent is the <em>app's</em> ClassLoader, the inspector
 * classes resolve Spring types through the parent.
 *
 * <h3>Why parent-first for non-agent classes?</h3>
 * ByteBuddy, Jackson, SLF4J etc. should be shared with the System CL to avoid
 * having two incompatible copies. Using parent-first delegation ensures a single
 * class identity for library classes.
 */
public class AgentClassLoader extends URLClassLoader {

    static {
        // Ensure ClassLoader.registerAsParallelCapable for thread safety
        try {
            ClassLoader.registerAsParallelCapable();
        } catch (Throwable ignored) {
        }
    }

    public AgentClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. Check if already loaded
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }

            // 2. For relocated agent classes and agent launcher classes — child-first
            //    (load from our own JAR so Spring types resolve through parent app CL)
            //
            //    Relocated packages: com.debugagent.internal.* (shaded from com.debugagent.engine/inspector/etc.)
            //    Non-relocated classes that need Spring visibility (and their inner classes):
            //      - AgentLauncher, StandaloneInspectorFactory, AgentHttpServer
            //
            //    Bootstrap classes stay parent-first (only exist in system CL):
            //      - JavaAgentBootstrap, AgentConfig, AgentClassLoader, SpringRunAdvice
            //    Note: AgentConfig MUST stay parent-first so it's the same Class object
            //    in both system CL and child CL (passed as parameter across CLs).
            if (name.startsWith("com.debugagent.internal.") ||
                (name.startsWith("com.debugagent.javaagent.") &&
                 !name.startsWith("com.debugagent.javaagent.JavaAgentBootstrap") &&
                 !name.startsWith("com.debugagent.javaagent.AgentConfig") &&
                 !name.startsWith("com.debugagent.javaagent.AgentClassLoader") &&
                 !name.startsWith("com.debugagent.javaagent.SpringRunAdvice"))) {
                try {
                    c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException e) {
                    // Not in our JAR — fall through to parent
                }
            }

            // 3. For everything else — parent-first (standard delegation)
            return super.loadClass(name, resolve);
        }
    }
}
