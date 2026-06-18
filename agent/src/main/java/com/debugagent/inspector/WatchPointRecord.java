package com.debugagent.inspector;

import java.util.*;

/**
 * Stores a captured method invocation record from a WatchPoint.
 */
public class WatchPointRecord {

    private final String watchPointId;
    private final String className;
    private final String methodName;
    private final long timestamp;
    private final List<Object> args;
    private final Object returnValue;
    private final Throwable thrown;
    private final long durationMs;
    private final Map<String, Object> thisFields;

    public WatchPointRecord(String watchPointId, String className, String methodName,
                            long timestamp, Object[] args, Object returnValue,
                            Throwable thrown, long durationMs,
                            Map<String, Object> thisFields) {
        this.watchPointId = watchPointId;
        this.className = className;
        this.methodName = methodName;
        this.timestamp = timestamp;
        this.args = args != null ? Arrays.asList(args) : Collections.emptyList();
        this.returnValue = returnValue;
        this.thrown = thrown;
        this.durationMs = durationMs;
        this.thisFields = thisFields != null ? thisFields : Collections.emptyMap();
    }

    public String getWatchPointId() { return watchPointId; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public long getTimestamp() { return timestamp; }
    public List<Object> getArgs() { return args; }
    public Object getReturnValue() { return returnValue; }
    public Throwable getThrown() { return thrown; }
    public long getDurationMs() { return durationMs; }
    public Map<String, Object> getThisFields() { return thisFields; }
}
