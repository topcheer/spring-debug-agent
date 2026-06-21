package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * Spring State Machine diagnostic tools.
 * Inspects registered state machines, current states, and transition tables
 * via reflection when spring-statemachine-core is on the classpath.
 */
public class StateMachineInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all registered Spring State Machines: machine ID, current state, list of states, and list of transitions. Useful for verifying state machine configuration or diagnosing transitions that won't fire.")
    public List<Map<String, Object>> getStateMachines() {
        List<Map<String, Object>> machines = new ArrayList<>();

        List<Object> stateMachines = findStateMachines();
        if (stateMachines.isEmpty()) {
            machines.add(Map.of("error", "No StateMachine beans found. Add spring-statemachine-core."));
            return machines;
        }

        for (Object sm : stateMachines) {
            machines.add(describeStateMachine(sm));
        }
        return machines;
    }

    @DebugTool(description = "Snapshot of all active state machine instances and their current states. Lightweight alternative to getStateMachines for runtime monitoring — returns machine ID, current state ID, and whether the machine is complete.")
    public List<Map<String, Object>> getCurrentStates() {
        List<Map<String, Object>> snapshots = new ArrayList<>();

        List<Object> stateMachines = findStateMachines();
        if (stateMachines.isEmpty()) {
            snapshots.add(Map.of("error", "No StateMachine beans found."));
            return snapshots;
        }

        for (Object sm : stateMachines) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("machineId", resolveMachineId(sm));
            Object current = ReflectionHelper.invokeMethod(sm, "getState");
            if (current != null) {
                snap.put("currentState", stateId(current));
                Object ids = ReflectionHelper.invokeMethod(current, "getIds");
                snap.put("currentStateIds", idsToList(ids));
            }
            snap.put("isComplete", ReflectionHelper.invokeMethod(sm, "isComplete"));
            snap.put("stopped", stoppedStatus(sm));
            snapshots.add(snap);
        }
        return snapshots;
    }

    @DebugTool(description = "List all transitions for a state machine: source state, target state, trigger event, and any guard/action metadata. Useful for tracing why a particular transition did or did not happen.")
    public List<Map<String, Object>> getStateTransitions(
            @ToolParam(description = "Machine ID (leave empty for the only/first machine)", required = false) String machineId
    ) {
        List<Map<String, Object>> transitions = new ArrayList<>();

        Object target = null;
        for (Object sm : findStateMachines()) {
            if (machineId == null || machineId.isBlank() || machineId.equals(resolveMachineId(sm))) {
                target = sm;
                break;
            }
        }
        if (target == null) {
            transitions.add(Map.of("error", "No state machine matched machineId=" + machineId));
            return transitions;
        }

        Object transitionsCollection = ReflectionHelper.invokeMethod(target, "getTransitions");
        if (!(transitionsCollection instanceof Collection<?> coll)) {
            transitions.add(Map.of("error", "Could not read transitions."));
            return transitions;
        }

        for (Object t : coll) {
            Map<String, Object> info = new LinkedHashMap<>();
            Object source = ReflectionHelper.invokeMethod(t, "getSource");
            Object targetState = ReflectionHelper.invokeMethod(t, "getTarget");
            Object trigger = ReflectionHelper.invokeMethod(t, "getTrigger");

            info.put("source", source != null ? stateId(source) : null);
            info.put("sourceIds", source != null ? idsToList(ReflectionHelper.invokeMethod(source, "getIds")) : null);
            info.put("target", targetState != null ? stateId(targetState) : null);
            info.put("targetIds", targetState != null
                    ? idsToList(ReflectionHelper.invokeMethod(targetState, "getIds"))
                    : null);
            info.put("triggerEvent", describeTrigger(trigger));
            // Guard/actions (best-effort)
            Object guard = ReflectionHelper.invokeMethod(t, "getGuard");
            if (guard != null) info.put("guard", guard.getClass().getSimpleName());
            Object actions = ReflectionHelper.invokeMethod(t, "getActions");
            if (actions instanceof Collection<?> acts && !acts.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object a : acts) names.add(a.getClass().getSimpleName());
                info.put("actions", names);
            }
            transitions.add(info);
        }

        if (transitions.isEmpty()) {
            transitions.add(Map.of("note", "No transitions defined for this machine."));
        }
        return transitions;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private List<Object> findStateMachines() {
        try {
            Class<?> smClass = Class.forName("org.springframework.statemachine.StateMachine",
                    false, ctx.getClassLoader());
            String[] names = ctx.getBeanNamesForType(smClass);
            List<Object> beans = new ArrayList<>();
            for (String n : names) {
                try {
                    beans.add(ctx.getBean(n));
                } catch (Exception ignored) {}
            }
            // Also try scanning all beans for StateMachine interface implementations
            if (beans.isEmpty()) {
                for (String name : ctx.getBeanDefinitionNames()) {
                    try {
                        Object bean = ctx.getBean(name);
                        for (Class<?> iface : bean.getClass().getInterfaces()) {
                            if (iface.getName().equals("org.springframework.statemachine.StateMachine")) {
                                beans.add(bean);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            return beans;
        } catch (ClassNotFoundException e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> describeStateMachine(Object sm) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("machineId", resolveMachineId(sm));

        Object current = ReflectionHelper.invokeMethod(sm, "getState");
        if (current != null) {
            info.put("currentState", stateId(current));
            info.put("currentStateIds", idsToList(ReflectionHelper.invokeMethod(current, "getIds")));
        }

        Object statesCollection = ReflectionHelper.invokeMethod(sm, "getStates");
        if (statesCollection instanceof Collection<?> coll) {
            List<Map<String, Object>> states = new ArrayList<>();
            for (Object s : coll) {
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("id", stateId(s));
                st.put("ids", idsToList(ReflectionHelper.invokeMethod(s, "getIds")));
                Object pseudo = ReflectionHelper.invokeMethod(s, "getPseudoState");
                if (pseudo != null) {
                    Object kind = ReflectionHelper.invokeMethod(pseudo, "getKind");
                    st.put("pseudoKind", kind != null ? kind.toString() : null);
                }
                states.add(st);
            }
            info.put("states", states);
            info.put("stateCount", states.size());
        }

        Object transitions = ReflectionHelper.invokeMethod(sm, "getTransitions");
        if (transitions instanceof Collection<?> coll) {
            info.put("transitionCount", coll.size());
        }

        info.put("isComplete", ReflectionHelper.invokeMethod(sm, "isComplete"));
        info.put("stopped", stoppedStatus(sm));
        return info;
    }

    private String resolveMachineId(Object sm) {
        try {
            Object id = ReflectionHelper.invokeMethod(sm, "getId");
            return id != null ? id.toString() : sm.getClass().getSimpleName();
        } catch (Exception e) {
            return sm.getClass().getSimpleName();
        }
    }

    private String stateId(Object state) {
        if (state == null) return null;
        Object id = ReflectionHelper.invokeMethod(state, "getId");
        return id != null ? id.toString() : state.toString();
    }

    private List<String> idsToList(Object ids) {
        if (ids instanceof Collection<?> coll) {
            List<String> result = new ArrayList<>();
            for (Object id : coll) result.add(id != null ? id.toString() : null);
            return result;
        }
        return ids != null ? List.of(ids.toString()) : Collections.emptyList();
    }

    private String describeTrigger(Object trigger) {
        if (trigger == null) return null;
        Object event = ReflectionHelper.invokeMethod(trigger, "getEvent");
        return event != null ? event.toString() : trigger.getClass().getSimpleName();
    }

    private Object stoppedStatus(Object sm) {
        Object status = ReflectionHelper.invokeMethod(sm, "getState");
        // Best-effort: try isStopped() (some versions) — fall back to null.
        try {
            return ReflectionHelper.invokeMethod(sm, "isStopped");
        } catch (Exception e) {
            return null;
        }
    }
}
