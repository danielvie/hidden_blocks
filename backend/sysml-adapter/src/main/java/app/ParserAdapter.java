package app;

import static app.ModelContracts.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ExtensibleURIConverterImpl;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.impl.ResourceDescriptionsData;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.*;
import org.omg.sysml.util.FeatureUtil;

/**
 * Official Pilot, never process()/delete(). Parse/index/resolve/validate follow the pinned sources:
 * https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml.interactive/src/org/omg/sysml/interactive/SysMLInteractive.java
 * https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml/src/org/omg/sysml/io/SysMLUtil.java
 */
public final class ParserAdapter implements AutoCloseable {
    // Pilot setup and library helpers contain static mutable state. Serialize across adapter instances too.
    private static final Object PARSER_LOCK = new Object();
    private final Path libraries;
    private final Path analysisRoot;
    private final Map<String, SysMLInteractive> workspaces = new LinkedHashMap<>();
    private boolean closed;

    public record Result(Analysis analysis, Map<String, String> identities) {
        public Result { identities = Map.copyOf(identities); }
    }

    public ParserAdapter(Path libraries, Path analysisRoot) {
        this.libraries = libraries.toAbsolutePath().normalize();
        this.analysisRoot = analysisRoot.toAbsolutePath().normalize();
    }

    public synchronized Result analyze(String workspaceId, long revision, List<SourceDocument> documents,
                                       Map<String, String> identities) {
        synchronized (PARSER_LOCK) {
            if (closed) throw new IllegalStateException("Parser adapter is closed");
            var versions = new LinkedHashMap<String, Long>();
            for (var document : documents) {
                validateDocumentId(document.documentId());
                if (versions.put(document.documentId(), document.version()) != null)
                    throw new IllegalArgumentException("Duplicate document ID");
            }
            var ids = new LinkedHashMap<>(identities);
            var diagnostics = new ArrayList<Diagnostic>();
            var elements = new ArrayList<ModelElement>();
            var connections = new ArrayList<Connection>();
            try {
                var pilot = workspace(workspaceId);
                clearInputs(pilot);
                var resources = new LinkedHashMap<SourceDocument, XtextResource>();
                Path root = analysisRoot.resolve(UUID.nameUUIDFromBytes(workspaceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                for (var document : documents) {
                    pilot.setResource(root.resolve(document.documentId()).toString());
                    pilot.addInputResource(pilot.getResource());
                    pilot.parse(document.text());
                    resources.put(document, (XtextResource) pilot.getResource());
                }
                var ambiguous = ambiguousNames(resources, diagnostics);
                // All documents must be indexed before any reference is resolved.
                resources.values().forEach(pilot::addResourceToIndex);
                pilot.resolveAllInputResources();
                for (var entry : resources.entrySet()) {
                    var document = entry.getKey();
                    var resource = entry.getValue();
                    var validator = resource.getResourceServiceProvider().getResourceValidator();
                    for (var issue : validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl)) {
                        var severity = switch (issue.getSeverity()) {
                            case ERROR -> Severity.ERROR;
                            case WARNING -> Severity.WARNING;
                            default -> Severity.INFO;
                        };
                        SourceRange range = issue.getOffset() == null || issue.getLength() == null ? null :
                            new SourceRange(issue.getOffset(), issue.getOffset() + issue.getLength());
                        diagnostics.add(new Diagnostic(severity, issue.isSyntaxError() ? "syntax" : "semantic",
                            issue.getMessage(), document.documentId(), range));
                    }
                }
                extract(resources, pilot.getLibraryResources(), ambiguous, ids, elements, connections, diagnostics);
            } catch (IOException | RuntimeException e) {
                diagnostics.add(new Diagnostic(Severity.ERROR, "parser", e.getMessage() == null ? e.toString() : e.getMessage(), null, null));
                // Do not retain partially linked resources after an unexpected failure.
                var pilot = workspaces.remove(workspaceId);
                if (pilot != null) dispose(pilot);
            }
            var state = diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR) ? AnalysisState.INVALID : AnalysisState.VALID;
            return new Result(new Analysis(workspaceId, revision, versions, state, elements, diagnostics, connections), ids);
        }
    }

    private SysMLInteractive workspace(String id) {
        var existing = workspaces.get(id);
        if (existing != null) return existing;
        // Bound library memory while retaining isolated, warm Alice/Bob resource sets.
        if (workspaces.size() == 2) {
            var first = workspaces.keySet().iterator().next();
            dispose(workspaces.remove(first));
        }
        var pilot = SysMLInteractive.createInstance();
        pilot.setVerbose(false);
        pilot.loadLibrary(libraries.toString());
        // Inputs are strings, libraries are already loaded. No source-controlled URI may perform I/O.
        pilot.getResourceSet().setURIConverter(new ExtensibleURIConverterImpl(List.of(), List.of()) {
            @Override public InputStream createInputStream(URI uri, Map<?, ?> options) throws IOException {
                throw new IOException("External resource loading is disabled: " + uri);
            }
        });
        workspaces.put(id, pilot);
        return pilot;
    }

