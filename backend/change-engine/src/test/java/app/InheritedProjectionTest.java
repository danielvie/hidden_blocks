package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InheritedProjectionTest {
    private PropertyValue value(String number) {
        return new PropertyValue("ScalarValues::Real", number, new ScalarValue(ScalarKind.REAL, number));
    }
    private Attribute owned(String number) {
        return new Attribute("base-flow", "definition", "flowRate", value(number),
            new SourceRange(0, number.length()), true, null, "M::Pump", "base-flow", false);
    }
    private Attribute inherited(String number) {
        return new Attribute("pump-flow", "pump", "flowRate", value(number), null,
            false, "Inherited from Pump", "M::Pump", "base-flow", true);
    }
    private ModelElement definition(Attribute attribute) {
        return new ModelElement("definition", "model.sysml", "M::Pump", "Pump", "PartDefinition",
            new SourceRange(0, 100), 99, List.of(attribute));
    }
    private ModelElement usage(Attribute attribute) {
        return new ModelElement("pump", "model.sysml", "M::system::pump", "pump", "PartUsage",
            new SourceRange(101, 150), null, List.of(attribute), "M::Pump", "definition",
            List.of(), List.of(), "system");
    }
    private Analysis analysis(long revision, Attribute owned, Attribute inherited) {
        return new Analysis("alice", revision, Map.of("model.sysml", revision), AnalysisState.VALID,
            List.of(definition(owned), usage(inherited)), List.of());
    }
    @Test void definitionEditProducesOneAuthoredDeltaNotAnInheritedAssignment() {
        var edits = ChangeEngine.diff(analysis(1, owned("10"), inherited("10")),
                                     analysis(2, owned("12"), inherited("12")));
        assertEquals(1, edits.size());
        assertEquals("base-flow", edits.getFirst().target().attributeId());
    }
    @Test void spellingOnlyChangesOnDefinitionsDoNotInventInheritedEdits() {
        assertTrue(ChangeEngine.diff(analysis(1, owned("10"), inherited("10")),
            analysis(2, owned("10.0"), inherited("10.0"))).isEmpty());
    }
    @Test void unexplainedEffectiveValueChangesCannotBecomeEmptySuccessfulDeltas() {
        assertThrows(ChangeEngine.UnsupportedEditException.class, () ->
            ChangeEngine.diff(analysis(1, owned("10"), inherited("10")),
                              analysis(2, owned("10"), inherited("12"))));
    }
    @Test void unchangedReadOnlyInheritedExpressionsDoNotBlockIndependentWork() {
        var formula = new PropertyValue("ScalarValues::Real", "other * 2", null);
        var a = new Attribute("formula", "definition", "calculated", formula, new SourceRange(0, 9),
                              false, "Computed expression", "M::Pump", "formula", false);
        var inherited = new Attribute("inherited-formula", "pump", "calculated", formula, null,
                                      false, "Inherited", "M::Pump", "formula", true);
        assertTrue(ChangeEngine.diff(analysis(1, a, inherited), analysis(2, a, inherited)).isEmpty());
    }
    @Test void connectionTopologyIsNotAScalarEdit() {
        var before = analysis(1, owned("10"), inherited("10"));
        var after = new Analysis("alice", 2, Map.of("model.sysml", 2L), AnalysisState.VALID,
            before.elements(), List.of(), List.of(new Connection("pipe", null, "pump", "out", "tank", "in")));
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.diff(before, after));
    }
}
