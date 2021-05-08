/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.exception;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;

import java.util.Map;
import java.util.Set;

public class ExceptionTest {

    private static final String MAIN = "Exceptions";

    @BeforeClass
    public static void buildWorld() {
        System.setProperty("ENABLE_JIMPLE_OPT", "true");
        Main.buildWorld("-pp", "-cp", "test-resources/basic", "-m", MAIN);
    }

    @AfterClass
    public static void clear() {
        System.clearProperty("ENABLE_JIMPLE_OPT");
    }

    @Test
    public void testCatchImplicit() {
        showCatch("explicit", "implicitCaught", "implicitUncaught");
    }

    @Test
    public void testCatchThrow() {
        showCatch("all", "throwCaught", "throwUncaught", "nestedThrowCaught");
    }

    @Test
    public void testCatchDeclared() {
        showCatch("explicit", "declaredCaught", "declaredUncaught");
    }

    private static void showCatch(String exception, String... methodNames) {
        JClass c = World.getClassHierarchy().getClass(MAIN);
        ThrowAnalysis throwAnalysis = new ThrowAnalysis(
                new AnalysisConfig(ThrowAnalysis.ID, "exception", exception));
        for (String methodName : methodNames) {
            JMethod m = c.getDeclaredMethod(methodName);
            System.out.println(m);
            ThrowResult throwResult = throwAnalysis.analyze(m.getIR());
            CatchResult result = CatchAnalysis.analyze(
                    m.getIR(), throwResult);
            m.getIR().getStmts().forEach(stmt -> {
                Map<Stmt, Set<ClassType>> caught = result.getCaughtOf(stmt);
                Set<ClassType> uncaught = result.getUncaughtOf(stmt);
                if (!caught.isEmpty() || !uncaught.isEmpty()) {
                    System.out.printf("%s(@L%d)%n", stmt, stmt.getLineNumber());
                    if (!caught.isEmpty()) {
                        System.out.println("Caught exceptions:");
                        caught.forEach((s, e) ->
                                System.out.printf("%s(@L%d): %s%n", s, s.getLineNumber(), e));
                    }
                    if (!uncaught.isEmpty()) {
                        System.out.println("Uncaught exceptions: " + uncaught);
                    }
                    System.out.println();
                }
            });
            System.out.println("------------------------------");
        }
    }
}
