package app;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application-owned records. Source offsets are UTF-16 code units, end-exclusive. */
public final class ModelContracts {
    private ModelContracts() {}
    public static final int SCHEMA_VERSION = 1;

    /** Semantic scalar kind follows the resolved supported type, not the parser node class. */
    public enum ScalarKind { BOOLEAN, STRING, INTEGER, REAL }
    public enum AnalysisState { VALID, INVALID, PARTIAL }
    public enum Severity { ERROR, WARNING, INFO }
    public enum EditKind { SET_ATTRIBUTE, ADD_ATTRIBUTE }
    public enum ConflictKind { VALUE, ADDITION, SOURCE }

    public record SourceRange(int start, int end) {
        public SourceRange {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source range");
        }
        public String slice(String text) {
            if (end > text.length()) throw new IllegalArgumentException("Range exceeds document");
            return text.substring(start, end);
        }
    }

    public record SourceDocument(String documentId, long version, String text) {
        public SourceDocument {
            required(documentId); Objects.requireNonNull(text);
            safeVersion(version);
        }
    }

    public record ScalarValue(ScalarKind kind, String canonical) {
        public ScalarValue {
            Objects.requireNonNull(kind); Objects.requireNonNull(canonical);
            switch (kind) {
                case BOOLEAN -> {
                    if (!canonical.equals("true") && !canonical.equals("false"))
                        throw new IllegalArgumentException("Invalid Boolean");
                }
                case INTEGER -> canonical = new BigInteger(canonical).toString();
                case REAL -> canonical = new BigDecimal(canonical).stripTrailingZeros().toPlainString();
                case STRING -> { /* Decoded string; authored escaping stays in expression. */ }
            }
        }
    }

    public record PropertyValue(String typeName, String expression, ScalarValue scalar) {
        public PropertyValue {
            if (typeName != null) required(typeName);
            Objects.requireNonNull(expression);
            // Read-only expressions retain authored text even without a resolved type or scalar.
        }
    }

    public record Attribute(String id, String ownerId, String name, PropertyValue value,
                            SourceRange valueRange, boolean editable, String readOnlyReason,
                            String declaredBy, String definitionAttributeId, boolean inherited) {
        public Attribute(String id, String ownerId, String name, PropertyValue value,
                         SourceRange valueRange, boolean editable, String readOnlyReason) {
            this(id, ownerId, name, value, valueRange, editable, readOnlyReason, null, null, false);
        }
        public Attribute {
            required(id); required(ownerId); required(name);
            if (editable && (value == null || value.typeName() == null || value.scalar() == null || valueRange == null))
                throw new IllegalArgumentException("Editable attributes need authored values and ranges");
            if (inherited && editable) throw new IllegalArgumentException("Inherited rows are inspected through their definition");
        }
    }

    public record Port(String id, String ownerId, String name, String typeName,
                       boolean conjugated, boolean inherited, String declaredBy) {
        public Port { required(id); required(ownerId); required(name); }
    }

    public record Connection(String id, String name, String sourceElementId, String sourcePortId,
                             String targetElementId, String targetPortId) {
        public Connection {
            required(id); required(sourceElementId); required(sourcePortId);
            required(targetElementId); required(targetPortId);
        }
    }

    public record ModelElement(String id, String documentId, String qualifiedName, String name,
                               String kind, SourceRange declarationRange, Integer insertionOffset,
                               List<Attribute> attributes, String typeName, String definitionId,
                               List<String> superTypeIds, List<Port> ports, String containerId) {
        public ModelElement(String id, String documentId, String qualifiedName, String name,
                            String kind, SourceRange declarationRange, Integer insertionOffset,
                            List<Attribute> attributes) {
            this(id, documentId, qualifiedName, name, kind, declarationRange, insertionOffset,
                 attributes, null, null, List.of(), List.of(), null);
        }
        public ModelElement {
            required(id); required(documentId); required(qualifiedName); required(name); required(kind);
            attributes = List.copyOf(attributes);
            superTypeIds = superTypeIds == null ? List.of() : List.copyOf(superTypeIds);
            ports = ports == null ? List.of() : List.copyOf(ports);
        }
    }

    public record Diagnostic(Severity severity, String category, String message,
                             String documentId, SourceRange range) {
        public Diagnostic { Objects.requireNonNull(severity); required(category); required(message); }
    }

