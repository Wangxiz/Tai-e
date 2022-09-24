package pascal.taie.analysis.graph.callgraph;

import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.Optional;
import java.util.Set;

public abstract class AbstractXTABuilder extends PropagationBasedBuilder {

    private MultiMap<JField, JClass> fieldSubTypes;
    private MultiMap<JMethod, JClass> paramSubTypes;
    private MultiMap<JMethod, JClass> returnSubTypes;

    @Override
    protected void customInit() {
        fieldSubTypes = Maps.newMultiMap();
        paramSubTypes = Maps.newMultiMap();
        returnSubTypes = Maps.newMultiMap();
    }

    protected Set<JClass> getFieldSubTypes(JField field) {
        if (field.getType() instanceof ClassType classType) {
            JClass jClass = classType.getJClass();
            if (!fieldSubTypes.containsKey(field)) {
                fieldSubTypes.putAll(field, getSubTypes(jClass));
            }
            return fieldSubTypes.get(field);
        } else {
            return Set.of();
        }
    }

    protected Set<JClass> getParamSubTypes(JMethod callee) {
        if (!paramSubTypes.containsKey(callee)) {
            Set<JClass> paramTypes = getSubTypes(getParamTypes(callee));
            paramSubTypes.putAll(callee, paramTypes);
        }
        return paramSubTypes.get(callee);
    }

    protected Set<JClass> getReturnSubTypes(JMethod callee) {
        Optional<JClass> returnType = getReturnType(callee);
        if (returnType.isPresent()) {
            if (!returnSubTypes.containsKey(callee)) {
                Set<JClass> returnTypes = getSubTypes(returnType.get());
                returnSubTypes.putAll(callee, returnTypes);
            }
            return returnSubTypes.get(callee);
        } else {
            return Set.of();
        }
    }

    protected abstract void propagateToMethod(Set<JClass> classes, JMethod method);
    protected abstract void propagateToField(Set<JClass> classes, JField field);

    protected abstract void propagateCallerToCallee(JMethod caller, JMethod callee);

    protected void propagateCallerToCallees(JMethod caller) {
        callGraph.getCalleesOfM(caller)
                .forEach(callee -> propagateCallerToCallee(caller, callee));
    }

    protected abstract void propagateCalleeToCaller(JMethod callee, JMethod caller);

    protected void propagateCalleeToCallers(JMethod callee) {
        callGraph.getCallersOf(callee)
                .stream()
                .map(Invoke::getContainer)
                .forEach(caller -> propagateCalleeToCaller(callee, caller));
    }

    protected abstract void processStoreField(JMethod method, StoreField storeField);
    protected abstract void processLoadField(JMethod method, LoadField loadField);

    @Override
    protected void propagateMethod(JMethod method) {
        method.getIR().forEach(stmt -> {
            if (stmt instanceof New newStmt) {
                processNewStmt(newStmt);
            } else if (stmt instanceof StoreField storeField) {
                processStoreField(method, storeField);
            } else if (stmt instanceof LoadField loadField) {
                processLoadField(method, loadField);
            }
        });
        callGraph.getCallSitesIn(method).forEach(this::processCallSite);
    }

    @Override
    protected void processCallSite(Invoke callSite) {
        JMethod caller = callSite.getContainer();
        resolveCalleesOf(callSite).forEach(callee -> {
            if (!callGraph.contains(callee)) {
                workList.add(callee);
            }
            addCGEdge(callSite, callee);
            propagateCallerToCallee(caller, callee);
            propagateCalleeToCaller(callee, caller);
        });
    }

    protected abstract void resolvePending(JClass instanceClass, JMethod method);

}
