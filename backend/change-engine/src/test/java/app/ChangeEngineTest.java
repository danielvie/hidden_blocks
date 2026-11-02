package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeEngineTest {
    private static final SourceRange RANGE = new SourceRange(0, 1);

    private static PropertyValue real(String expression) {
        return new PropertyValue("ScalarValues::Real", expression, new ScalarValue(ScalarKind.REAL, expression));
    }

    private static Attribute attribute(String id, String name, PropertyValue value) {
        return new Attribute(id, "pump", name, value, RANGE, true, null);
    }

    private static Attribute flow(String value) { return attribute("flow", "flowRate", real(value)); }

    private static Attribute alert() {
        return attribute("alert", "alertEnabled", new PropertyValue("ScalarValues::Boolean", "true",
                new ScalarValue(ScalarKind.BOOLEAN, "true")));
    }

    private static Analysis analysis(Attribute... attributes) {
        return new Analysis("w", 1, Map.of("model.sysml", 1L), AnalysisState.VALID,
                List.of(new ModelElement("pump", "model.sysml", "Demo::Pump", "Pump", "part def", RANGE,
                        1, List.of(attributes))), List.of());
    }

    private static Analysis materialize(Analysis current, List<AuthoredEdit> patches) {
        List<ModelElement> elements = new ArrayList<>();
        for (ModelElement owner : current.elements()) {
            List<Attribute> attributes = new ArrayList<>(owner.attributes());
            for (AuthoredEdit patch : patches) {
                if (!patch.target().elementId().equals(owner.id())) continue;
                Attribute prior = attributes.stream().filter(a -> a.id().equals(patch.target().attributeId()))
                        .findFirst().orElse(null);
                assertEquals(prior == null ? null : prior.value(), patch.before());
                assertEquals(prior == null ? EditKind.ADD_ATTRIBUTE : EditKind.SET_ATTRIBUTE, patch.kind());
                if (prior != null) attributes.remove(prior);
                attributes.add(new Attribute(patch.target().attributeId(), owner.id(), patch.attributeName(),
                        patch.after(), RANGE, true, null));
            }
            elements.add(new ModelElement(owner.id(), owner.documentId(), owner.qualifiedName(), owner.name(),
                    owner.kind(), owner.declarationRange(), owner.insertionOffset(), attributes));
        }
        return new Analysis(current.workspaceId(), current.workspaceRevision() + 1, current.documentVersions(),
                AnalysisState.VALID, elements, List.of());
    }

    @Test void scalarDiffUsesCanonicalValueAndResolvedType() {
        assertTrue(ChangeEngine.diff(analysis(flow("10.00")), analysis(flow("1e1"))).isEmpty());
        List<AuthoredEdit> edits = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15"), alert()));
        assertEquals(2, edits.size());
        assertEquals(EditKind.SET_ATTRIBUTE, edits.get(0).kind());
        assertEquals(real("10"), edits.get(0).before());
        assertEquals(EditKind.ADD_ATTRIBUTE, edits.get(1).kind());
        assertNull(edits.get(1).before());
        assertThrows(UnsupportedOperationException.class, () -> edits.clear());
    }

    private static ChangeEngine.MergeResult apply(List<AuthoredEdit> edits, Analysis current,
                                                   List<Conflict> inherited, String changeId) {
        return ChangeEngine.apply(edits, current, inherited, changeId, "R0", "R1", changeId + "-v1", "Bob");
    }

    @Test void divergentValuesConflictAndMaterializeLocalWithCurrentBefore() {
        List<AuthoredEdit> delta = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15")));
        ChangeEngine.MergeResult result = apply(delta, analysis(flow("12")), List.of(), "B1");
        assertEquals(1, result.conflicts().size());
        Conflict conflict = result.conflicts().getFirst();
        assertEquals("B1", conflict.originChangeId());
        assertEquals(real("10"), conflict.base().value());
        assertEquals(real("12"), conflict.incoming().value());
        assertEquals(real("15"), conflict.local().value());
        assertEquals("R1", conflict.incoming().revisionId());
        assertEquals("B1-v1", conflict.local().revisionId());
        assertEquals(real("12"), result.patches().getFirst().before());
        assertEquals(real("15"), result.patches().getFirst().after());
        assertEquals(real("10"), delta.getFirst().before());
        assertEquals(result, apply(delta, analysis(flow("12")), List.of(), "B1"));
    }

    @Test void equalNumbersConvergeAndIndependentPropertiesSurvive() {
        List<AuthoredEdit> delta = ChangeEngine.diff(analysis(flow("10")), analysis(flow("12.0")));
        ChangeEngine.MergeResult equal = apply(delta, analysis(flow("1.2e1")), List.of(), "B1");
        assertTrue(equal.patches().isEmpty());
        assertTrue(equal.conflicts().isEmpty());
        ChangeEngine.MergeResult independent = apply(ChangeEngine.diff(analysis(flow("10")),
                analysis(flow("10"), alert())), analysis(flow("12")), List.of(), "B2");
        assertEquals(List.of(EditKind.ADD_ATTRIBUTE), independent.patches().stream().map(AuthoredEdit::kind).toList());
        assertTrue(independent.conflicts().isEmpty());
    }

    @Test void additionIdentityReconciliationAndIncompatibleValues() {
        Attribute incoming = attribute("acceptedFlow", "flowRate", real("12"));
        List<AuthoredEdit> delta = ChangeEngine.diff(analysis(), analysis(flow("12.0")));
        ChangeEngine.MergeResult equal = apply(delta, analysis(incoming), List.of(), "B1");
        assertEquals(Map.of("flow", "acceptedFlow"), equal.identityRemap());
        assertTrue(equal.patches().isEmpty());
        assertTrue(equal.conflicts().isEmpty());
        ChangeEngine.MergeResult incompatible = apply(ChangeEngine.diff(analysis(), analysis(flow("15"))),
                analysis(incoming), List.of(), "B1");
        assertEquals(ConflictKind.ADDITION, incompatible.conflicts().getFirst().kind());
        assertEquals("acceptedFlow", incompatible.conflicts().getFirst().target().attributeId());
        assertNull(incompatible.conflicts().getFirst().base().value());
        assertEquals("acceptedFlow", incompatible.patches().getFirst().target().attributeId());
        assertEquals(EditKind.SET_ATTRIBUTE, incompatible.patches().getFirst().kind());
        assertEquals(real("12"), incompatible.patches().getFirst().before());
    }

    @Test void inheritedConflictsAreNotDuplicatedOrResolvedByProvisionalValues() {
        Conflict origin = apply(ChangeEngine.diff(analysis(flow("10")), analysis(flow("15"))),
                analysis(flow("12")), List.of(), "B1").conflicts().getFirst();
        ChangeEngine.MergeResult unrelated = apply(ChangeEngine.diff(analysis(flow("15")),
                analysis(flow("15"), alert())), analysis(flow("15")), List.of(origin), "B2");
        assertEquals(List.of(origin), unrelated.conflicts());
        assertSame(origin, unrelated.conflicts().getFirst());
        ChangeEngine.MergeResult sameOrigin = apply(ChangeEngine.diff(analysis(flow("10")), analysis(flow("15"))),
                analysis(flow("15")), List.of(origin), "B1");
        assertEquals(List.of(origin), sameOrigin.conflicts());
        ChangeEngine.MergeResult descendant = apply(ChangeEngine.diff(analysis(flow("15")), analysis(flow("16"))),
                analysis(flow("15")), List.of(origin), "B2");
        assertEquals("B2", descendant.conflicts().getFirst().originChangeId());
        assertNotEquals(origin.id(), descendant.conflicts().getFirst().id());
    }

    @Test void originResolutionReplaysAlertOverFreshEquivalentAnalysisAndKeepsLaterDeltas() {
        Analysis accepted = analysis(flow("12"));
        List<AuthoredEdit> b1 = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15")));
        List<AuthoredEdit> b2 = ChangeEngine.diff(analysis(flow("15")), analysis(flow("15"), alert()));
        Conflict conflict = apply(b1, accepted, List.of(), "B1").conflicts().getFirst();
        List<AuthoredEdit> resolved = ChangeEngine.resolveEdits(b1, conflict, real("14"));
        assertEquals(real("12"), resolved.getFirst().before());
        assertEquals(real("14"), resolved.getFirst().after());
        ChangeEngine.MergeResult origin = apply(resolved, accepted, List.of(), "B1");
        assertTrue(origin.conflicts().isEmpty());
        assertEquals(real("14"), origin.patches().getFirst().after());

        // Reanalysis creates new records and shifted offsets, not object-identity matches.
        Analysis freshlyAnalyzed = new Analysis("w", 5, Map.of("model.sysml", 7L), AnalysisState.VALID,
                List.of(new ModelElement("pump", "model.sysml", "Demo::Pump", "Pump", "part def",
                        new SourceRange(12, 60), 59,
                        List.of(new Attribute("flow", "pump", "flowRate", real("14.000"),
                                new SourceRange(30, 36), true, null)))), List.of());
        ChangeEngine.MergeResult replayB2 = apply(b2, freshlyAnalyzed, origin.conflicts(), "B2");
        assertTrue(replayB2.conflicts().isEmpty());
        assertEquals(alert().value(), replayB2.patches().getFirst().after());
        Analysis afterB2 = materialize(freshlyAnalyzed, replayB2.patches());
        assertEquals(real("14.000"), afterB2.elements().getFirst().attributes().stream()
                .filter(a -> a.id().equals("flow")).findFirst().orElseThrow().value());
        assertTrue(afterB2.elements().getFirst().attributes().contains(alert()));
        Attribute alertOff = attribute("alert", "alertEnabled", new PropertyValue("ScalarValues::Boolean", "false",
                new ScalarValue(ScalarKind.BOOLEAN, "false")));
        List<AuthoredEdit> b3 = ChangeEngine.diff(analysis(flow("15"), alert()), analysis(flow("15"), alertOff));
        ChangeEngine.MergeResult replayB3 = apply(b3, afterB2, List.of(), "B3");
        assertTrue(replayB3.conflicts().isEmpty());
        assertEquals(alertOff.value(), replayB3.patches().getFirst().after());
        assertEquals("alert", replayB3.patches().getFirst().target().attributeId());
        Analysis afterB3 = materialize(afterB2, replayB3.patches());
        assertEquals(List.of(real("14.000"), alertOff.value()), afterB3.elements().getFirst().attributes()
                .stream().map(Attribute::value).toList());
        assertEquals(analysis(flow("12")), accepted);
        assertEquals(real("10"), b1.getFirst().before());
        assertEquals(real("15"), b1.getFirst().after());
        assertEquals(real("15"), conflict.local().value());
    }

    @Test void laterIndependentFlowEditConflictsAfterAncestorResolutionAndTipResolutionLeavesAncestorUntouched() {
        List<AuthoredEdit> b1 = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15")));
        Conflict ancestor = apply(b1, analysis(flow("12")), List.of(), "B1").conflicts().getFirst();
        List<AuthoredEdit> b2 = ChangeEngine.diff(analysis(flow("15")), analysis(flow("16")));
        List<AuthoredEdit> resolvedB1 = ChangeEngine.resolveEdits(b1, ancestor, real("14"));
        assertTrue(apply(resolvedB1, analysis(flow("12")), List.of(), "B1").conflicts().isEmpty());
        ChangeEngine.MergeResult replay = apply(b2, analysis(flow("14")), List.of(), "B2");
        Conflict descendant = replay.conflicts().getFirst();
        assertEquals("B2", descendant.originChangeId());
        assertEquals(real("15"), descendant.base().value());
        assertEquals(real("14"), descendant.incoming().value());
        assertEquals(real("16"), descendant.local().value());
        assertEquals(real("16"), replay.patches().getFirst().after());
        List<AuthoredEdit> resolvedTip = ChangeEngine.resolveEdits(b2, descendant, real("17"));
        assertEquals(real("14"), resolvedTip.getFirst().before());
        assertEquals(real("17"), resolvedTip.getFirst().after());
        assertEquals(real("15"), b1.getFirst().after());
        assertEquals(real("12"), ancestor.incoming().value());
        assertEquals(real("15"), ancestor.local().value());
    }

    @Test void resolutionPreservesUnrelatedEditsAndHandlesAdditionIdentity() {
        List<AuthoredEdit> original = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15"), alert()));
        Conflict conflict = apply(original, analysis(flow("12")), List.of(), "B1").conflicts().getFirst();
        List<AuthoredEdit> resolved = ChangeEngine.resolveEdits(original, conflict, real("12.00"));
        assertSame(original.get(1), resolved.get(1));
        assertTrue(apply(resolved, analysis(flow("12")), List.of(), "B1").conflicts().isEmpty());
        assertEquals(1, apply(resolved, analysis(flow("12")), List.of(), "B1").patches().size());
        List<AuthoredEdit> addition = ChangeEngine.diff(analysis(), analysis(flow("15"), alert()));
        Analysis incoming = analysis(attribute("acceptedFlow", "flowRate", real("12")));
        ChangeEngine.MergeResult initial = apply(addition, incoming, List.of(), "B1");
        Conflict additionConflict = initial.conflicts().getFirst();
        // The service persists original authored deltas with canonical targets, not materialization patches.
        List<AuthoredEdit> canonicalAddition = addition.stream().map(e -> new AuthoredEdit(e.kind(), e.documentId(),
                new PropertyTarget(e.target().elementId(), initial.identityRemap().getOrDefault(
                        e.target().attributeId(), e.target().attributeId())), e.attributeName(), e.before(), e.after())).toList();
        List<AuthoredEdit> resolvedAddition = ChangeEngine.resolveEdits(canonicalAddition, additionConflict, real("14"));
        assertEquals(EditKind.SET_ATTRIBUTE, resolvedAddition.getFirst().kind());
        assertEquals(real("12"), resolvedAddition.getFirst().before());
        ChangeEngine.MergeResult replay = apply(resolvedAddition, incoming, List.of(), "B1");
        assertTrue(replay.conflicts().isEmpty());
        assertEquals("acceptedFlow", replay.patches().getFirst().target().attributeId());
        assertTrue(replay.identityRemap().isEmpty());
        assertSame(canonicalAddition.get(1), resolvedAddition.get(1));
    }

    @Test void rejectsUnsupportedCollisionsDuplicateTargetsAndStaleResolutions() {
        List<AuthoredEdit> addition = ChangeEngine.diff(analysis(), analysis(flow("15")));
        Attribute wrongType = attribute("other", "flowRate", new PropertyValue("ScalarValues::Integer", "12",
                new ScalarValue(ScalarKind.INTEGER, "12")));
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> apply(addition, analysis(wrongType), List.of(), "B1"));
        AuthoredEdit secondAddition = new AuthoredEdit(EditKind.ADD_ATTRIBUTE, "model.sysml",
                new PropertyTarget("pump", "other"), "flowRate", null, real("15"));
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> apply(List.of(addition.getFirst(), secondAddition), analysis(), List.of(), "B1"));
        List<AuthoredEdit> b1 = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15")));
        Conflict conflict = apply(b1, analysis(flow("12")), List.of(), "B1").conflicts().getFirst();
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.resolveEdits(
                ChangeEngine.diff(analysis(flow("10")), analysis(flow("16"))), conflict, real("14")));
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.resolveEdits(
                b1, conflict, alert().value()));
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.resolveEdits(
                ChangeEngine.diff(analysis(flow("9")), analysis(flow("15"))), conflict, real("14")));
        assertThrows(UnsupportedOperationException.class, () ->
                apply(addition, analysis(), List.of(), "B1").patches().clear());
    }

    @Test void canonicalAdditionConflictsRemainVisibleAndDescendantsCannotHideThem() {
        List<AuthoredEdit> addition = ChangeEngine.diff(analysis(), analysis(flow("15")));
        ChangeEngine.MergeResult origin = apply(addition,
                analysis(attribute("acceptedFlow", "flowRate", real("12"))), List.of(), "B1");
        Conflict inherited = origin.conflicts().getFirst();
        PropertyTarget canonical = new PropertyTarget("pump", "acceptedFlow");
        assertEquals(canonical, inherited.target());
        AuthoredEdit descendantEdit = new AuthoredEdit(EditKind.SET_ATTRIBUTE, "model.sysml", canonical,
                "flowRate", real("15"), real("16"));
        ChangeEngine.MergeResult descendant = apply(List.of(descendantEdit),
                analysis(attribute("acceptedFlow", "flowRate", real("15"))), List.of(inherited), "B2");
        assertEquals(1, descendant.conflicts().size());
        assertEquals("B2", descendant.conflicts().getFirst().originChangeId());
        assertEquals(canonical, descendant.conflicts().getFirst().target());
        assertEquals("B1", inherited.originChangeId());
        assertThrows(UnsupportedOperationException.class, () -> origin.identityRemap().clear());
        assertThrows(UnsupportedOperationException.class, () -> descendant.conflicts().clear());
    }

    @Test void scalarEqualityDoesNotLoseLargeIntegerOrStringInformation() {
        PropertyValue integer = new PropertyValue("ScalarValues::Integer", "+09007199254740993",
                new ScalarValue(ScalarKind.INTEGER, "+09007199254740993"));
        PropertyValue same = new PropertyValue("ScalarValues::Integer", "9007199254740993",
                new ScalarValue(ScalarKind.INTEGER, "9007199254740993"));
        PropertyValue other = new PropertyValue("ScalarValues::Integer", "9007199254740992",
                new ScalarValue(ScalarKind.INTEGER, "9007199254740992"));
        assertTrue(ChangeEngine.diff(analysis(attribute("count", "count", integer)),
                analysis(attribute("count", "count", same))).isEmpty());
        assertEquals(1, ChangeEngine.diff(analysis(attribute("count", "count", integer)),
                analysis(attribute("count", "count", other))).size());
        PropertyValue string = new PropertyValue("ScalarValues::String", "\"a\\nb\"",
                new ScalarValue(ScalarKind.STRING, "a\nb"));
        PropertyValue escaped = new PropertyValue("ScalarValues::String", "alternate authored spelling",
                new ScalarValue(ScalarKind.STRING, "a\nb"));
        assertTrue(ChangeEngine.diff(analysis(attribute("label", "label", string)),
                analysis(attribute("label", "label", escaped))).isEmpty());
        assertTrue(ChangeEngine.diff(analysis(alert()), analysis(alert())).isEmpty());
    }

    @Test void untouchedLocalValueKeepsIncomingAndDistinctAdditionsBothSurvive() {
        AuthoredEdit noOp = new AuthoredEdit(EditKind.SET_ATTRIBUTE, "model.sysml",
                new PropertyTarget("pump", "flow"), "flowRate", real("10"), real("10.00"));
        ChangeEngine.MergeResult incomingOnly = apply(List.of(noOp), analysis(flow("12")), List.of(), "B1");
        assertTrue(incomingOnly.patches().isEmpty());
        assertTrue(incomingOnly.conflicts().isEmpty());
        ChangeEngine.MergeResult distinct = apply(ChangeEngine.diff(analysis(), analysis(flow("15"))),
                analysis(alert()), List.of(), "B1");
        assertTrue(distinct.conflicts().isEmpty());
        assertEquals("flow", distinct.patches().getFirst().target().attributeId());
        assertEquals(EditKind.ADD_ATTRIBUTE, distinct.patches().getFirst().kind());
    }

    @Test void scalarReplayCannotDiscardAnUnresolvedSourceConflict() {
        List<AuthoredEdit> original = ChangeEngine.diff(analysis(flow("10")), analysis(flow("15")));
        Conflict scalar = apply(original, analysis(flow("12")), List.of(), "B1").conflicts().getFirst();
        Conflict source = new Conflict("source-conflict", "B0", scalar.target(), ConflictKind.SOURCE,
                scalar.base(), scalar.incoming(), scalar.local());
        assertEquals(List.of(source), apply(List.of(), analysis(flow("15")), List.of(source), "B2").conflicts());
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> apply(original, analysis(flow("15")), List.of(source), "B2"));
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.resolveEdits(original, source, real("14")));
    }

    @Test void rejectsDeletionRenameTypeChangeAndReadOnlyLoss() {
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.diff(analysis(flow("10")), analysis()));
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.diff(analysis(flow("10")), analysis(attribute("flow", "renamed", real("10")))));
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.diff(analysis(flow("10")), analysis(attribute("flow", "flowRate",
                        new PropertyValue("OtherReal", "10", new ScalarValue(ScalarKind.REAL, "10"))))));
        Attribute readonly = new Attribute("port", "pump", "port", null, null, false, "structural member");
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.diff(analysis(flow("10"), readonly), analysis(flow("12"))));
        Attribute changed = new Attribute("port", "pump", "port", null, null, false, "unsupported expression");
        assertThrows(ChangeEngine.UnsupportedEditException.class,
                () -> ChangeEngine.diff(analysis(readonly), analysis(changed)));
    }

    @Test void rejectsBlockStructureAndIncompleteOrStaleAnalyses() {
        Analysis before = analysis(flow("10"));
        ModelElement pump = before.elements().getFirst();
        Analysis renamed = new Analysis("w", 1, before.documentVersions(), AnalysisState.VALID,
                List.of(new ModelElement("pump", "model.sysml", "Demo::Renamed", "Renamed", pump.kind(),
                        RANGE, 1, pump.attributes())), List.of());
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.diff(before, renamed));
        Analysis incomplete = new Analysis("w", 1, before.documentVersions(), AnalysisState.PARTIAL,
                before.elements(), List.of());
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.diff(before, incomplete));
        Analysis stale = new Analysis("w", 0, before.documentVersions(), AnalysisState.VALID, before.elements(), List.of());
        assertThrows(ChangeEngine.UnsupportedEditException.class, () -> ChangeEngine.diff(before, stale));
    }
}
