/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

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
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds call graph via XTA.
 */
public final class XTABuilder extends PropagationBasedBuilder {

    private MultiMap<JMethod, JClass> mapM;
    private MultiMap<JField, JClass> mapF;
    private TwoKeyMap<JClass, JMethod, Set<Pair<Invoke, JMethod>>> pending;

    private MultiMap<JField, JClass> fieldSubTypes;
    private MultiMap<JMethod, JClass> paramSubTypes;
    private MultiMap<JMethod, JClass> returnSubTypes;

    private MultiMap<JMethod, JField> methodToStores;
    private MultiMap<JField, JMethod> loadToMethods;

    @Override
    protected void customInit() {
        mapM = Maps.newMultiMap();
        mapF = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();

        fieldSubTypes = Maps.newMultiMap();
        paramSubTypes = Maps.newMultiMap();
        returnSubTypes = Maps.newMultiMap();

        methodToStores = Maps.newMultiMap();
        loadToMethods = Maps.newMultiMap();
    }

    private Set<JClass> getFieldSubTypes(JField field) {
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

    private Set<JClass> getParamSubTypes(JMethod callee) {
        if (!paramSubTypes.containsKey(callee)) {
            Set<JClass> paramTypes = getSubTypes(getParamTypes(callee));
            paramSubTypes.putAll(callee, paramTypes);
        }
        return paramSubTypes.get(callee);
    }

    private Set<JClass> getReturnSubTypes(JMethod callee) {
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

    private void propagateToMethod(Set<JClass> classes, JMethod method) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            boolean cgd = mapM.put(method, instanceClass);
            changed |= cgd;
            if (cgd) {
                resolvePending(instanceClass, method);
            }
        }
        if (changed) {
            propagateCalleeToCallers(method);
            propagateCallerToCallees(method);
            propagateMethodToFields(method);
        }
    }

    private void propagateToField(Set<JClass> classes, JField field) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= mapF.put(field, instanceClass);
        }
        if (changed) {
            propagateFieldToMethods(field);
        }
    }

    private void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> mapM.contains(caller, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, callee);
    }

    private void propagateCallerToCallees(JMethod caller) {
        callGraph.getCalleesOfM(caller)
                .forEach(callee -> propagateCallerToCallee(caller, callee));
    }

    private void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> mapM.contains(callee, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, caller);
    }

    private void propagateCalleeToCallers(JMethod callee) {
        callGraph.getCallersOf(callee)
                .stream()
                .map(Invoke::getContainer)
                .forEach(caller -> propagateCalleeToCaller(callee, caller));
    }

    private void propagateFieldToMethod(JField field, JMethod method) {
        propagateToMethod(mapF.get(field), method);
    }

    private void propagateFieldToMethods(JField field) {
        loadToMethods.get(field)
                .forEach(method -> propagateFieldToMethod(field, method));
    }

    private void propagateMethodToField(JMethod method, JField field) {
        Set<JClass> classes = getFieldSubTypes(field).stream()
                .filter(c -> mapM.contains(method, c))
                .collect(Collectors.toSet());
        propagateToField(classes, field);
    }

    private void propagateMethodToFields(JMethod method) {
        methodToStores.get(method)
                .forEach(field -> propagateMethodToField(method, field));
    }

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

    private void processNewStmt(New stmt) {
        JMethod method = stmt.getContainer();
        NewExp newExp = stmt.getRValue();
        if (newExp instanceof NewInstance newInstance) {
            JClass jClass = newInstance.getType().getJClass();
            if (!mapM.contains(method, jClass)) {
                boolean changed = mapM.put(method, jClass);
                if (changed) {
                    resolvePending(jClass, method);
                    propagateCalleeToCallers(method);
                    propagateCallerToCallees(method);
                    propagateMethodToFields(method);
                }
            }
        }
    }

    private void resolvePending(JClass jClass, JMethod method) {
        pending.getOrDefault(jClass, method, Set.of()).forEach(this::update);
    }

    private void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            propagateMethodToField(method, field);
            methodToStores.put(method, field);
        }
    }

    private void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            propagateFieldToMethod(field, method);
            loadToMethods.put(field, method);
        }
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
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        JMethod caller = callSite.getContainer();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(c -> mapM.contains(caller, c), Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(targetClass -> {
                JMethod method = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(method)) {
                    pending.computeIfAbsent(targetClass, caller, (c, m) -> Sets.newSet())
                            .add(new Pair<>(callSite, method));
                }
            });
            Set<JMethod> methods = Sets.newSet();
            classes.getOrDefault(true, Set.of()).forEach(targetClass -> {
                JMethod callee = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(callee)) {
                    methods.add(callee);
                    boolean changed = mapM.put(callee, targetClass);
                    if (changed) {
                        resolvePending(targetClass, callee);
                        propagateCalleeToCallers(callee);
                        propagateCallerToCallees(callee);
                        propagateMethodToFields(callee);
                    }
                }
            });
            callees = methods;
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
