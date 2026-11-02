package app;

import static app.ModelContracts.*;

import java.util.Objects;

/** Narrow UTF-16 source patches. The caller must reanalyze candidates before accepting them. */
public final class SourceEdits {
    private SourceEdits() {}

    public static SourceDocument replace(SourceDocument document, Attribute attribute,
                                         String expectedExpression, String replacement) {
        Objects.requireNonNull(expectedExpression);
        Objects.requireNonNull(replacement);
        if (!attribute.editable() || attribute.valueRange() == null || attribute.value() == null)
            throw new IllegalArgumentException("Attribute is read-only");
        if (!expectedExpression.equals(attribute.value().expression()) ||
            !expectedExpression.equals(attribute.valueRange().slice(document.text())))
            throw new IllegalArgumentException("Stale attribute expression/range");
        requireLiteral(replacement);
        return patch(document, attribute.valueRange().start(), attribute.valueRange().end(), replacement);
    }

    public static SourceDocument addAttribute(SourceDocument document, ModelElement owner,
                                              String name, String typeName, String expression) {
        Objects.requireNonNull(expression);
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Attribute name must be a simple identifier");
        if (typeName == null || !typeName.matches("(?:ScalarValues::)?(?:Boolean|String|Integer|Real)"))
            throw new IllegalArgumentException("Unsupported scalar type name");
        if (!document.documentId().equals(owner.documentId()) || owner.insertionOffset() == null || owner.declarationRange() == null)
            throw new IllegalArgumentException("Owner has no matching authored body");
        if (owner.attributes().stream().anyMatch(a -> a.name().equals(name)))
            throw new IllegalArgumentException("Attribute already exists");
        int offset = owner.insertionOffset();
        String text = document.text();
        if (offset < owner.declarationRange().start() || offset >= owner.declarationRange().end() ||
            owner.declarationRange().end() > text.length() || text.charAt(offset) != '}')
            throw new IllegalArgumentException("Stale owner body boundary");
        requireLiteral(expression);
        String declaration = "attribute " + name + " : " + typeName + " = " + expression + ";";
        int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
        String indent = text.substring(lineStart, offset);
        if (indent.chars().allMatch(c -> c == ' ' || c == '\t')) {
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            return patch(document, lineStart, lineStart, indent + "    " + declaration + newline);
        }
        return patch(document, offset, offset, " " + declaration + " ");
    }

    private static void requireLiteral(String expression) {
        // A lexical safety check, not a SysML parser. The official reanalysis decides syntax/type support.
        // Never let a structured scalar edit smuggle in another declaration or a line comment.
        String value = expression.strip();
        if (!value.matches("true|false|[+-]?\\s*(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|\\\"(?:[^\\\"\\\\\\r\\n]|\\\\[^\\r\\n])*\\\""))
            throw new IllegalArgumentException("Expected a single scalar literal expression");
    }

    private static SourceDocument patch(SourceDocument document, int start, int end, String replacement) {
        return new SourceDocument(document.documentId(), Math.addExact(document.version(), 1),
            document.text().substring(0, start) + replacement + document.text().substring(end));
    }
}