    private static void clearInputs(SysMLInteractive pilot) {
        var index = ResourceDescriptionsData.ResourceSetAdapter.findResourceDescriptionsData(pilot.getResourceSet());
        for (var resource : List.copyOf(pilot.getInputResources())) {
            index.removeDescription(resource.getURI());
            resource.unload();
            pilot.getResourceSet().getResources().remove(resource);
        }
        pilot.getInputResources().clear();
        // Restore any library names shadowed by an invalid previous document.
        pilot.getLibraryResources().forEach(pilot::addResourceToIndex);
    }

    private static Set<String> ambiguousNames(Map<SourceDocument, XtextResource> resources, List<Diagnostic> diagnostics) {
        var seen = new HashSet<String>();
        var ambiguous = new HashSet<String>();
        resources.forEach((document, resource) -> resource.getAllContents().forEachRemaining(object -> {
            if (object instanceof Element element && (element instanceof PartDefinition || element instanceof PartUsage || element instanceof AttributeUsage || element instanceof PortUsage)
                && element.getDeclaredName() != null && NodeModelUtils.getNode(element) != null) {
                String name = authoredName(element);
                if (name != null && !seen.add(name)) {
                    ambiguous.add(name);
                    diagnostics.add(new Diagnostic(Severity.ERROR, "identity", "Ambiguous authored qualified name: " + name,
                        document.documentId(), range(element)));
                }
            }
        }));
        return ambiguous;
    }

    private static boolean part(Element element) {
        // Connections implement PartUsage too, but are edges rather than diagram blocks.
        return element instanceof PartDefinition || element instanceof PartUsage && !(element instanceof ConnectionUsage);
    }

