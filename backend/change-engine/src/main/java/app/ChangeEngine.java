package app;

import static app.ModelContracts.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure semantic proposals. The caller validates and materializes source separately. */
public final class ChangeEngine {
    private ChangeEngine() {}

    /** Unsupported structure or ambiguous identity must never become an empty successful delta. */
    public static final class UnsupportedEditException extends IllegalArgumentException {
        public UnsupportedEditException(String message) { super(message); }
    }

    public record MergeResult(List<AuthoredEdit> patches, List<Conflict> conflicts,
                              Map<String, String> identityRemap) {
        public MergeResult {
            patches = List.copyOf(patches);
            conflicts = List.copyOf(conflicts);
            identityRemap = Map.copyOf(identityRemap);
        }
    }

    public static List<AuthoredEdit> diff(Analysis before, Analysis after) {
        Map<String, ModelElement> oldElements = index(before);
        Map<String, ModelElement> newElements = index(after);
        require(before.workspaceId().equals(after.workspaceId()), "Different workspaces");
        require(after.workspaceRevision() >= before.workspaceRevision(), "Stale workspace analysis");
        require(before.documentVersions().keySet().equals(after.documentVersions().keySet()),
                "Document creation or deletion is unsupported");
        before.documentVersions().forEach((id, version) ->
                require(after.documentVersions().get(id) >= version, "Stale document analysis: " + id));
        require(oldElements.keySet().equals(newElements.keySet()), "Block creation, deletion or identity change");
        require(before.connections().equals(after.connections()), "Connection changes are unsupported");
        List<AuthoredEdit> edits = new ArrayList<>();
        List<Attribute> inheritedEffects = new ArrayList<>();
        for (ModelElement oldElement : before.elements()) {
            ModelElement element = newElements.get(oldElement.id());
            require(oldElement.documentId().equals(element.documentId())
                    && oldElement.name().equals(element.name())
                    && oldElement.qualifiedName().equals(element.qualifiedName())
                    && oldElement.kind().equals(element.kind())
                    && Objects.equals(oldElement.typeName(), element.typeName())
                    && Objects.equals(oldElement.definitionId(), element.definitionId())
                    && Objects.equals(oldElement.containerId(), element.containerId())
                    && oldElement.superTypeIds().equals(element.superTypeIds())
                    && oldElement.ports().equals(element.ports())
                    && (oldElement.declarationRange() == null) == (element.declarationRange() == null)
                    && (oldElement.insertionOffset() == null) == (element.insertionOffset() == null),
                    "Block structure changed: " + element.id());
            Map<String, Attribute> oldAttributes = attributes(oldElement);
            Map<String, Attribute> newAttributes = attributes(element);
            for (Attribute oldAttribute : oldElement.attributes()) {
                Attribute attribute = newAttributes.get(oldAttribute.id());
                require(attribute != null, "Deleted attribute or changed identity: " + oldAttribute.id());
                require(oldAttribute.name().equals(attribute.name()), "Attribute renamed: " + attribute.id());
                require(oldAttribute.editable() == attribute.editable()
                        && Objects.equals(oldAttribute.readOnlyReason(), attribute.readOnlyReason()),
                        "Attribute edit support changed: " + attribute.id());
                require(oldAttribute.inherited() == attribute.inherited(), "Attribute ownership changed: " + attribute.id());
                if (attribute.inherited()) {
                    require(Objects.equals(oldAttribute.definitionAttributeId(), attribute.definitionAttributeId())
                            && Objects.equals(oldAttribute.declaredBy(), attribute.declaredBy())
                            && Objects.equals(oldAttribute.value() == null ? null : oldAttribute.value().typeName(),
                                              attribute.value() == null ? null : attribute.value().typeName()),
                            "Inherited attribute definition changed: " + attribute.id());
                    boolean unchanged = oldAttribute.value() != null && attribute.value() != null
                            && oldAttribute.value().scalar() != null && attribute.value().scalar() != null
                            ? sameValue(oldAttribute.value(), attribute.value())
                            : Objects.equals(oldAttribute.value(), attribute.value());
                    if (!unchanged) inheritedEffects.add(attribute);
                } else if (!attribute.editable()) {
                    require(Objects.equals(oldAttribute.value(), attribute.value())
                            && (oldAttribute.valueRange() == null) == (attribute.valueRange() == null),
                            "Read-only attribute changed: " + attribute.id());
                } else {
                    require(sameType(oldAttribute.value(), attribute.value()),
                            "Attribute type changed: " + attribute.id());
                    if (!sameValue(oldAttribute.value(), attribute.value()))
                        edits.add(edit(EditKind.SET_ATTRIBUTE, element, attribute, oldAttribute.value(), attribute.value()));
                }
            }
            for (Attribute attribute : element.attributes()) {
                if (!oldAttributes.containsKey(attribute.id())) {
                    if (attribute.inherited()) { inheritedEffects.add(attribute); continue; }
                    require(attribute.editable() && element.insertionOffset() != null,
                            "Unsupported attribute addition: " + attribute.id());
                    edits.add(edit(EditKind.ADD_ATTRIBUTE, element, attribute, null, attribute.value()));
                }
            }
        }
        // Effective inherited rows are projections, not independently authored assignments.
        for (Attribute effect : inheritedEffects) {
            require(effect.definitionAttributeId() != null && edits.stream().anyMatch(edit ->
                    edit.target().attributeId().equals(effect.definitionAttributeId())
                    && sameValue(edit.after(), effect.value())),
                    "An inherited change has no supported owning-definition edit: " + effect.id());
        }
        return List.copyOf(edits);
    }

