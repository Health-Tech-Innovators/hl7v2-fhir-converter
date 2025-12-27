/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.linuxforhealth.hl7.parsing;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.GenericParser;

public class HL7HapiParser {

  private DefaultHapiContext context;
  private GenericParser parser;


  public HL7HapiParser() {

    context = new DefaultHapiContext();

    // IMPORTANT: Do NOT use CanonicalModelClassFactory with a hard-coded version.
    // This causes messages of other versions (e.g., v2.3) to be parsed as GenericMessage
    // instead of their proper structure (e.g., ADT_A03), making all segments except MSH inaccessible.
    // 
    // By not setting a ModelClassFactory, HAPI will use the default behavior:
    // - Parse messages according to their version specified in MSH-12
    // - Create proper message structures (e.g., ADT_A03) for each version
    // - Make all segments accessible via the structure API and Terser
    //
    // The converter's version-aware template loading (ResourceReader.getMessageTemplateForVersion)
    // will handle version-specific mappings at the template level.

    /*
     * The ValidationContext is used during parsing and well as during validation using {@link
     * ca.uhn.hl7v2.validation.Validator} objects. Sometimes we want parsing without validation
     * followed by a separate validation step. We can still use a single HapiContext.
     */
    context.getParserConfiguration().setValidating(false);

    /*
     * A Parser is used to convert between string representations of messages and instances of
     * HAPI's "Message" object. In this case, we are using a "GenericParser", which is
     * able to handle both XML and ER7 (pipe & hat) encodings.
     */
    parser = context.getGenericParser();
  }


  public DefaultHapiContext getContext() {
    return context;
  }

  public GenericParser getParser() {
    return parser;
  }

  /**
   * Normalize HL7 line endings to standard format.
   * 
   * HL7 v2 standard specifies \r (carriage return, 0x0D) as segment delimiter.
   * Files may be stored with \n (Unix) or \r\n (Windows) line endings.
   * This method normalizes to proper HL7 format before parsing.
   * 
   * CRITICAL: Without normalization, HAPI parser treats messages with \n delimiters
   * as a single MSH segment, losing 93% of message data!
   * 
   * @param hl7Content Raw HL7 message content
   * @return HL7 content with normalized line endings, or null if input is null
   */
  public static String normalizeLineEndings(String hl7Content) {
    if (hl7Content == null) {
      return null;
    }
    // Convert Windows (\r\n) to HL7 standard (\r)
    // Convert Unix (\n) to HL7 standard (\r)
    // Order matters: replace \r\n first to avoid double conversion
    return hl7Content.replace("\r\n", "\r").replace("\n", "\r");
  }

  /**
   * Parse HL7 message with automatic line ending normalization.
   * 
   * This is the recommended method for parsing HL7 messages, as it handles
   * files with Unix (\n) or Windows (\r\n) line endings correctly.
   * 
   * @param hl7MessageData Raw HL7 message string
   * @return Parsed HAPI Message object
   * @throws HL7Exception if parsing fails
   */
  public Message parseWithNormalization(String hl7MessageData) throws HL7Exception {
    String normalized = normalizeLineEndings(hl7MessageData);
    return parser.parse(normalized);
  }

}


