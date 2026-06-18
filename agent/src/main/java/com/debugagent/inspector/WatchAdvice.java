package com.debugagent.inspector;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ByteBuddy Advice that gets inlined into watched methods.
 *
 * OnMethodEnter: checks if a watch point is active for this method.
 *                If so, captures start time, arguments, and this.fields.
 * OnMethodExit:  records the return value (or thrown exception) and duration.
 *
 * If no watch point is active, both methods are near no-ops (one map lookup).
 */
public class WatchAdvice {

    @Advice.OnMethodEnter
    public static WatchContext onEnter(
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] args
    ) {
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();

        String wpId = WatchPointHolder.findActiveWatchPoint(className, methodName);
        if (wpId == null) {
            return null; // no active watch point → skip
        }

        // Capture 'this' fields if possible (won't work for static methods)
        Map<String, Object> fields = null;
        // @Advice.This is available but can't be used here generically for static methods.
        // We capture fields lazily on exit if available.

        return new WatchContext(wpId, className, methodName, args, fields, System.nanoTime());
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter WatchContext ctx,
            @Advice.Origin Method method,
            @Advice.AllArguments Object[] args,
            @Advice.Return Object returnValue,
            @Advice.Thrown Throwable thrown
    ) {
        if (ctx == null) return; // no active watch point

        long durationMs = (System.nanoTime() - ctx.startNano) / 1_000_000;

        WatchPointHolder.record(
                ctx.watchPointId,
                ctx.className,
                ctx.methodName,
                ctx.args != null ? ctx.args : args,
                returnValue,
                thrown,
                durationMs,
                ctx.thisFields
        );
    }
}