    public record Analysis(String workspaceId, long workspaceRevision, Map<String, Long> documentVersions,
                           AnalysisState state, List<ModelElement> elements, List<Diagnostic> diagnostics,
                           List<Connection> connections) {
        public Analysis(String workspaceId, long workspaceRevision, Map<String, Long> documentVersions,
                        AnalysisState state, List<ModelElement> elements, List<Diagnostic> diagnostics) {
            this(workspaceId, workspaceRevision, documentVersions, state, elements, diagnostics, List.of());
        }
        public Analysis {
            required(workspaceId); Objects.requireNonNull(state);
            safeVersion(workspaceRevision);
            documentVersions.values().forEach(ModelContracts::safeVersion);
            documentVersions = Map.copyOf(documentVersions);
            elements = List.copyOf(elements); diagnostics = List.copyOf(diagnostics);
            connections = connections == null ? List.of() : List.copyOf(connections);
            if (state == AnalysisState.VALID && diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR))
                throw new IllegalArgumentException("Valid analysis cannot contain errors");
        }
    }

    public record PropertyTarget(String elementId, String attributeId) {
        public PropertyTarget { required(elementId); required(attributeId); }
    }
    public record ConflictAlternative(String revisionId, String actor, PropertyValue value) {
        public ConflictAlternative { required(revisionId); required(actor); }
    }
    public record Conflict(String id, String originChangeId, PropertyTarget target, ConflictKind kind,
                           ConflictAlternative base, ConflictAlternative incoming, ConflictAlternative local) {
        public Conflict {
            required(id); required(originChangeId); Objects.requireNonNull(target); Objects.requireNonNull(kind);
            Objects.requireNonNull(base); Objects.requireNonNull(incoming); Objects.requireNonNull(local);
        }
    }
    public record AuthoredEdit(EditKind kind, String documentId, PropertyTarget target, String attributeName,
                               PropertyValue before, PropertyValue after) {
        public AuthoredEdit {
            Objects.requireNonNull(kind); required(documentId); Objects.requireNonNull(target);
            required(attributeName); supportedValue(after);
            if (kind == EditKind.SET_ATTRIBUTE && before != null) supportedValue(before);
            if (kind == EditKind.SET_ATTRIBUTE && before == null)
                throw new IllegalArgumentException("Replacement requires a base value");
            if (kind == EditKind.ADD_ATTRIBUTE && before != null)
                throw new IllegalArgumentException("Addition must have an absent base");
        }
    }
    public record SourcePatch(String documentId, long expectedDocumentVersion, SourceRange range,
                              String expectedText, String replacementText) {
        public SourcePatch {
            required(documentId); Objects.requireNonNull(range); safeVersion(expectedDocumentVersion);
            Objects.requireNonNull(expectedText); Objects.requireNonNull(replacementText);
        }
    }
    public record Resolution(String conflictId, String originRevisionId, String actor, PropertyValue value) {
        public Resolution { required(conflictId); required(originRevisionId); required(actor); supportedValue(value); }
    }
    public record ChangeRevision(String changeId, String revisionId, String parentRevisionId,
                                 String actor, String description, List<AuthoredEdit> edits,
                                 List<SourcePatch> sourcePatches, List<Conflict> conflicts,
                                 List<String> inheritedConflictIds, List<Resolution> resolutions) {
        public ChangeRevision {
            required(changeId); required(revisionId); required(parentRevisionId); required(actor);
            Objects.requireNonNull(description);
            edits = List.copyOf(edits); sourcePatches = List.copyOf(sourcePatches);
            conflicts = List.copyOf(conflicts); inheritedConflictIds = List.copyOf(inheritedConflictIds);
            resolutions = List.copyOf(resolutions);
        }
    }
    public record WorkspaceSnapshot(String workspaceId, String actor, long revision, String acceptedBaseRevision,
                                    List<SourceDocument> documents, Map<String, String> identities,
                                    String draftTipRevision, Analysis analysis) {
        public WorkspaceSnapshot {
            required(workspaceId); required(actor); required(acceptedBaseRevision);
            safeVersion(revision);
            documents = List.copyOf(documents); identities = Map.copyOf(identities);
        }
    }
    private static void supportedValue(PropertyValue value) {
        if (value == null || value.typeName() == null || value.scalar() == null)
            throw new IllegalArgumentException("Authored edits require a supported typed scalar");
    }
    private static void safeVersion(long value) {
        if (value < 0 || value > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("Version exceeds the JSON safe integer range");
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required nonempty string");
    }
}
