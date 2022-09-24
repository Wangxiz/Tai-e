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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds call graph via CTA.
 */
public final class CTABuilder extends AbstractXTABuilder {

    // a map from a class to all instantiated classes for all fields and methods in the class
    private MultiMap<JClass, JClass> iClassesPerClass;

    // a multimap from a method (class) to the fields (classes) which are stored by the methods
    private MultiMap<JClass, JClass> stores;
    // a multimap from a field (class) to the methods (classes) which load the field
    private MultiMap<JClass, JClass> loads;

    private TwoKeyMap<JClass, JClass, Set<Pair<Invoke, JMethod>>> pending;

    @Override
    protected void customInit() {
        super.customInit();
        iClassesPerClass = Maps.newMultiMap();
        stores = Maps.newMultiMap();
        loads = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();
    }

    @Override
    protected void propagateToMethod(Set<JClass> classes, JMethod method) {
        JClass clazz = method.getDeclaringClass();
        boolean changed = false;
        for (JClass instanceClass : classes) {
            boolean cgd = iClassesPerClass.put(clazz, instanceClass);
            changed |= cgd;
            if (cgd) {
                resolvePending(instanceClass, method);
            }
        }
        if (changed) {
            clazz.getDeclaredMethods().stream()
                    .filter(Predicate.not(JMethod::isAbstract))
                    .forEach(m -> {
                        propagateCalleeToCallers(m);
                        propagateCallerToCallees(m);
                    });
            propagateMethodToFields(method);
        }
    }

    @Override
    protected void propagateToField(Set<JClass> classes, JField field) {
        JClass clazz = field.getDeclaringClass();
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= iClassesPerClass.put(clazz, instanceClass);
        }
        if (changed) {
            propagateFieldToMethods(field);
        }
    }

    private void propagateMethodToField(JMethod method, JField field) {
        Set<JClass> classes = getFieldSubTypes(field).stream()
                .filter(c -> iClassesPerClass.contains(method.getDeclaringClass(), c))
                .collect(Collectors.toSet());
        propagateToField(classes, field);
    }

    private void propagateMethodToFields(JMethod method) {
        stores.get(method.getDeclaringClass())
                .stream()
                .map(JClass::getDeclaredFields)
                .flatMap(Collection::stream)
                .forEach(field -> propagateMethodToField(method, field));
    }

    private void propagateFieldToMethod(JField field, JMethod method) {
        propagateToMethod(iClassesPerClass.get(field.getDeclaringClass()), method);
    }

    private void propagateFieldToMethods(JField field) {
        loads.get(field.getDeclaringClass())
                .stream()
                .map(JClass::getDeclaredMethods)
                .flatMap(Collection::stream)
                .forEach(method -> propagateFieldToMethod(field, method));
    }

    @Override
    protected void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> iClassesPerClass.contains(caller.getDeclaringClass(), c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, callee);
    }

    @Override
    protected void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> iClassesPerClass.contains(callee.getDeclaringClass(), c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, caller);
    }

    @Override
    protected void processNewStmt(New stmt) {
        NewExp newExp = stmt.getRValue();
        JMethod method = stmt.getContainer();
        JClass srcClass = method.getDeclaringClass();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!iClassesPerClass.contains(srcClass, instanceClass)) {
                boolean changed = iClassesPerClass.put(srcClass, instanceClass);
                if (changed) {
                    resolvePending(instanceClass, method);
                    srcClass.getDeclaredMethods()
                            .stream()
                            .filter(Predicate.not(JMethod::isAbstract))
                            .forEach(m -> {
                                propagateCalleeToCallers(m);
                                propagateCallerToCallees(m);
                            });
                    propagateMethodToFields(method);
                }
            }
        }
    }

    @Override
    protected void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass mtdClazz = method.getDeclaringClass();
            JClass fldClazz = field.getDeclaringClass();
            propagateMethodToField(method, field);
            stores.put(mtdClazz, fldClazz);
        }
    }

    @Override
    protected void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            JClass mtdClazz = method.getDeclaringClass();
            JClass fldClazz = field.getDeclaringClass();
            propagateFieldToMethod(field, method);
            loads.put(fldClazz, mtdClazz);
        }
    }

    @Override
    protected void resolvePending(JClass instanceClass, JMethod method) {
        JClass clazz = method.getDeclaringClass();
        pending.getOrDefault(instanceClass, clazz, Set.of()).forEach(this::update);
    }

    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        JClass callerClass = callSite.getContainer().getDeclaringClass();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(c -> iClassesPerClass.contains(callerClass, c), Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(targetClass -> {
                JMethod method = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(method)) {
                    pending.computeIfAbsent(targetClass, callerClass, (c, m) -> Sets.newSet())
                            .add(new Pair<>(callSite, method));
                }
            });
            Set<JMethod> methods = Sets.newSet();
            classes.getOrDefault(true, Set.of()).forEach(targetClass -> {
                JMethod callee = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(callee)) {
                    methods.add(callee);
                    JClass calleeClass = callee.getDeclaringClass();
                    boolean changed = iClassesPerClass.put(calleeClass, targetClass);
                    if (changed) {
                        resolvePending(targetClass, callee);
                        calleeClass.getDeclaredMethods()
                                .stream()
                                .filter(Predicate.not(JMethod::isAbstract))
                                .forEach(method -> {
                                    propagateCalleeToCallers(method);
                                    propagateCallerToCallees(method);
                                });
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
