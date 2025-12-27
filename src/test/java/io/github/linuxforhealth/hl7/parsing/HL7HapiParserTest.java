package io.github.linuxforhealth.hl7.parsing;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Structure;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for HL7HapiParser line ending normalization.
 * 
 * Critical issue: HL7 v2 standard specifies \r (carriage return, 0x0D) as segment delimiter,
 * but files may be stored with \n (Unix) or \r\n (Windows) line endings.
 * 
 * Without normalization, HAPI parser treats the entire message as one MSH segment,
 * losing 93% of message data.
 */
public class HL7HapiParserTest {

    // Sample HL7 ADT_A03 message with minimal required fields
    private static final String HL7_MESSAGE_BASE = 
        "MSH|^~\\&|EPIC|WFH|||20251113001015||ADT^A03||P|2.3||||||||||" +
        "EVN|A03|20251113001015||" +
        "PID|1||E1259049^^^EPIC^MRN||DOE^JOHN^A||19800101|M||" +
        "PV1|1|INPATIENT|||||||||||||||||||||||||||||||||||||";

    /**
     * Test Case 1: Parse message with Unix line endings (\n)
     */
    @Test
    public void testParseWithUnixLineEndings() throws Exception {
        String hl7WithUnix = HL7_MESSAGE_BASE.replace("EVN", "\nEVN")
                                              .replace("PID", "\nPID")
                                              .replace("PV1", "\nPV1");
        
        HL7HapiParser parser = new HL7HapiParser();
        Message message = parser.parseWithNormalization(hl7WithUnix);
        
        assertNotNull(message);
        assertEquals("ADT_A03", message.getName());
        
        Segment msh = (Segment) message.get("MSH");
        assertFalse(msh.isEmpty());
        
        Segment evn = (Segment) message.get("EVN");
        assertFalse(evn.isEmpty());
        
        Segment pid = (Segment) message.get("PID");
        assertFalse(pid.isEmpty());
        assertEquals(30, pid.numFields());
        
        Segment pv1 = (Segment) message.get("PV1");
        assertFalse(pv1.isEmpty());
    }

    /**
     * Test Case 2: Parse message with Windows line endings (\r\n)
     */
    @Test
    public void testParseWithWindowsLineEndings() throws Exception {
        String hl7WithWindows = HL7_MESSAGE_BASE.replace("EVN", "\r\nEVN")
                                                 .replace("PID", "\r\nPID")
                                                 .replace("PV1", "\r\nPV1");
        
        HL7HapiParser parser = new HL7HapiParser();
        Message message = parser.parseWithNormalization(hl7WithWindows);
        
        assertNotNull(message);
        assertEquals("ADT_A03", message.getName());
        
        Segment pid = (Segment) message.get("PID");
        assertFalse(pid.isEmpty());
        
        Segment pv1 = (Segment) message.get("PV1");
        assertFalse(pv1.isEmpty());
    }

    /**
     * Test Case 3: Parse message with proper HL7 format (\r)
     */
    @Test
    public void testParseWithProperHL7Format() throws Exception {
        String hl7Proper = HL7_MESSAGE_BASE.replace("EVN", "\rEVN")
                                           .replace("PID", "\rPID")
                                           .replace("PV1", "\rPV1");
        
        HL7HapiParser parser = new HL7HapiParser();
        Message message = parser.parseWithNormalization(hl7Proper);
        
        assertNotNull(message);
        assertEquals("ADT_A03", message.getName());
        
        Segment pid = (Segment) message.get("PID");
        assertFalse(pid.isEmpty());
        
        Segment pv1 = (Segment) message.get("PV1");
        assertFalse(pv1.isEmpty());
    }

    /**
     * Test Case 4: Parse real Ascension file
     */
    @Test
    public void testParseRealAscensionMessage() throws Exception {
        String filePath = "../python/tests/fixtures/Ascension/individual_messages/adt_multiple_msg_1.hl7";
        
        if (!Files.exists(Paths.get(filePath))) {
            filePath = "../../python/tests/fixtures/Ascension/individual_messages/adt_multiple_msg_1.hl7";
            if (!Files.exists(Paths.get(filePath))) {
                System.out.println("Skipping real file test - file not found");
                return;
            }
        }
        
        String hl7Content = new String(Files.readAllBytes(Paths.get(filePath)));
        
        HL7HapiParser parser = new HL7HapiParser();
        Message message = parser.parseWithNormalization(hl7Content);
        
        assertNotNull(message);
        assertEquals("ADT_A03", message.getName());
        
        Segment pid = (Segment) message.get("PID");
        assertFalse(pid.isEmpty());
        assertEquals(30, pid.numFields());
        
        Segment pv1 = (Segment) message.get("PV1");
        assertFalse(pv1.isEmpty());
        
        Structure[] nk1s = message.getAll("NK1");
        assertTrue(nk1s.length > 0);
        for (Structure nk1 : nk1s) {
            if (nk1 instanceof Segment) {
                assertFalse(((Segment) nk1).isEmpty());
            }
        }
        
        Structure[] al1s = message.getAll("AL1");
        assertTrue(al1s.length > 0);
        for (Structure al1 : al1s) {
            if (al1 instanceof Segment) {
                assertFalse(((Segment) al1).isEmpty());
            }
        }
    }

    /**
     * Test Case 5: Backward compatibility
     */
    @Test
    public void testBackwardCompatibility() throws Exception {
        String hl7 = HL7_MESSAGE_BASE.replace("EVN", "\rEVN")
                                     .replace("PID", "\rPID")
                                     .replace("PV1", "\rPV1");
        
        HL7HapiParser parser = new HL7HapiParser();
        
        Message message1 = parser.getParser().parse(hl7);
        assertNotNull(message1);
        
        Message message2 = parser.parseWithNormalization(hl7);
        assertNotNull(message2);
        
        assertEquals(message1.getName(), message2.getName());
        assertEquals(message1.getVersion(), message2.getVersion());
    }

    /**
     * Test Case 6: Edge cases
     */
    @Test
    public void testNormalizationEdgeCases() throws Exception {
        assertNull(HL7HapiParser.normalizeLineEndings(null));
        
        assertEquals("", HL7HapiParser.normalizeLineEndings(""));
        
        String mixed = "MSH|...\nEVN|...\r\nPID|...\n";
        String normalized = HL7HapiParser.normalizeLineEndings(mixed);
        
        assertFalse(normalized.contains("\n"));
        assertTrue(normalized.contains("\r"));
        
        int crCount = 0;
        for (char c : normalized.toCharArray()) {
            if (c == '\r') crCount++;
        }
        assertEquals(3, crCount);
    }

    /**
     * Test Case 7: Performance check
     */
    @Test
    public void testNormalizationPerformance() {
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeMessage.append("MSH|^~\\&|TEST|TEST|||20251113|||TEST|P|2.3||||||||||\n");
        }
        String large = largeMessage.toString();
        
        long startTime = System.nanoTime();
        String normalized = HL7HapiParser.normalizeLineEndings(large);
        long endTime = System.nanoTime();
        
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(normalized);
        assertTrue(durationMs < 10);
        
        System.out.println("Normalization performance: " + durationMs + "ms for " + 
                          large.length() + " characters");
    }
}
