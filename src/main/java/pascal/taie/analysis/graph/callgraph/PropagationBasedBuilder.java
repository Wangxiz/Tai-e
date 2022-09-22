package pascal.taie.analysis.graph.callgraph;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.ir.proginfo.MemberRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;

public abstract class PropagationBasedBuilder implements CGBuilder<Invoke, JMethod> {

    private static final Logger logger = LogManager.getLogger(PropagationBasedBuilder.class);

    protected DefaultCallGraph callGraph;
    protected ClassHierarchy hierarchy;
    protected TwoKeyMap<JClass, MemberRef, Set<JMethod>> resolveTable;
    protected Queue<JMethod> workList;

    protected void customInit() {}

    @Override
    public CallGraph<Invoke, JMethod> build() {
        return buildCallGraph(World.get().getMainMethod());
    }

    protected CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);

        hierarchy = World.get().getClassHierarchy();
        resolveTable = Maps.newTwoKeyMap();

        workList = new ArrayDeque<>();
        workList.add(entry);

        customInit();

        while (!workList.isEmpty()) {
            JMethod method = workList.poll();
            callGraph.addReachableMethod(method);
            propagateMethod(method);
        }
        return callGraph;
    }

    protected abstract void propagateMethod(JMethod method);

    protected void addCGEdge(Invoke invoke, JMethod callee) {
        callGraph.addEdge(new Edge<>(
                CallGraphs.getCallKind(invoke), invoke, callee));
    }

    protected void processCallSite(Invoke callSite) {
        resolveCalleesOf(callSite).forEach(callee -> {
            if (!callGraph.contains(callee)) {
                workList.add(callee);
            }
            addCGEdge(callSite, callee);
        });
    }

    protected abstract Set<JMethod> resolveVirtualCalleesOf(Invoke callSite);

    protected Set<JMethod> resolveCalleesOf(Invoke callSite) {
        CallKind kind = CallGraphs.getCallKind(callSite);
        return switch (kind) {
            case INTERFACE, VIRTUAL -> resolveVirtualCalleesOf(callSite);
            case SPECIAL, STATIC -> Set.of(callSite.getMethodRef().resolve());
            case DYNAMIC -> {
                logger.debug("CHA cannot resolve invokedynamic " + callSite);
                yield Set.of();
            }
            default -> throw new AnalysisException(
                    "Failed to resolve call site: " + callSite);
        };
    }

}
