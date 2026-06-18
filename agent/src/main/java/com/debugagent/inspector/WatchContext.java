package com.debugagent.inspector;

import java.util.Map;

/**
 * Thread-local context object passed from OnMethodEnter to OnMethodExit
 * in the ByteBuddy Advice. Must be a simple top-level class visible from
 * instrumented application code.
 */
public class WatchContext {

    public final String watchPointId;
    public final String className;
    public final String methodName;
    public final Object[] args;
    public final Map<String, Object> thisFields;
    public final long startNano;

    public WatchContext(String watchPointId, String className, String methodName,
                        Object[] args, Map<String, Object> thisFields, long startNano) {
        this.watchPointId = watchPointId;
        this.className = className;
        this.methodName = methodName;
        this.args = args;
        this.thisFields = thisFields;
        this.startNano = startNano;
    }
}
