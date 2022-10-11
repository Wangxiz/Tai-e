package pascal.taie.analysis.graph.callgraph;

import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

public final class VTABuilder implements CGBuilder<Invoke, JMethod> {

    @Override
    public CallGraph<Invoke, JMethod> build() {
        return null;
    }

}
