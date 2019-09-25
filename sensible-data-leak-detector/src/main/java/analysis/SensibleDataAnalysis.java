package analysis;

import static analysis.abstraction.SensibilityLattice.BOTTOM;
import static analysis.abstraction.SensibilityLattice.HIGH;
import static analysis.abstraction.SensibilityLattice.supremeBetween;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import analysis.abstraction.SensibilityLattice;
import org.slf4j.Logger;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

public class SensibleDataAnalysis extends ForwardFlowAnalysis<Unit, Map<String, SensibilityLattice>> {

  private final Logger LOGGER = getLogger(SensibleDataAnalysis.class);

  private Map<String, SensibilityLattice> startingLocalsMap;
  private Map<Unit, Boolean> possibleLeakInUnit;
  private Set<String> offendingMethod;

  public SensibleDataAnalysis(ExceptionalUnitGraph graph) {
    super(graph);

    offendingMethod = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                                                                              "println", "print")));

    startingLocalsMap = new HashMap<>();
    possibleLeakInUnit = new HashMap<>();

    // As starting point, save all locals as bottom
    for (Local variable : graph.getBody().getLocals()) {
      startingLocalsMap.put(variable.getName(), SensibilityLattice.getBottom());
    }

    doAnalysis();
  }

  @Override
  protected void flowThrough(Map<String, SensibilityLattice> in, Unit unit,
                             Map<String, SensibilityLattice> out) {
    if (unit instanceof DefinitionStmt) {
      DefinitionStmt definition = (DefinitionStmt) unit;
    } else if (unit instanceof InvokeStmt) {
      InvokeExpr invokeExpr = ((InvokeStmt) unit).getInvokeExpr();
      SootMethod invokedMethod = invokeExpr.getMethod();
      if (invokedMethod.getDeclaringClass().getName().equals("analysis.SensibilityMarker") &&
          invokedMethod.getName().equals("markAsSensible")) {
        // Marking the argument as sensible, if it's a local
        assert invokeExpr.getArgCount() == 1;
        Value argument = invokeExpr.getArg(0);
        if (argument instanceof Local) {
          Local sensibleLocal = (Local) argument;
          LOGGER.warn("Just discovered a sensible variable named {}", sensibleLocal.getName());
          in.put(sensibleLocal.getName(), HIGH);
        }
      } else if (offendingMethod.contains(invokedMethod.getName())) {
        // This method is offending, if it has a sensible variable, WARN
        LOGGER.warn("Just found offending method call");
        invokeExpr.getArgs().stream()
            .filter(argument -> new ContainsSensibleVariableVisitor(in).visit(argument).done())
            .findFirst()
            .ifPresent(sensibleArgument -> LOGGER.warn("Local variable named {} is being leaked", sensibleArgument));
      }
    }
    out.clear();
    out.putAll(in);
  }

  @Override
  protected Map<String, SensibilityLattice> newInitialFlow() {
    Map<String, SensibilityLattice> newMap = new HashMap<>();
    newMap.putAll(startingLocalsMap);
    return newMap;
  }

  @Override
  protected void merge(Map<String, SensibilityLattice> input1, Map<String, SensibilityLattice> input2,
                       Map<String, SensibilityLattice> out) {
    out.clear();
    out.putAll(input1);
    // On conflicting values, take supreme to make analysis sound
    for (String variable : input2.keySet()) {
      SensibilityLattice currentValue = out.getOrDefault(variable, BOTTOM);
      out.put(variable, supremeBetween(input2.get(variable), currentValue));
    }

  }

  @Override
  protected void copy(Map<String, SensibilityLattice> input, Map<String, SensibilityLattice> out) {
    out.clear();
    out.putAll(input);
  }
}
