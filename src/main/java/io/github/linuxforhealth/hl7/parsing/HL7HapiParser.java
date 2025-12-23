/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.linuxforhealth.hl7.parsing;

import ca.uhn.hl7v2.DefaultHapiContext;
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



}
