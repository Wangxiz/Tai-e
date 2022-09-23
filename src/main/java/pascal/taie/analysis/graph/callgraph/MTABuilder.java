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
import pascal.taie.util.collection.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds call graph via MTA.
 */
public final class MTABuilder extends PropagationBasedBuilder {

    private MultiMap<JClass, JClass> mapC;
    private MultiMap<JField, JClass> mapF;
    private TwoKeyMap<JClass, JClass, Set<Pair<Invoke, JMethod>>> pending;

    private MultiMap<JField, JClass> fieldSubTypes;
    private MultiMap<JMethod, JClass> paramSubTypes;
    private MultiMap<JMethod, JClass> returnSubTypes;

    private MultiMap<JClass, JField> classToStores;
    private MultiMap<JField, JClass> loadToClasses;

    @Override
    protected void customInit() {
        mapC = Maps.newMultiMap();
        mapF = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();

        fieldSubTypes = Maps.newMultiMap();
        paramSubTypes = Maps.newMultiMap();
        returnSubTypes = Maps.newMultiMap();

        classToStores = Maps.newMultiMap();
        loadToClasses = Maps.newMultiMap();
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

    private void propagateToClass(Set<JClass> classes, JClass clazz) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            boolean cgd = mapC.put(clazz, instanceClass);
            changed |= cgd;
            if (cgd) {
                resolvePending(instanceClass, clazz);
            }
        }
        if (changed) {
            clazz.getDeclaredMethods().stream()
                    .filter(Predicate.not(JMethod::isAbstract))
                    .forEach(method -> {
                        propagateCalleeToCallers(method);
                        propagateCallerToCallees(method);
                    });
            propagateClassToFields(clazz);
        }
    }

    private void propagateToField(Set<JClass> classes, JField field) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= mapF.put(field, instanceClass);
        }
        if (changed) {
            propagateFieldToClasses(field);
        }
    }

    private void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> mapC.contains(caller.getDeclaringClass(), c))
                .collect(Collectors.toSet());
        propagateToClass(classes, callee.getDeclaringClass());
    }

    private void propagateCallerToCallees(JMethod caller) {
        callGraph.getCalleesOfM(caller)
                .forEach(callee -> propagateCallerToCallee(caller, callee));
    }

    private void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> mapC.contains(callee.getDeclaringClass(), c))
                .collect(Collectors.toSet());
        propagateToClass(classes, caller.getDeclaringClass());
    }

    private void propagateCalleeToCallers(JMethod callee) {
        callGraph.getCallersOf(callee)
                .stream()
                .map(Invoke::getContainer)
                .forEach(caller -> propagateCalleeToCaller(callee, caller));
    }

    private void propagateFieldToClass(JField field, JClass clazz) {
        propagateToClass(mapF.get(field), clazz);
    }

    private void propagateFieldToClasses(JField field) {
        loadToClasses.get(field)
                .forEach(method -> propagateFieldToClass(field, method));
    }

    private void propagateClassToField(JClass clazz, JField field) {
        Set<JClass> classes = getFieldSubTypes(field).stream()
                .filter(c -> mapC.contains(clazz, c))
                .collect(Collectors.toSet());
        propagateToField(classes, field);
    }

    private void propagateClassToFields(JClass clazz) {
        classToStores.get(clazz)
                .forEach(field -> propagateClassToField(clazz, field));
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
        JClass srcClass = method.getDeclaringClass();
        NewExp newExp = stmt.getRValue();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!mapC.contains(srcClass, instanceClass)) {
                boolean changed = mapC.put(srcClass, instanceClass);
                if (changed) {
                    srcClass.getDeclaredMethods()
                            .stream()
                            .filter(Predicate.not(JMethod::isAbstract))
                            .forEach(m -> {
                                resolvePending(instanceClass, srcClass);
                                propagateCalleeToCallers(m);
                                propagateCallerToCallees(m);
                            });
                    propagateClassToFields(srcClass);
                }
            }
        }
    }

    private void resolvePending(JClass instanceClass, JClass srcClass) {
        pending.getOrDefault(instanceClass, srcClass, Set.of()).forEach(this::update);
    }

    private void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass clazz = method.getDeclaringClass();
            propagateClassToField(clazz, field);
            classToStores.put(clazz, field);
        }
    }

    private void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass clazz = method.getDeclaringClass();
            propagateFieldToClass(field, clazz);
            loadToClasses.put(field, clazz);
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
        JClass callerClass = caller.getDeclaringClass();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(c -> mapC.contains(callerClass, c), Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(targetClass -> {
                JMethod method = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(method)) {
                    pending.computeIfAbsent(targetClass, callerClass, (t, c) -> Sets.newSet())
                            .add(new Pair<>(callSite, method));
                }
            });
            Set<JMethod> methods = Sets.newSet();
            classes.getOrDefault(true, Set.of()).forEach(targetClass -> {
                JMethod callee = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(callee)) {
                    methods.add(callee);
                    JClass calleeClass = callee.getDeclaringClass();
                    boolean changed = mapC.put(calleeClass, targetClass);
                    if (changed) {
                        calleeClass.getDeclaredMethods()
                                .stream()
                                .filter(Predicate.not(JMethod::isAbstract))
                                .forEach(method -> {
                                    resolvePending(targetClass, calleeClass);
                                    propagateCalleeToCallers(method);
                                    propagateCallerToCallees(method);
                                });
                        propagateClassToFields(calleeClass);
                    }
                }
            });
            callees = methods;
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
