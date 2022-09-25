package pascal.taie.analysis.graph.callgraph;

import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.Set;

public interface IXTABuilder {

    boolean containsInMethod(JMethod method, JClass clazz);
    boolean updateClassesInMethod(JMethod method, JClass clazz);
    Set<JClass> getClassesInMethod(JMethod method);
    boolean containsInField(JField field, JClass clazz);
    boolean updateClassesInField(JField field, JClass clazz);
    Set<JClass> getClassesInField(JField field);

}