    /** Patches materialize local provisional values; they never replace stored authored deltas. */
    public static MergeResult apply(List<AuthoredEdit> edits, Analysis current, List<Conflict> inherited,
                                    String changeId, String baseRevision, String incomingRevision,
                                    String localRevision, String actor) {
        Map<String, ModelElement> elements = index(current);
        required(changeId); required(baseRevision); required(incomingRevision); required(localRevision); required(actor);
        Map<String, Conflict> conflicts = new LinkedHashMap<>();
        for (Conflict conflict : inherited) {
            Conflict prior = conflicts.putIfAbsent(conflict.id(), conflict);
            require(prior == null || prior.equals(conflict), "Conflicting inherited conflict identity");
        }
        List<AuthoredEdit> patches = new ArrayList<>();
        Map<String, String> remap = new LinkedHashMap<>();
        List<PropertyTarget> touched = new ArrayList<>();
        List<List<String>> touchedNames = new ArrayList<>();
        for (AuthoredEdit authored : edits) {
            ModelElement owner = elements.get(authored.target().elementId());
            require(owner != null, "Missing target block: " + authored.target().elementId());
            require(owner.documentId().equals(authored.documentId()), "Target document changed");
            for (ModelElement element : current.elements()) {
                if (!element.id().equals(owner.id()))
                    require(!attributes(element).containsKey(authored.target().attributeId()), "Attribute owner changed");
            }
            Map<String, Attribute> members = attributes(owner);
            Attribute attribute = members.get(authored.target().attributeId());
            if (attribute == null) {
                attribute = owner.attributes().stream()
                        .filter(a -> a.name().equals(authored.attributeName())).findFirst().orElse(null);
                if (attribute != null) remap.put(authored.target().attributeId(), attribute.id());
            }
            if (attribute == null) {
                require(authored.kind() == EditKind.ADD_ATTRIBUTE, "Deleted target attribute");
                require(owner.insertionOffset() != null, "Block has no authored insertion point");
                for (ModelElement element : current.elements())
                    require(!attributes(element).containsKey(authored.target().attributeId()), "Attribute identity reused");
            } else {
                require(attribute.name().equals(authored.attributeName()), "Target attribute renamed");
                require(attribute.editable(), "Target attribute is read-only");
                require(sameType(attribute.value(), authored.after()), "Unsupported target type change");
            }
            if (authored.kind() == EditKind.SET_ATTRIBUTE)
                require(sameType(authored.before(), authored.after()), "Unsupported authored type change");
            PropertyTarget target = new PropertyTarget(owner.id(),
                    attribute == null ? authored.target().attributeId() : attribute.id());
            List<String> namedTarget = List.of(owner.id(), authored.attributeName());
            require(!touched.contains(target) && !touchedNames.contains(namedTarget),
                    "Multiple edits address the same logical attribute");
            touched.add(target);
            touchedNames.add(namedTarget);
            PropertyValue incoming = attribute == null ? null : attribute.value();
            List<Conflict> hidden = conflicts.values().stream().filter(c ->
                    c.target().equals(target) || c.target().equals(authored.target())).toList();
            require(hidden.stream().noneMatch(c -> c.kind() == ConflictKind.SOURCE),
                    "A scalar edit cannot supersede an unresolved source conflict");
            boolean sameOrigin = hidden.stream().anyMatch(c -> c.originChangeId().equals(changeId));
            boolean conflict = !hidden.isEmpty() || (!sameValue(incoming, authored.before())
                    && !sameValue(incoming, authored.after()) && !sameValue(authored.before(), authored.after()));
            if (conflict && !sameOrigin) {
                hidden.forEach(c -> conflicts.remove(c.id()));
                Conflict created = new Conflict(conflictId(changeId, target), changeId, target,
                        authored.kind() == EditKind.ADD_ATTRIBUTE ? ConflictKind.ADDITION : ConflictKind.VALUE,
                        new ConflictAlternative(baseRevision, actor, authored.before()),
                        new ConflictAlternative(incomingRevision, actor, incoming),
                        new ConflictAlternative(localRevision, actor, authored.after()));
                conflicts.put(created.id(), created);
            }
            boolean noLocalChange = sameValue(authored.before(), authored.after()) && hidden.isEmpty();
            if (!noLocalChange && !sameValue(incoming, authored.after()))
                patches.add(new AuthoredEdit(attribute == null ? EditKind.ADD_ATTRIBUTE : EditKind.SET_ATTRIBUTE,
                        owner.documentId(), target, authored.attributeName(), incoming, authored.after()));
        }
        return new MergeResult(patches, new ArrayList<>(conflicts.values()), remap);
    }