    private static void extract(Map<SourceDocument, XtextResource> resources, Set<Resource> libraryResources,
                                Set<String> ambiguous, Map<String, String> ids,
                                List<ModelElement> output, List<Connection> connections, List<Diagnostic> diagnostics) {
        var documents = new IdentityHashMap<Resource, SourceDocument>();
        var authored = new ArrayList<Element>();
        var wires = new ArrayList<ConnectionUsage>();
        var sourceIds = new IdentityHashMap<Element, String>();
        // Pass one assigns every unambiguous authored identity before following cross-document references.
        resources.forEach((document, resource) -> {
            documents.put(resource, document);
            resource.getAllContents().forEachRemaining(object -> {
                if (!(object instanceof Element element) || NodeModelUtils.getNode(element) == null) return;
                if (element instanceof ConnectionUsage connection) wires.add(connection);
                if (!(part(element) || element instanceof AttributeUsage || element instanceof PortUsage || element instanceof ConnectionUsage)
                    || element.getDeclaredName() == null) return;
                String name = authoredName(element);
                if (name == null || ambiguous.contains(name)) return;
                authored.add(element);
                sourceIds.put(element, identity(ids, document.documentId(), name));
            });
        });
        var sourceAttributes = new IdentityHashMap<AttributeUsage, Attribute>();
        for (var element : authored) {
            if (!(element instanceof AttributeUsage attribute) || !sourceIds.containsKey(attribute.getOwningType())) continue;
            var document = documents.get(attribute.eResource());
            var expression = FeatureUtil.getValueExpressionFor(attribute);
            var expressionRange = range(expression);
            String text = expressionRange == null ? "" : expressionRange.slice(document.text());
            String type = attribute.getType().size() == 1 && !attribute.getType().getFirst().eIsProxy()
                ? attribute.getType().getFirst().getQualifiedName() : null;
            boolean libraryType = attribute.getType().size() == 1 && libraryResources.contains(attribute.getType().getFirst().eResource());
            ScalarValue scalar = libraryType ? scalar(type, expression, text) : null;
            if (libraryType && incompatibleLiteral(type, expression))
                diagnostics.add(new Diagnostic(Severity.ERROR, "scalar-type", "Literal is incompatible with resolved scalar type " + type,
                    document.documentId(), expressionRange));
            String reason = attribute.isDerived() ? "Derived attribute" : scalar == null ? "Unsupported or unresolved scalar expression/type" :
                expressionRange == null ? "Missing exact authored range" : null;
            if (attribute.isDerived()) scalar = null;
            String id = sourceIds.get(attribute);
            sourceAttributes.put(attribute, new Attribute(id, sourceIds.get(attribute.getOwningType()), attribute.getDeclaredName(),
                new PropertyValue(type, text, scalar), expressionRange, reason == null, reason,
                authoredName(attribute.getOwningType()), id, false));
        }
        var projectedPorts = new IdentityHashMap<Type, Map<PortUsage, String>>();
        // Pass two uses Pilot's effective feature list, which removes redefined base features.
        // See Type#getFeature / Feature#getChainingFeature in the pinned org.omg.sysml model API.
        for (var element : authored) {
            if (!part(element)) continue;
            var owner = (Type) element;
            var document = documents.get(element.eResource());
            String ownerId = sourceIds.get(element);
            String qname = authoredName(element);
            var attributes = new ArrayList<Attribute>();
            var ports = new ArrayList<Port>();
            var portIds = new IdentityHashMap<PortUsage, String>();
            projectedPorts.put(owner, portIds);
            for (var feature : owner.getFeature()) {
                // Input identities exclude all implicit library plumbing and ambiguous declarations.
                if (!(feature instanceof AttributeUsage || feature instanceof PortUsage) || !sourceIds.containsKey(feature)) continue;
                boolean inherited = feature.getOwningType() != owner;
                String featureId = inherited ? identity(ids, document.documentId(), qname + "::@inherited:" + sourceIds.get(feature)) : sourceIds.get(feature);
                if (feature instanceof AttributeUsage attribute) {
                    var source = sourceAttributes.get(attribute);
                    if (source == null) continue;
                    attributes.add(inherited ? new Attribute(featureId, ownerId, source.name(), source.value(), null, false,
                        "Inherited from " + source.declaredBy(), source.declaredBy(), source.id(), true) : source);
                } else if (feature instanceof PortUsage port) {
                    var type = port.getPortDefinition().size() == 1 ? port.getPortDefinition().getFirst() : null;
                    boolean conjugated = type instanceof ConjugatedPortDefinition;
                    if (type instanceof ConjugatedPortDefinition conjugate) type = conjugate.getOriginalPortDefinition();
                    ports.add(new Port(featureId, ownerId, port.getDeclaredName(), type == null || type.eIsProxy() ? null : type.getQualifiedName(),
                        conjugated, inherited, authoredName(port.getOwningType())));
                    portIds.put(port, featureId);
                }
            }
            Type explicitType = null;
            if (owner instanceof PartUsage usage) {
                var types = usage.getOwnedTyping().stream().filter(t -> NodeModelUtils.getNode(t) != null)
                    .map(FeatureTyping::getType).filter(t -> t != null && !t.eIsProxy()).toList();
                if (types.size() == 1) explicitType = types.getFirst();
            }
            // On usages the cue describes the explicit type's direct bases, not instance generalization.
            var specializationOwner = explicitType == null ? owner : explicitType;
            var superTypeIds = specializationOwner.getOwnedSpecialization().stream()
                .filter(s -> NodeModelUtils.getNode(s) != null && !(s instanceof FeatureTyping))
                .map(Specialization::getGeneral).filter(PartDefinition.class::isInstance)
                .map(sourceIds::get).filter(Objects::nonNull).distinct().toList();
            String containerId = null;
            for (EObject parent = element.eContainer(); parent != null; parent = parent.eContainer()) {
                if (parent instanceof Element enclosing && part(enclosing)) {
                    containerId = sourceIds.get(enclosing);
                    break;
                }
            }
            Integer insertion = null;
            for (var leaf : NodeModelUtils.getNode(element).getLeafNodes()) if (!leaf.isHidden())
                insertion = leaf.getText().equals("}") ? leaf.getOffset() : null;
            output.add(new ModelElement(ownerId, document.documentId(), qname, element.getDeclaredName(),
                element instanceof PartDefinition ? "PartDefinition" : "PartUsage", range(element), insertion, attributes,
                explicitType == null ? null : explicitType.getQualifiedName(), sourceIds.get(explicitType), superTypeIds, ports, containerId));
        }
        var occurrences = new HashMap<String, Integer>();
        for (var wire : wires) {
            // These are linked feature chains, not tokens parsed from the source spelling of a connect statement.
            var ends = wire.getRelatedFeature();
            if (ends.size() != 2) continue;
            var source = endpoint(ends.getFirst(), sourceIds, projectedPorts);
            var target = endpoint(ends.getLast(), sourceIds, projectedPorts);
            if (source == null || target == null) continue;
            var document = documents.get(wire.eResource());
            String id = sourceIds.get(wire);
            if (wire.getDeclaredName() != null && id == null) continue;
            if (id == null) {
                String key = authoredName(wire) + "::@connection:" + source.elementId() + "/" + source.portId() + "->" + target.elementId() + "/" + target.portId();
                id = identity(ids, document.documentId(), key + ":" + occurrences.merge(key, 1, Integer::sum));
            }
            connections.add(new Connection(id, wire.getDeclaredName(), source.elementId(), source.portId(), target.elementId(), target.portId()));
        }
    }

