package pascal.taie.analysis.graph.callgraph;

import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.NewInstance;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractXTABuilder extends PropagationBasedBuilder {

    private MultiMap<JField, JClass> fieldSubTypes;
    private MultiMap<JMethod, JClass> paramSubTypes;
    private MultiMap<JMethod, JClass> returnSubTypes;

    // a multimap from a method to the fields which are stored by the methods
    private MultiMap<JMethod, JField> stores;
    // a multimap from a field to the methods which load the field
    private MultiMap<JField, JMethod> loads;

    @Override
    protected void customInit() {
        fieldSubTypes = Maps.newMultiMap();
        paramSubTypes = Maps.newMultiMap();
        returnSubTypes = Maps.newMultiMap();
        stores = Maps.newMultiMap();
        loads = Maps.newMultiMap();
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
    protected Set<JClass> getParamTypes(JMethod method) {
        return method.getParamTypes().stream()
                .filter(type -> type instanceof ClassType)
                .map(type -> ((ClassType) type).getJClass())
                .collect(Collectors.toSet());
    }
    protected Set<JClass> getParamSubTypes(JMethod callee) {
        if (!paramSubTypes.containsKey(callee)) {
            Set<JClass> paramTypes = getSubTypes(getParamTypes(callee));
            paramSubTypes.putAll(callee, paramTypes);
        }
        return paramSubTypes.get(callee);
    }
    protected Optional<JClass> getReturnType(JMethod method) {
        return Optional.ofNullable(method.getReturnType())
                .filter(type -> type instanceof ClassType)
                .map(type -> ((ClassType) type).getJClass());
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

    protected abstract boolean containsInMethod(JMethod method, JClass clazz);
    protected abstract boolean updateClassesInMethod(JMethod method, JClass clazz);
    protected abstract Set<JClass> getClassesInMethod(JMethod method);

    protected abstract boolean containsInField(JField field, JClass clazz);
    protected abstract boolean updateClassesInField(JField field, JClass clazz);
    protected abstract Set<JClass> getClassesInField(JField field);

    protected void updateStores(JMethod method, JField field) {
        stores.put(method, field);
    }
    protected Set<JField> getStores(JMethod method) {
        return stores.get(method);
    }
    protected void updateLoads(JField field, JMethod method) {
        loads.put(field, method);
    }
    protected Set<JMethod> getLoads(JField field) {
        return loads.get(field);
    }

    protected abstract void resolvePending(JClass clazz, JMethod caller);
    protected abstract void updatePending(JClass clazz, JMethod caller, Invoke invoke, JMethod callee);

    protected boolean updateMethod(Set<JClass> classes, JMethod method) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            boolean cgd = updateClassesInMethod(method, instanceClass);
            changed |= cgd;
            if (cgd) {
                resolvePending(instanceClass, method);
            }
        }
        return changed;
    }
    protected void propagateMethod(JMethod method) {
        propagateCalleeToCallers(method);
        propagateCallerToCallees(method);
        propagateMethodToFields(method);
    }

    protected boolean updateField(Set<JClass> classes, JField field) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= updateClassesInField(field, instanceClass);
        }
        return changed;
    }
    protected void propagateField(JField field) {
        propagateFieldToMethods(field);
    }

    protected void propagateToMethod(Set<JClass> classes, JMethod method) {
        boolean changed = updateMethod(classes, method);
        if (changed) {
            propagateMethod(method);
        }
    }
    protected void propagateToField(Set<JClass> classes, JField field) {
        boolean changed = updateField(classes, field);
        if (changed) {
            propagateField(field);
        }
    }

    protected void propagateFieldToMethod(JField field, JMethod method) {
        propagateToMethod(getClassesInField(field), method);
    }
    protected void propagateFieldToMethods(JField field) {
        getLoads(field).forEach(method -> propagateFieldToMethod(field, method));
    }

    protected void propagateMethodToField(JMethod method, JField field) {
        Set<JClass> classes = getFieldSubTypes(field).stream()
                .filter(c -> containsInMethod(method, c))
                .collect(Collectors.toSet());
        propagateToField(classes, field);
    }
    protected void propagateMethodToFields(JMethod method) {
        getStores(method).forEach(field -> propagateMethodToField(method, field));
    }

    protected void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> containsInMethod(caller, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, callee);
    }
    protected void propagateCallerToCallees(JMethod caller) {
        callGraph.getCalleesOfM(caller)
                .forEach(callee -> propagateCallerToCallee(caller, callee));
    }
    protected void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> containsInMethod(callee, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, caller);
    }
    protected void propagateCalleeToCallers(JMethod callee) {
        callGraph.getCallersOf(callee)
                .stream()
                .map(Invoke::getContainer)
                .forEach(caller -> propagateCalleeToCaller(callee, caller));
    }

    @Override
    protected void processMethod(JMethod method) {
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
    @Override
    protected void processNewStmt(New stmt) {
        NewExp newExp = stmt.getRValue();
        JMethod method = stmt.getContainer();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!containsInMethod(method, instanceClass)) {
                boolean changed = updateClassesInMethod(method, instanceClass);
                if (changed) {
                    resolvePending(instanceClass, method);
                    propagateMethod(method);
                }
            }
        }
    }
    protected void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            updateStores(method, field);
            propagateMethodToField(method, field);
        }
    }
    protected void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            updateLoads(field, method);
            propagateFieldToMethod(field, method);
        }
    }

    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            JMethod caller = callSite.getContainer();
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(clazz -> containsInMethod(caller, clazz), Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(clazz -> {
                JMethod callee = hierarchy.dispatch(clazz, methodRef);
                if (Objects.nonNull(callee)) {
                    updatePending(clazz, caller, callSite, callee);
                }
            });
            Set<JMethod> methods = Sets.newSet();
            classes.getOrDefault(true, Set.of()).forEach(clazz -> {
                JMethod callee = hierarchy.dispatch(clazz, methodRef);
                if (Objects.nonNull(callee)) {
                    methods.add(callee);
                    boolean changed = updateClassesInMethod(callee, clazz);
                    if (changed) {
                        resolvePending(clazz, callee);
                        propagateMethod(callee);
                    }
                }
            });
            callees = methods;
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