    /** Re-author only the conflict's origin edit against its incoming alternative. */
    public static List<AuthoredEdit> resolveEdits(List<AuthoredEdit> original, Conflict conflict,
                                                 PropertyValue resolution) {
        Objects.requireNonNull(conflict);
        require(conflict.kind() != ConflictKind.SOURCE, "Source conflict resolution belongs to the service");
        require(sameType(conflict.local().value(), resolution), "Resolution must preserve the supported type");
        PropertyValue incoming = conflict.incoming().value();
        require(incoming == null || sameType(incoming, resolution), "Resolution changes the incoming type");
        List<AuthoredEdit> resolved = new ArrayList<>();
        boolean found = false;
        for (AuthoredEdit authored : original) {
            if (!authored.target().equals(conflict.target())) {
                resolved.add(authored);
                continue;
            }
            require(!found, "Ambiguous origin edit");
            require(sameValue(authored.before(), conflict.base().value())
                    && sameValue(authored.after(), conflict.local().value()), "Conflict no longer matches origin edit");
            found = true;
            resolved.add(new AuthoredEdit(incoming == null ? EditKind.ADD_ATTRIBUTE : EditKind.SET_ATTRIBUTE,
                    authored.documentId(), authored.target(), authored.attributeName(), incoming, resolution));
        }
        require(found, "Conflict target does not belong to supplied origin edits");
        return List.copyOf(resolved);
    }

    private static String conflictId(String changeId, PropertyTarget target) {
        String key = changeId.length() + ":" + changeId + target.elementId().length() + ":"
                + target.elementId() + target.attributeId().length() + ":" + target.attributeId();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void required(String value) {
        require(value != null && !value.isBlank(), "Nonempty revision, change and actor IDs are required");
    }

    private static AuthoredEdit edit(EditKind kind, ModelElement owner, Attribute attribute,
                                     PropertyValue before, PropertyValue after) {
        return new AuthoredEdit(kind, owner.documentId(), new PropertyTarget(owner.id(), attribute.id()),
                attribute.name(), before, after);
    }

    private static Map<String, ModelElement> index(Analysis analysis) {
        Objects.requireNonNull(analysis);
        require(analysis.state() == AnalysisState.VALID, "A current valid analysis is required");
        Map<String, ModelElement> elements = new LinkedHashMap<>();
        Map<String, Attribute> allAttributes = new LinkedHashMap<>();
        for (ModelElement element : analysis.elements()) {
            require(analysis.documentVersions().containsKey(element.documentId()),
                    "Missing analyzed document version: " + element.documentId());
            require(elements.putIfAbsent(element.id(), element) == null, "Duplicate element identity");
            for (Attribute attribute : attributes(element).values())
                require(allAttributes.putIfAbsent(attribute.id(), attribute) == null, "Duplicate attribute identity");
        }
        return elements;
    }

    private static Map<String, Attribute> attributes(ModelElement element) {
        Map<String, Attribute> attributes = new LinkedHashMap<>();
        Map<String, Attribute> names = new LinkedHashMap<>();
        for (Attribute attribute : element.attributes()) {
            require(element.id().equals(attribute.ownerId()), "Attribute owner mismatch: " + attribute.id());
            require(attributes.putIfAbsent(attribute.id(), attribute) == null, "Duplicate attribute identity");
            require(names.putIfAbsent(attribute.name(), attribute) == null, "Ambiguous attribute name");
        }
        return attributes;
    }

    private static boolean sameType(PropertyValue left, PropertyValue right) {
        return left != null && right != null && Objects.equals(left.typeName(), right.typeName())
                && left.scalar() != null && right.scalar() != null
                && left.scalar().kind() == right.scalar().kind();
    }

    private static boolean sameValue(PropertyValue left, PropertyValue right) {
        return left == null || right == null ? left == right
                : sameType(left, right) && left.scalar().equals(right.scalar());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new UnsupportedEditException(message);
    }
}