    private record Endpoint(String elementId, String portId) {}

    private static Endpoint endpoint(Feature feature, Map<Element, String> sourceIds,
                                     Map<Type, Map<PortUsage, String>> projectedPorts) {
        var chain = feature.getChainingFeature();
        var last = chain.isEmpty() ? feature.getFeatureTarget() : chain.getLast();
        if (!(last instanceof PortUsage port)) return null;
        Type owner = port.getOwningType();
        for (int i = chain.size() - 2; i >= 0; i--) {
            if (projectedPorts.containsKey(chain.get(i))) {
                owner = chain.get(i);
                break;
            }
        }
        var ports = projectedPorts.get(owner);
        String portId = ports == null ? null : ports.get(port);
        return portId == null ? null : new Endpoint(sourceIds.get(owner), portId);
    }

    private static String authoredName(Element element) {
        var names = new ArrayList<String>();
        for (EObject current = element; current != null; current = current.eContainer())
            if (current instanceof Namespace namespace && namespace.getDeclaredName() != null) names.add(namespace.escapedName());
        Collections.reverse(names);
        return names.isEmpty() ? null : String.join("::", names);
    }

    private static String identity(Map<String, String> ids, String documentId, String qualifiedName) {
        return ids.computeIfAbsent(documentId + "#" + qualifiedName, key -> UUID.randomUUID().toString());
    }

    private static SourceRange range(EObject object) {
        ICompositeNode node = object == null ? null : NodeModelUtils.getNode(object);
        return node == null ? null : new SourceRange(node.getOffset(), node.getEndOffset());
    }

    private static boolean incompatibleLiteral(String type, Expression expression) {
        if (type == null || !(expression instanceof LiteralExpression)) return false;
        return switch (type) {
            case "ScalarValues::Boolean" -> !(expression instanceof LiteralBoolean);
            case "ScalarValues::String" -> !(expression instanceof LiteralString);
            case "ScalarValues::Integer" -> !(expression instanceof LiteralInteger);
            case "ScalarValues::Real" -> !(expression instanceof LiteralInteger || expression instanceof LiteralRational);
            default -> false;
        };
    }

    private static boolean numericLiteral(Expression expression) {
        if (expression instanceof LiteralInteger || expression instanceof LiteralRational) return true;
        if (!(expression instanceof OperatorExpression operator) || !Set.of("+", "-").contains(operator.getOperator())) return false;
        // getOperand() is a write-only grammar facade. OperandEList directs readers to getArgument().
        var operands = operator.getArgument();
        return operands.size() == 1 && (operands.getFirst() instanceof LiteralInteger || operands.getFirst() instanceof LiteralRational);
    }

    private static ScalarValue scalar(String type, Expression expression, String text) {
        if (type == null || expression == null) return null;
        try {
            return switch (type) {
                case "ScalarValues::Boolean" -> expression instanceof LiteralBoolean literal
                    ? new ScalarValue(ScalarKind.BOOLEAN, Boolean.toString(literal.isValue())) : null;
                case "ScalarValues::String" -> expression instanceof LiteralString literal ? new ScalarValue(ScalarKind.STRING, literal.getValue()) : null;
                case "ScalarValues::Integer" -> numericLiteral(expression) && !text.contains(".") && !text.toLowerCase(Locale.ROOT).contains("e")
                    ? new ScalarValue(ScalarKind.INTEGER, text.replaceAll("\\s+", "")) : null;
                case "ScalarValues::Real" -> numericLiteral(expression)
                    ? new ScalarValue(ScalarKind.REAL, text.replaceAll("\\s+", "")) : null;
                default -> null;
            };
        } catch (NumberFormatException e) { return null; }
    }

    private static void validateDocumentId(String id) {
        if (!id.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.sysml") ||
            Arrays.asList(id.split("/")).contains("..") || Arrays.asList(id.split("/")).contains("."))
            throw new IllegalArgumentException("Document ID must be a relative .sysml path");
    }

    private static void dispose(SysMLInteractive pilot) {
        for (var resource : List.copyOf(pilot.getResourceSet().getResources())) resource.unload();
        pilot.getInputResources().clear();
        pilot.getResourceSet().getResources().clear();
    }

    @Override public synchronized void close() {
        synchronized (PARSER_LOCK) {
            workspaces.values().forEach(ParserAdapter::dispose);
            workspaces.clear();
            closed = true;
        }
    }
}
