package pascal.taie.analysis.graph.callgraph;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.ir.proginfo.MemberRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class PropagationBasedBuilder implements CGBuilder<Invoke, JMethod> {

    private static final Logger logger = LogManager.getLogger(PropagationBasedBuilder.class);

    protected DefaultCallGraph callGraph;
    protected ClassHierarchy hierarchy;
    protected TwoKeyMap<JClass, MemberRef, Set<JMethod>> resolveTable;
    protected Queue<JMethod> workList;

    protected void customInit() {}

    protected void postProcess() {}

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
        postProcess();

        return callGraph;
    }

    protected abstract void propagateMethod(JMethod method);

    protected abstract void processNewStmt(New stmt);

    protected void addCGEdge(Invoke invoke, JMethod callee) {
        callGraph.addEdge(new Edge<>(
                CallGraphs.getCallKind(invoke), invoke, callee));
    }

    protected void update(Invoke invoke, JMethod callee) {
        addCGEdge(invoke, callee);
        MethodRef methodRef = invoke.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        resolveTable.computeIfAbsent(cls, methodRef, (c, m) -> Sets.newSet()).add(callee);
    }

    protected void update(Pair<Invoke, JMethod> pair) {
        update(pair.first(), pair.second());
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

    protected Set<JMethod> resolveStaticCalleesOf(Invoke callSite) {
        return Set.of(callSite.getMethodRef().resolve());
    }

    protected Set<JMethod> resolveCalleesOf(Invoke callSite) {
        CallKind kind = CallGraphs.getCallKind(callSite);
        return switch (kind) {
            case INTERFACE, VIRTUAL -> resolveVirtualCalleesOf(callSite);
            case SPECIAL, STATIC -> resolveStaticCalleesOf(callSite);
            case DYNAMIC -> {
                logger.debug("CHA cannot resolve invokedynamic " + callSite);
                yield Set.of();
            }
            default -> throw new AnalysisException(
                    "Failed to resolve call site: " + callSite);
        };
    }

    protected Set<JClass> getSubTypes(JClass cls) {
        return hierarchy.getAllSubclassesOf(cls)
                .stream()
                .filter(Predicate.not(JClass::isAbstract))
                .collect(Collectors.toSet());
    }

    protected Set<JClass> getSubTypes(Set<JClass> classes) {
        return classes.stream()
                .map(this::getSubTypes)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    protected Set<JClass> getParamTypes(JMethod method) {
        return method.getParamTypes().stream()
                .filter(type -> type instanceof ClassType)
                .map(type -> ((ClassType) type).getJClass())
                .collect(Collectors.toSet());
    }

    protected Optional<JClass> getReturnType(JMethod method) {
        return Optional.ofNullable(method.getReturnType())
                .filter(type -> type instanceof ClassType)
                .map(type -> ((ClassType) type).getJClass());
    }

}
