package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelContractsTest {
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @Test void exactScalarsNeverRoundThroughFloatingPoint() {
        assertEquals("9007199254740993", new ScalarValue(ScalarKind.INTEGER, "9007199254740993").canonical());
        assertEquals("0.123456789012345678901", new ScalarValue(ScalarKind.REAL, "0.1234567890123456789010").canonical());
        assertEquals(new ScalarValue(ScalarKind.REAL, "12.0"), new ScalarValue(ScalarKind.REAL, "1.2e1"));
        assertThrows(IllegalArgumentException.class, () -> new ScalarValue(ScalarKind.BOOLEAN, "yes"));
    }

    @Test void sourceRangesUseUtf16AndPreserveCrLf() {
        String text = "// \uD83D\uDE80\r\nattribute value = 10;";
        int offset = text.indexOf("10");
        assertEquals("10", new SourceRange(offset, offset + 2).slice(text));
        SourceDocument document = new SourceDocument("model.sysml", 4, text);
        assertEquals(document, gson.fromJson(gson.toJson(document), SourceDocument.class));
        assertThrows(IllegalArgumentException.class, () -> new SourceRange(4, 3));
        assertThrows(IllegalArgumentException.class, () -> new SourceRange(0, 99).slice(text));
    }

    @Test void analysisCannotHideErrorsOrRetainMutableLists() {
        Diagnostic error = new Diagnostic(Severity.ERROR, "syntax", "Unexpected token", "model.sysml", null);
        assertThrows(IllegalArgumentException.class, () ->
            new Analysis("alice", 1, Map.of(), AnalysisState.VALID, List.of(), List.of(error)));
        Analysis invalid = new Analysis("alice", 1, Map.of("model.sysml", 1L), AnalysisState.INVALID, List.of(), List.of(error));
        assertThrows(UnsupportedOperationException.class, () -> invalid.diagnostics().clear());
    }

    @Test void readOnlyExpressionsRetainAuthoredTextWithoutInventingScalars() throws Exception {
        var fixture = JsonParser.parseString(Files.readString(Path.of("../golden/model-cases.json"))).getAsJsonObject();
        var json = fixture.getAsJsonObject("readOnlyExpression");
        Attribute attribute = gson.fromJson(json, Attribute.class);
        assertEquals(json, JsonParser.parseString(gson.toJson(attribute)));
        assertNull(attribute.value().scalar());
        assertNull(attribute.value().typeName());
        assertEquals("unknownFunction(flowRate)", attribute.value().expression());
        assertThrows(IllegalArgumentException.class, () -> new Attribute(attribute.id(), attribute.ownerId(),
            attribute.name(), attribute.value(), attribute.valueRange(), true, null));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredEdit(EditKind.ADD_ATTRIBUTE,
            "pump.sysml", new PropertyTarget("pump-1", "derived-1"), "derivedFlow", null, attribute.value()));
    }

    @Test void legacyStoredAnalysisAndElementsKeepTheirMeaning() {
        var element = gson.fromJson("{\"id\":\"p\",\"documentId\":\"model.sysml\",\"qualifiedName\":\"M::Pump\",\"name\":\"Pump\",\"kind\":\"PartDefinition\",\"declarationRange\":null,\"insertionOffset\":null,\"attributes\":[]}", ModelElement.class);
        assertTrue(element.ports().isEmpty()); assertTrue(element.superTypeIds().isEmpty()); assertNull(element.typeName());
        var analysis = gson.fromJson("{\"workspaceId\":\"alice\",\"workspaceRevision\":0,\"documentVersions\":{},\"state\":\"VALID\",\"elements\":[],\"diagnostics\":[]}", Analysis.class);
        assertTrue(analysis.connections().isEmpty());
    }

    @Test void schemaFieldsMatchEveryJavaRecord() throws Exception {
        var schema = JsonParser.parseString(Files.readString(Path.of("../model.schema.json"))).getAsJsonObject().getAsJsonObject("$defs");
        for (Class<?> type : ModelContracts.class.getDeclaredClasses()) {
            if (!type.isRecord()) continue;
            var definition = schema.getAsJsonObject(type.getSimpleName());
            assertNotNull(definition, type.getSimpleName());
            var fields = java.util.Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
            assertEquals(fields, definition.getAsJsonObject("properties").keySet());
            assertEquals(fields.size(), definition.getAsJsonArray("required").size());
        }
        assertThrows(IllegalArgumentException.class,
            () -> new SourceDocument("model.sysml", 9_007_199_254_740_992L, ""));
    }

    @Test void sharedGoldenCasesRoundTripWithoutInventingEdits() throws Exception {
        var fixture = JsonParser.parseString(Files.readString(Path.of("../golden/model-cases.json"))).getAsJsonObject();
        for (var item : fixture.getAsJsonArray("scalars")) {
            ScalarValue value = gson.fromJson(item, ScalarValue.class);
            assertEquals(item, JsonParser.parseString(gson.toJson(value)));
        }
        var inheritedJson = fixture.getAsJsonObject("inheritedChange");
        ChangeRevision inherited = gson.fromJson(inheritedJson, ChangeRevision.class);
        assertEquals(inheritedJson, JsonParser.parseString(gson.toJson(inherited)));
        assertEquals(List.of("conflict-flow"), inherited.inheritedConflictIds());
        assertEquals(1, inherited.edits().size());
        assertEquals("alertEnabled", inherited.edits().getFirst().attributeName());
        assertTrue(inherited.conflicts().isEmpty());
        Conflict conflict = gson.fromJson(fixture.getAsJsonObject("conflict"), Conflict.class);
        assertEquals("B1", conflict.originChangeId());
        assertEquals("12", conflict.incoming().value().scalar().canonical());
        assertEquals("15", conflict.local().value().scalar().canonical());
        assertEquals("10", conflict.base().value().scalar().canonical());
    }
}
