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

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds call graph via FTA.
 */
public final class FTABuilder extends AbstractXTABuilder {

    private MultiMap<JMethod, JClass> mapM;
    private MultiMap<JClass, JClass> mapC;
    private TwoKeyMap<JClass, JMethod, Set<Pair<Invoke, JMethod>>> pending;

    private MultiMap<JMethod, JClass> methodToClasses;
    private MultiMap<JClass, JMethod> classToMethods;

    @Override
    protected void customInit() {
        super.customInit();

        mapM = Maps.newMultiMap();
        mapC = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();

        methodToClasses = Maps.newMultiMap();
        classToMethods = Maps.newMultiMap();
    }

    @Override
    protected void propagateToMethod(Set<JClass> classes, JMethod method) {
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
            propagateMethodToClasses(method);
        }
    }

    @Override
    protected void propagateToField(Set<JClass> classes, JField field) {
        JClass clazz = field.getDeclaringClass();
        propagateToClass(classes, clazz);
    }

    private void propagateToClass(Set<JClass> classes, JClass clazz) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= mapC.put(clazz, instanceClass);
        }
        if (changed) {
            propagateClassToMethods(clazz);
        }
    }

    @Override
    protected void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> mapM.contains(caller, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, callee);
    }

    @Override
    protected void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> mapM.contains(callee, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, caller);
    }

    private void propagateClassToMethod(JClass clazz, JMethod method) {
        propagateToMethod(mapC.get(clazz), method);
    }

    private void propagateClassToMethods(JClass clazz) {
        classToMethods.get(clazz)
                .forEach(method -> propagateClassToMethod(clazz, method));
    }

    private void propagateMethodToClass(JMethod method, JClass clazz) {
        Set<JClass> classes = clazz.getDeclaredFields().stream()
                .map(this::getFieldSubTypes)
                .flatMap(Collection::stream)
                .filter(c -> mapM.contains(method, c))
                .collect(Collectors.toSet());
        propagateToClass(classes, clazz);
    }

    private void propagateMethodToClasses(JMethod method) {
        methodToClasses.get(method)
                .forEach(clazz -> propagateMethodToClass(method, clazz));
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

    @Override
    protected void processNewStmt(New stmt) {
        NewExp newExp = stmt.getRValue();
        JMethod method = stmt.getContainer();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!mapM.contains(method, instanceClass)) {
                boolean changed = mapM.put(method, instanceClass);
                if (changed) {
                    resolvePending(instanceClass, method);
                    propagateCalleeToCallers(method);
                    propagateCallerToCallees(method);
                    propagateMethodToClasses(method);
                }
            }
        }
    }

    private void resolvePending(JClass instanceClass, JMethod method) {
        pending.getOrDefault(instanceClass, method, Set.of()).forEach(this::update);
    }

    @Override
    protected void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass clazz = field.getDeclaringClass();
            propagateMethodToClass(method, clazz);
            methodToClasses.put(method, clazz);
        }
    }

    @Override
    protected void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass clazz = field.getDeclaringClass();
            propagateClassToMethod(clazz, method);
            classToMethods.put(clazz, method);
        }
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
                        propagateMethodToClasses(callee);
                    }
                }
            });
            callees = methods;
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
