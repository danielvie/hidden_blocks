package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ParserAdapterTest {
    @TempDir static Path directory;
    static ParserAdapter adapter;
    static String pump;

    @BeforeAll static void setup() throws Exception {
        adapter = new ParserAdapter(Path.of(System.getProperty("sysml.pilot.library")), directory);
        pump = Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures/pump.sysml"));
        long start = System.nanoTime();
        valid(analyze(pump).analysis());
        System.out.println("ADAPTER_COLD_ANALYSIS_MS=" + (System.nanoTime() - start) / 1_000_000);
    }
    @AfterAll static void close() { adapter.close(); }
    static ParserAdapter.Result analyze(String text) {
        return adapter.analyze("alice", 1, List.of(new SourceDocument("pump.sysml", 1, text)), Map.of());
    }
    static void valid(Analysis analysis) {
        assertEquals(AnalysisState.VALID, analysis.state(), analysis.diagnostics().toString());
    }
    static Attribute flow(Analysis analysis) {
        return analysis.elements().stream().flatMap(e -> e.attributes().stream()).filter(a -> a.name().equals("flowRate")).findFirst().orElseThrow();
    }
    @Test void mapsRealOfficialModel() {
        long start = System.nanoTime();
        var result = analyze(pump);
        valid(result.analysis());
        var owner = result.analysis().elements().getFirst();
        assertEquals("PumpModel::Pump", owner.qualifiedName());
        var flow = flow(result.analysis());
        assertEquals("10", flow.valueRange().slice(pump));
        assertEquals(new ScalarValue(ScalarKind.REAL, "10"), flow.value().scalar());
        assertTrue(flow.editable());
        assertEquals('}', pump.charAt(owner.insertionOffset()));
        System.out.println("ADAPTER_EXISTING_WORKSPACE_ANALYSIS_MS=" + (System.nanoTime() - start) / 1_000_000);
    }
    @Test void invalidSourceStaysAvailableAndDoesNotDeleteFiles() throws Exception {
        var sentinel = directory.resolve(UUID.nameUUIDFromBytes("alice".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).resolve("pump.sysml");
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "do not delete");
        var result = analyze(pump.replace("= 10;", "= ;"));
        assertEquals(AnalysisState.INVALID, result.analysis().state());
        assertTrue(result.analysis().diagnostics().stream().anyMatch(d -> d.category().equals("syntax")));
        assertEquals("do not delete", Files.readString(sentinel));
        valid(analyze(pump).analysis());
    }
    @Test void mapsTypesPrecisionReadOnlyUnicodeAndCrLf() throws Exception {
        String text = Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures/source-mapping.sysml"));
        assertTrue(text.contains("\r\n"));
        var result = analyze(text);
        valid(result.analysis());
        var owner = result.analysis().elements().stream().filter(e -> e.qualifiedName().equals("Mapping::Pump")).findFirst().orElseThrow();
        var attrs = new HashMap<String, Attribute>();
        owner.attributes().forEach(a -> attrs.put(a.name(), a));
        assertEquals("0.12345678901234567890123456789", attrs.get("precision").value().scalar().canonical());
        assertEquals(ScalarKind.INTEGER, attrs.get("count").value().scalar().kind());
        assertEquals(ScalarKind.BOOLEAN, attrs.get("enabled").value().scalar().kind());
        assertEquals("line\n\"quoted\"", attrs.get("label").value().scalar().canonical());
        for (var attr : owner.attributes()) assertEquals(attr.value().expression(), attr.valueRange().slice(text));
        for (String name : List.of("computed", "derivedValue")) {
            assertFalse(attrs.get(name).editable());
            assertNull(attrs.get(name).value().scalar());
            assertNotNull(attrs.get(name).readOnlyReason());
        }
        assertEquals("1 + 2", attrs.get("computed").value().expression());
        assertEquals(2, result.analysis().elements().stream().flatMap(e -> e.attributes().stream()).filter(a -> a.name().equals("flowRate") && !a.inherited()).count());
        var usage = result.analysis().elements().stream().filter(e -> e.kind().equals("PartUsage")).findFirst().orElseThrow();
        assertFalse(usage.attributes().isEmpty());
        for (var attribute : usage.attributes()) {
            assertTrue(attribute.inherited());
            assertFalse(attribute.editable());
            assertNull(attribute.valueRange(), "Inherited attributes must not fabricate authored edit ranges");
        }
    }
    @Test void multiDocumentLinksAreIsolatedAndRemovedInputsDoNotRemainIndexed() {
        var use = new SourceDocument("use.sysml", 1, "package Use { part machine : Definitions::Pump; }");
        var definition = new SourceDocument("defs.sysml", 1, "package Definitions { part def Pump; }");
        var alice = adapter.analyze("alice", 1, List.of(use, definition), Map.of());
        valid(alice.analysis());
        var bob = adapter.analyze("bob", 1, List.of(use), Map.of());
        assertEquals(AnalysisState.INVALID, bob.analysis().state());
        valid(adapter.analyze("bob", 2, List.of(use, definition), Map.of()).analysis());
        var removed = adapter.analyze("alice", 2, List.of(use), alice.identities());
        assertEquals(AnalysisState.INVALID, removed.analysis().state());
        assertTrue(removed.analysis().diagnostics().stream().anyMatch(d -> d.message().contains("Definitions")));
    }
    @Test void rejectsUnknownTypesMismatchesAndAmbiguousIdentity() {
        assertEquals(AnalysisState.INVALID, analyze(pump.replace(": Real", ": MissingType")).analysis().state());
        assertEquals(AnalysisState.INVALID, analyze(pump.replace("= 10", "= true")).analysis().state());
        var duplicate = analyze(pump.replace("attribute flowRate : Real = 10;", "attribute flowRate : Real = 10; attribute flowRate : Real = 11;"));
        assertEquals(AnalysisState.INVALID, duplicate.analysis().state());
        assertTrue(duplicate.analysis().diagnostics().stream().anyMatch(d -> d.category().equals("identity")));
        assertTrue(duplicate.analysis().elements().getFirst().attributes().isEmpty(), duplicate.analysis().toString());
    }
    @Test void signedAndExponentNumbersUseSourcePrecisionAndOversizedIntegersAreNotRounded() {
        for (String number : List.of("- 12.345678901234567890123", "+12", "1.234567890123456789e12")) {
            var result = analyze(pump.replace("= 10;", "= " + number + ";"));
            valid(result.analysis());
            assertEquals(new ScalarValue(ScalarKind.REAL, number.replace(" ", "")), flow(result.analysis()).value().scalar());
        }
        var large = analyze(pump.replace("= 10;", "= 999999999999999999999999999999;"));
        assertEquals(AnalysisState.INVALID, large.analysis().state(), "Official int converter overflow must not be accepted as a rounded scalar");
    }
    @Test void duplicateCrossDocumentNamesNeverReconcileByIndexOrder() {
        var one = new SourceDocument("one.sysml", 1, "package Shared { part def Pump; }");
        var two = new SourceDocument("two.sysml", 1, "package Shared { part def Pump; }");
        var result = adapter.analyze("alice", 1, List.of(one, two), Map.of());
        assertEquals(AnalysisState.INVALID, result.analysis().state());
        assertTrue(result.analysis().diagnostics().stream().anyMatch(d -> d.category().equals("identity")));
        assertTrue(result.analysis().elements().isEmpty());
    }
    @Test void externalResourceIoHasNoHandlersAndBothInputStreamOverloadsAreDenied() throws Exception {
        valid(analyze(pump).analysis());
        var field = ParserAdapter.class.getDeclaredField("workspaces");
        field.setAccessible(true);
        var workspaces = (Map<?, ?>) field.get(adapter);
        var pilot = (org.omg.sysml.interactive.SysMLInteractive) workspaces.get("alice");
        assertEquals(1, pilot.getInputResources().size());
        assertEquals(95, pilot.getResourceSet().getResources().size(), "Old input resources must be removed, not just unloaded");
        var converter = pilot.getResourceSet().getURIConverter();
        assertTrue(converter.getURIHandlers().isEmpty());
        for (String address : List.of("file:///not-a-model.sysml", "https://example.invalid/private.sysml")) {
            var uri = org.eclipse.emf.common.util.URI.createURI(address);
            assertThrows(java.io.IOException.class, () -> converter.createInputStream(uri));
            assertThrows(java.io.IOException.class, () -> converter.createInputStream(uri, Map.of()));
            assertThrows(RuntimeException.class, () -> converter.getURIHandler(uri));
        }
    }
    @Test void parenthesizedExpressionsHaveExactInnerRangesWithoutFalseTypeErrors() {
        for (String expression : List.of("(10)", "(1 + 2)")) {
            var result = analyze(pump.replace("= 10;", "= " + expression + ";"));
            valid(result.analysis());
            String mapped = expression.substring(1, expression.length() - 1);
            assertEquals(mapped, flow(result.analysis()).value().expression());
            assertEquals(mapped, flow(result.analysis()).valueRange().slice(pump.replace("= 10;", "= " + expression + ";")));
        }
    }
    @Test void unsafeDocumentPathsAndClosedAdaptersAreRejected() {
        for (String id : List.of("../pump.sysml", "/pump.sysml", "C:/pump.sysml", "https://host/pump.sysml", "dir/../pump.sysml"))
            assertThrows(IllegalArgumentException.class, () -> adapter.analyze("alice", 1, List.of(new SourceDocument(id, 1, pump)), Map.of()));
        var other = new ParserAdapter(Path.of(System.getProperty("sysml.pilot.library")), directory);
        other.close();
        assertThrows(IllegalStateException.class, () -> other.analyze("alice", 1, List.of(), Map.of()));
    }
    @Test void sourcePatchesRoundTripAndRejectStaleOrUnsafeEdits() {
        String text = "// music 𝄞\r\n" + pump.replace("\n", "\r\n");
        var document = new SourceDocument("pump.sysml", 4, text);
        var first = adapter.analyze("alice", 4, List.of(document), Map.of());
        valid(first.analysis());
        var flow = flow(first.analysis());
        var replaced = SourceEdits.replace(document, flow, "10", "12");
        assertEquals(5, replaced.version());
        assertEquals(text.substring(0, flow.valueRange().start()) + "12" + text.substring(flow.valueRange().end()), replaced.text());
        var next = adapter.analyze("alice", 5, List.of(replaced), first.identities());
        valid(next.analysis());
        assertEquals(flow.id(), flow(next.analysis()).id());
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.replace(replaced, flow, "10", "13"));
        var owner = next.analysis().elements().getFirst();
        var added = SourceEdits.addAttribute(replaced, owner, "alertEnabled", "Boolean", "true");
        assertEquals(6, added.version());
        assertEquals(replaced.text(), added.text().replace("        attribute alertEnabled : Boolean = true;\r\n", ""));
        assertTrue(added.text().contains("// Keep this comment"));
        var last = adapter.analyze("alice", 6, List.of(added), next.identities());
        valid(last.analysis());
        assertEquals(flow.id(), flow(last.analysis()).id());
        assertTrue(last.analysis().elements().getFirst().attributes().stream().anyMatch(a -> a.name().equals("alertEnabled") && a.value().scalar().canonical().equals("true")));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.addAttribute(replaced, owner, "x; part def Injected", "Boolean", "true"));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.addAttribute(replaced, owner, "x", "Real; part def Injected", "true"));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.addAttribute(replaced, owner, "flowRate", "Real", "1"));
        var readOnly = analyze(pump.replace("= 10", "= 1 + 2"));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.replace(new SourceDocument("pump.sysml", 1, pump), flow(readOnly.analysis()), "1 + 2", "4"));
    }
    @Test void stringReplacementKeepsEscapesAndUnrelatedCrLfText() throws Exception {
        var document = new SourceDocument("mapping.sysml", 1, Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures/source-mapping.sysml")));
        var before = adapter.analyze("alice", 1, List.of(document), Map.of());
        valid(before.analysis());
        var attribute = before.analysis().elements().stream().flatMap(e -> e.attributes().stream()).filter(a -> a.name().equals("label")).findFirst().orElseThrow();
        String replacement = "\"new\\n\\\"label\\\"; {}\"";
        var changed = SourceEdits.replace(document, attribute, attribute.value().expression(), replacement);
        var after = adapter.analyze("alice", 2, List.of(changed), before.identities());
        valid(after.analysis());
        var updated = after.analysis().elements().stream().flatMap(e -> e.attributes().stream()).filter(a -> a.id().equals(attribute.id())).findFirst().orElseThrow();
        assertEquals("new\n\"label\"; {}", updated.value().scalar().canonical());
        assertEquals(replacement, updated.valueRange().slice(changed.text()));
        assertEquals(document.text().substring(0, attribute.valueRange().start()) + replacement + document.text().substring(attribute.valueRange().end()), changed.text());
    }
    @Test void additionsHandleInlineBodiesAndStringsWithoutBroadSerialization() {
        var document = new SourceDocument("inline.sysml", 1, "part def Pump { /* keep } */ }");
        var first = adapter.analyze("alice", 1, List.of(document), Map.of());
        valid(first.analysis());
        var added = SourceEdits.addAttribute(document, first.analysis().elements().getFirst(), "label", "ScalarValues::String", "\"safe; { text }\"");
        assertEquals("part def Pump { /* keep } */  attribute label : ScalarValues::String = \"safe; { text }\"; }", added.text());
        var result = adapter.analyze("alice", 2, List.of(added), first.identities());
        valid(result.analysis());
        assertEquals("safe; { text }", result.analysis().elements().getFirst().attributes().getFirst().value().scalar().canonical());
        var before = new SourceDocument("pump.sysml", 1, pump);
        var pumpResult = analyze(pump);
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.replace(before, flow(pumpResult.analysis()), "10", "12; attribute injected : Boolean = true"));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.addAttribute(before, pumpResult.analysis().elements().getFirst(), "x", "Real", "12; attribute injected : Boolean = true"));
        var wrongType = SourceEdits.replace(before, flow(pumpResult.analysis()), "10", "false");
        assertEquals(AnalysisState.INVALID, analyze(wrongType.text()).analysis().state());
    }
    @Test void customAndUnresolvedTypesAreNotGuessedAsSupportedScalars() {
        var custom = analyze("package Custom { public import ScalarValues::*; attribute def Speed :> Real; part def Pump { attribute flowRate : Speed = 10; attribute unassigned : Real; } }");
        valid(custom.analysis());
        var attribute = flow(custom.analysis());
        assertEquals("Custom::Speed", attribute.value().typeName());
        assertFalse(attribute.editable());
        assertNull(attribute.value().scalar());
        assertEquals("10", attribute.value().expression());
        var unassigned = custom.analysis().elements().getFirst().attributes().stream().filter(a -> a.name().equals("unassigned")).findFirst().orElseThrow();
        assertFalse(unassigned.editable());
        assertNull(unassigned.valueRange());
        assertNull(unassigned.value().scalar());
    }
    @Test void sourceCannotSpoofLibraryScalarTypesOrPoisonLaterAnalyses() {
        var spoofed = analyze("package ScalarValues { attribute def Real; } package PumpModel { public import ScalarValues::*; part def Pump { attribute flowRate : Real = 10; } }");
        for (var element : spoofed.analysis().elements()) for (var attribute : element.attributes()) {
            assertFalse(attribute.editable());
            assertNull(attribute.value().scalar());
        }
        var restored = analyze(pump);
        valid(restored.analysis());
        assertEquals(new ScalarValue(ScalarKind.REAL, "10"), flow(restored.analysis()).value().scalar());
    }
    static SourceDocument connected() throws Exception {
        return new SourceDocument("connected-pump.sysml", 1,
            Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures/connected-pump.sysml")));
    }
    static ModelElement element(Analysis analysis, String name) {
        return analysis.elements().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }
    static Attribute attribute(ModelElement element, String name) {
        return element.attributes().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }
    @Test void waterTankHasThreePartsSixPortsAndAConnectedLoop() throws Exception {
        var document = new SourceDocument("water-tank.sysml", 1,
            Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures/water-tank.sysml")));
        var first = adapter.analyze("alice", 1, List.of(document), Map.of());
        var analysis = first.analysis();
        valid(analysis);
        assertTrue(analysis.diagnostics().isEmpty(), analysis.diagnostics().toString());
        assertEquals(7, analysis.elements().size());
        var system = element(analysis, "system");
        var parts = analysis.elements().stream().filter(e -> system.id().equals(e.containerId())).toList();
        assertEquals(Set.of("controller", "pump", "tank"), parts.stream().map(ModelElement::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, parts.stream().mapToInt(e -> e.ports().size()).sum());
        assertTrue(analysis.elements().stream().allMatch(e -> e.superTypeIds().isEmpty()));
        assertEquals(3, analysis.connections().size());
        for (var names : List.of(
                List.of("controller", "pumpCommandOut", "pump", "commandIn"),
                List.of("pump", "waterOut", "tank", "waterIn"),
                List.of("tank", "levelOut", "controller", "levelIn"))) {
            var source = element(analysis, names.get(0));
            var target = element(analysis, names.get(2));
            var output = source.ports().stream().filter(p -> p.name().equals(names.get(1))).findFirst().orElseThrow();
            var input = target.ports().stream().filter(p -> p.name().equals(names.get(3))).findFirst().orElseThrow();
            assertEquals(2, source.ports().size());
            assertEquals(output.typeName(), input.typeName());
            assertFalse(output.conjugated());
            assertTrue(input.conjugated());
            assertTrue(analysis.connections().stream().anyMatch(c -> c.sourceElementId().equals(source.id())
                && c.sourcePortId().equals(output.id()) && c.targetElementId().equals(target.id()) && c.targetPortId().equals(input.id())));
        }
        var pump = element(analysis, "pump");
        var flow = attribute(pump, "flowRate");
        assertTrue(flow.editable());
        assertFalse(flow.inherited());
        assertEquals("10", flow.valueRange().slice(document.text()));
        var edited = SourceEdits.replace(document, flow, "10", "12");
        var second = adapter.analyze("alice", 2, List.of(edited), first.identities());
        valid(second.analysis());
        var added = SourceEdits.addAttribute(edited, element(second.analysis(), "pump"), "alertEnabled", "Boolean", "true");
        var third = adapter.analyze("alice", 3, List.of(added), second.identities());
        valid(third.analysis());
        assertEquals("12", attribute(element(third.analysis(), "pump"), "flowRate").value().expression());
        assertTrue(attribute(element(third.analysis(), "pump"), "alertEnabled").editable());
        assertEquals(analysis.connections(), third.analysis().connections());
        for (var part : parts) assertEquals(part.ports(), element(third.analysis(), part.name()).ports());
    }
    @Test void projectsApprovedConnectedDesignFromLinkedFeatures() throws Exception {
        var document = connected();
        assertArrayEquals(Files.readAllBytes(Path.of(System.getProperty("sysml.root"), "docs/design/connected-pump-example.sysml")),
            Files.readAllBytes(Path.of(System.getProperty("sysml.root"), "fixtures/connected-pump.sysml")));
        var result = adapter.analyze("alice", 1, List.of(document), Map.of());
        var analysis = result.analysis();
        valid(analysis);
        assertTrue(analysis.diagnostics().isEmpty(), analysis.diagnostics().toString());
        assertEquals(6, analysis.elements().size());
        var base = element(analysis, "Pump");
        var definition = element(analysis, "VariableSpeedPump");
        var tankDefinition = element(analysis, "Tank");
        var system = element(analysis, "system");
        var pump = element(analysis, "pump");
        var tank = element(analysis, "tank");
        assertEquals(List.of(base.id()), definition.superTypeIds());
        assertTrue(base.superTypeIds().isEmpty(), "Implicit library bases must not become specialization links");
        assertNull(definition.typeName());
        assertNull(definition.definitionId());
        assertEquals("PumpSystem::VariableSpeedPump", pump.typeName());
        assertEquals(definition.id(), pump.definitionId());
        assertEquals(List.of(base.id()), pump.superTypeIds(), "Usage cue repeats its explicit type's direct user-defined bases");
        assertEquals(tankDefinition.id(), tank.definitionId());
        assertTrue(tank.superTypeIds().isEmpty());
        assertEquals(system.id(), pump.containerId());
        assertEquals(system.id(), tank.containerId());
        assertNull(system.containerId());
        assertNull(definition.containerId());
        assertEquals(Set.of("pump", "tank"), analysis.elements().stream().filter(e -> system.id().equals(e.containerId()))
            .map(ModelElement::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("flowRate", "speed"), pump.attributes().stream().map(Attribute::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, pump.attributes().size(), "Local redefinition replaces the inherited flowRate row");
        var flow = attribute(pump, "flowRate");
        var baseFlow = attribute(base, "flowRate");
        assertTrue(flow.editable());
        assertFalse(flow.inherited());
        assertEquals("10", flow.valueRange().slice(document.text()));
        assertEquals("ScalarValues::Real", flow.value().typeName());
        assertEquals(flow.id(), flow.definitionAttributeId());
        assertNotEquals(baseFlow.id(), flow.id());
        assertEquals(baseFlow.id(), baseFlow.definitionAttributeId());
        assertEquals("PumpSystem::Pump", baseFlow.declaredBy());
        var speed = attribute(pump, "speed");
        var sourceSpeed = attribute(definition, "speed");
        assertTrue(speed.inherited());
        assertFalse(speed.editable());
        assertNull(speed.valueRange());
        assertEquals("PumpSystem::VariableSpeedPump", speed.declaredBy());
        assertEquals(sourceSpeed.id(), speed.definitionAttributeId());
        assertNotEquals(sourceSpeed.id(), speed.id());
        assertEquals(pump.id(), speed.ownerId());
        assertEquals(new ScalarValue(ScalarKind.REAL, "1800"), speed.value().scalar());
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.replace(document, speed, "1800", "1900"));
        var inheritedFlow = attribute(definition, "flowRate");
        assertEquals(baseFlow.id(), inheritedFlow.definitionAttributeId());
        assertEquals("PumpSystem::Pump", inheritedFlow.declaredBy());
        assertEquals(1, pump.ports().size());
        assertEquals(1, tank.ports().size());
        assertTrue(system.ports().isEmpty());
        var outlet = pump.ports().getFirst();
        var inlet = tank.ports().getFirst();
        assertEquals("outlet", outlet.name());
        assertEquals("inlet", inlet.name());
        assertEquals("PumpSystem::FluidPort", outlet.typeName());
        assertEquals(outlet.typeName(), inlet.typeName());
        assertTrue(outlet.inherited());
        assertTrue(inlet.inherited());
        assertFalse(outlet.conjugated());
        assertTrue(inlet.conjugated());
        assertEquals("PumpSystem::Pump", outlet.declaredBy());
        assertEquals("PumpSystem::Tank", inlet.declaredBy());
        assertEquals(pump.id(), outlet.ownerId());
        assertNotEquals(base.ports().getFirst().id(), outlet.id());
        assertEquals(1, analysis.connections().size());
        var connection = analysis.connections().getFirst();
        assertNull(connection.name());
        assertEquals(pump.id(), connection.sourceElementId());
        assertEquals(outlet.id(), connection.sourcePortId());
        assertEquals(tank.id(), connection.targetElementId());
        assertEquals(inlet.id(), connection.targetPortId());
    }
    @Test void connectedLocalEditsPreserveDefaultsSpecializationPortsAndWires() throws Exception {
        var document = connected();
        var first = adapter.analyze("alice", 1, List.of(document), Map.of());
        valid(first.analysis());
        var owner = element(first.analysis(), "pump");
        var localFlow = attribute(owner, "flowRate");
        var replaced = SourceEdits.replace(document, localFlow, "10", "12");
        assertEquals(document.text().replace("attribute flowRate :>> Pump::flowRate = 10;", "attribute flowRate :>> Pump::flowRate = 12;"), replaced.text());
        assertTrue(replaced.text().contains("attribute flowRate : Real default = 10;"));
        assertTrue(replaced.text().contains("part def VariableSpeedPump :> Pump"));
        assertTrue(replaced.text().contains("connect pump.outlet to tank.inlet;"));
        var second = adapter.analyze("alice", 2, List.of(replaced), first.identities());
        valid(second.analysis());
        var updated = element(second.analysis(), "pump");
        assertEquals(localFlow.id(), attribute(updated, "flowRate").id());
        assertEquals("12", attribute(updated, "flowRate").value().scalar().canonical());
        assertEquals("10", attribute(element(second.analysis(), "Pump"), "flowRate").value().scalar().canonical());
        var added = SourceEdits.addAttribute(replaced, updated, "alertEnabled", "Boolean", "true");
        assertEquals(replaced.text(), added.text().replace("            attribute alertEnabled : Boolean = true;\n", ""));
        var third = adapter.analyze("alice", 3, List.of(added), second.identities());
        valid(third.analysis());
        var finalOwner = element(third.analysis(), "pump");
        assertTrue(attribute(finalOwner, "alertEnabled").editable());
        assertEquals("true", attribute(finalOwner, "alertEnabled").value().scalar().canonical());
        assertEquals(first.analysis().connections(), third.analysis().connections());
        for (var before : first.analysis().elements()) {
            var after = element(third.analysis(), before.name());
            assertEquals(before.id(), after.id());
            assertEquals(before.ports(), after.ports());
            assertEquals(before.definitionId(), after.definitionId());
            assertEquals(before.superTypeIds(), after.superTypeIds());
            for (var attribute : before.attributes()) assertEquals(attribute.id(), attribute(after, attribute.name()).id());
        }
    }
    @Test void definitionEditRefreshesInheritedValuesWithoutRetainingOldSemanticObjects() throws Exception {
        var document = connected();
        var first = adapter.analyze("alice", 1, List.of(document), Map.of());
        var sourceSpeed = attribute(element(first.analysis(), "VariableSpeedPump"), "speed");
        var inheritedSpeed = attribute(element(first.analysis(), "pump"), "speed");
        var changed = SourceEdits.replace(document, sourceSpeed, "1800", "1900.1234567890123456789");
        var next = adapter.analyze("alice", 2, List.of(changed), first.identities());
        valid(next.analysis());
        var updated = attribute(element(next.analysis(), "pump"), "speed");
        assertEquals(inheritedSpeed.id(), updated.id());
        assertEquals(sourceSpeed.id(), updated.definitionAttributeId());
        assertEquals("1900.1234567890123456789", updated.value().expression());
        assertEquals(new ScalarValue(ScalarKind.REAL, "1900.1234567890123456789"), updated.value().scalar());
        assertEquals("1800", inheritedSpeed.value().expression(), "Previous immutable analysis remains unchanged");
        assertFalse(updated.editable());
        assertNull(updated.valueRange());
        assertEquals(first.analysis().connections(), next.analysis().connections());
        assertEquals(element(first.analysis(), "pump").ports(), element(next.analysis(), "pump").ports());
    }
    @Test void siblingUsagesHaveDistinctVirtualFeatureIdsAndNoInventedConnections() throws Exception {
        var document = connected();
        String text = document.text().replace("part tank : Tank;", "part tank : Tank; part spare : VariableSpeedPump;")
            .replace("connect pump.outlet to tank.inlet;", "// connect pump.outlet to tank.inlet;\n        attribute note : String = \"connect spare.outlet to tank.inlet;\";");
        var result = adapter.analyze("alice", 1, List.of(new SourceDocument(document.documentId(), 1, text)), Map.of());
        valid(result.analysis());
        assertTrue(result.analysis().connections().isEmpty(), "Comments and strings cannot create semantic connections");
        var pump = element(result.analysis(), "pump");
        var spare = element(result.analysis(), "spare");
        assertNotEquals(pump.ports().getFirst().id(), spare.ports().getFirst().id());
        var speed = attribute(pump, "speed");
        var spareSpeed = attribute(spare, "speed");
        assertNotEquals(speed.id(), spareSpeed.id());
        assertEquals(speed.definitionAttributeId(), spareSpeed.definitionAttributeId());
        assertEquals("PumpSystem::Pump", attribute(spare, "flowRate").declaredBy());
        assertTrue(attribute(spare, "flowRate").inherited());
    }
    @Test void namedConnectionUsesResolvedAliasesAndInvalidEndpointsNeverInventPorts() throws Exception {
        var document = connected();
        String text = document.text().replace("connect pump.outlet to tank.inlet;",
            "alias machine for pump; connection supply connect machine.outlet to tank.inlet;");
        var first = adapter.analyze("alice", 1, List.of(new SourceDocument(document.documentId(), 1, text)), Map.of());
        valid(first.analysis());
        assertEquals(6, first.analysis().elements().size(), "Named connections are edges, not PartUsage blocks");
        var pump = element(first.analysis(), "pump");
        var wire = first.analysis().connections().getFirst();
        assertEquals("supply", wire.name());
        assertEquals(pump.id(), wire.sourceElementId());
        assertEquals(pump.ports().getFirst().id(), wire.sourcePortId());
        var next = adapter.analyze("alice", 2, List.of(new SourceDocument(document.documentId(), 2,
            text.replace("machine.outlet", "machine.missing"))), first.identities());
        assertEquals(AnalysisState.INVALID, next.analysis().state());
        assertTrue(next.analysis().diagnostics().stream().anyMatch(d -> d.message().contains("missing")));
        assertTrue(next.analysis().connections().isEmpty());
        assertEquals(pump.ports(), element(next.analysis(), "pump").ports());
    }
    @Test void projectionResolvesDefinitionsAfterUsagesAcrossDocuments() {
        var use = new SourceDocument("use.sysml", 1, "package Use { part machine : Definitions::Pump; }");
        var definition = new SourceDocument("defs.sysml", 1, "package Definitions { public import ScalarValues::*; port def Output; part def Pump { attribute speed : Real = 7; port outlet : Output; } }");
        var first = adapter.analyze("alice", 1, List.of(use, definition), Map.of());
        valid(first.analysis());
        var machine = element(first.analysis(), "machine");
        var source = element(first.analysis(), "Pump");
        assertEquals(source.id(), machine.definitionId());
        assertEquals(attribute(source, "speed").id(), attribute(machine, "speed").definitionAttributeId());
        assertEquals("7", attribute(machine, "speed").value().expression());
        assertEquals("Definitions::Output", machine.ports().getFirst().typeName());
        var next = adapter.analyze("alice", 2, List.of(definition, use), first.identities());
        valid(next.analysis());
        assertEquals(machine, element(next.analysis(), "machine"));
    }
    @Test void ambiguousSiblingDefinitionsNeverReplacePersistedIdentities() {
        var ids = Map.of("duplicate.sysml#Duplicate::Pump", "persisted-pump");
        var document = new SourceDocument("duplicate.sysml", 1, "package Duplicate { part def Pump; part def Pump; }");
        var result = adapter.analyze("alice", 1, List.of(document), ids);
        assertEquals(AnalysisState.INVALID, result.analysis().state());
        assertTrue(result.analysis().elements().isEmpty());
        assertEquals(ids, result.identities());
    }
    @Test void stableIdentitiesSurviveCommentsAndAdditions() {
        var first = analyze(pump);
        String text = pump.replace("attribute flowRate", "// extra comment\n        attribute alertEnabled : Boolean = true;\n        attribute flowRate");
        long start = System.nanoTime();
        var next = adapter.analyze("alice", 2, List.of(new SourceDocument("pump.sysml", 2, text)), first.identities());
        valid(next.analysis());
        assertEquals(flow(first.analysis()).id(), flow(next.analysis()).id());
        assertEquals(first.analysis().elements().getFirst().id(), next.analysis().elements().getFirst().id());
        System.out.println("ADAPTER_WARM_ANALYSIS_MS=" + (System.nanoTime() - start) / 1_000_000);
    }
}
