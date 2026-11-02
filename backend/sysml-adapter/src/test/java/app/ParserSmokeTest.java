package app;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.validation.Issue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.AttributeUsage;
import org.omg.sysml.lang.sysml.LiteralInteger;
import org.omg.sysml.lang.sysml.PartDefinition;

/** Real official parser and libraries. No mock parser or handwritten syntax recognition. */
class ParserSmokeTest {
    @TempDir static Path directory;
    static SysMLInteractive pilot;
    static String fixture;

    @BeforeAll static void startOfficialRuntime() throws Exception {
        long start = System.nanoTime();
        fixture = Files.readString(Path.of(System.getProperty("sysml.root"), "fixtures", "pump.sysml"));
        pilot = SysMLInteractive.createInstance();
        pilot.setVerbose(false);
        pilot.loadLibrary(System.getProperty("sysml.pilot.library"));
        assertEquals(94, pilot.getLibraryResources().size());
        pilot.setResource(directory.resolve("pump.sysml").toAbsolutePath().toString());
        pilot.addInputResource(pilot.getResource());
        System.out.println("PILOT_LIBRARY_STARTUP_MS=" + (System.nanoTime() - start) / 1_000_000);
    }

    static List<Issue> analyze(String text) throws Exception {
        pilot.parse(text);
        pilot.addResourceToIndex(pilot.getResource());
        pilot.resolveAllInputResources();
        return pilot.validate();
    }

    static List<EObject> objects() {
        var result = new ArrayList<EObject>();
        pilot.getResource().getAllContents().forEachRemaining(result::add);
        return result;
    }

    @Test void officialModelLinksPumpAttributeToStandardLibrary() throws Exception {
        var issues = analyze(fixture);
        assertTrue(issues.stream().noneMatch(i -> i.getSeverity() == Severity.ERROR), issues.toString());
        assertTrue(objects().stream().anyMatch(o -> o instanceof PartDefinition p && p.getName().equals("Pump")));
        var attribute = objects().stream().filter(AttributeUsage.class::isInstance)
            .map(AttributeUsage.class::cast).filter(a -> a.getName().equals("flowRate")).findFirst().orElseThrow();
        assertTrue(attribute.getType().stream().anyMatch(t ->
            !t.eIsProxy() && t.getQualifiedName().equals("ScalarValues::Real")));
        var literal = objects().stream().filter(LiteralInteger.class::isInstance)
            .map(LiteralInteger.class::cast).filter(v -> v.getValue() == 10).findFirst().orElseThrow();
        assertEquals("10", NodeModelUtils.getNode(literal).getText().strip());
    }

    @Test void malformedSourceHasAGenuineSyntaxDiagnostic() throws Exception {
        var issues = analyze(fixture.replace("= 10;", "= ;"));
        assertTrue(issues.stream().anyMatch(i -> i.getSeverity() == Severity.ERROR && i.isSyntaxError()), issues.toString());
    }

    @Test void unresolvedTypeHasAGenuineLinkerDiagnostic() throws Exception {
        var issues = analyze(fixture.replace(": Real", ": MissingType"));
        assertTrue(issues.stream().anyMatch(i -> i.getSeverity() == Severity.ERROR && i.getMessage().contains("MissingType")), issues.toString());
    }

    @Test void warmReparseChangesTheGenuineLiteral() throws Exception {
        analyze(fixture);
        long start = System.nanoTime();
        var issues = analyze(fixture.replace("= 10;", "= 12;"));
        assertTrue(issues.stream().noneMatch(i -> i.getSeverity() == Severity.ERROR), issues.toString());
        assertTrue(objects().stream().anyMatch(o -> o instanceof LiteralInteger literal && literal.getValue() == 12));
        System.out.println("PILOT_WARM_REPARSE_MS=" + (System.nanoTime() - start) / 1_000_000);
    }

    @Test void applicationGsonWinsOverTheShadedOlderCopy() {
        String origin = Gson.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(origin.endsWith("gson-2.15.0.jar"), "Unexpected Gson origin: " + origin);
        var document = new ModelContracts.SourceDocument("pump.sysml", 1, fixture);
        var gson = new Gson();
        assertEquals(document, gson.fromJson(gson.toJson(document), ModelContracts.SourceDocument.class));
    }
}
