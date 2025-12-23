/*
 * (C) Copyright IBM Corp. 2020, 2021
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.linuxforhealth.hl7.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.github.linuxforhealth.api.ResourceModel;
import io.github.linuxforhealth.core.Constants;
import io.github.linuxforhealth.core.ObjectMapperUtil;
import io.github.linuxforhealth.core.config.ConverterConfiguration;
import io.github.linuxforhealth.hl7.message.HL7FHIRResourceTemplate;
import io.github.linuxforhealth.hl7.message.HL7FHIRResourceTemplateAttributes;
import io.github.linuxforhealth.hl7.message.HL7MessageModel;

/**
 * Reads resources. If the configuration file has base path defined
 * (base.path.resource) then the
 * resources are loaded from that path. If the configuration is not defined then
 * default path would
 * be used.
 *
 *
 * @author pbhallam
 */
public class ResourceReader {

  private final Logger LOGGER = LoggerFactory.getLogger(ResourceReader.class);

  private static ResourceReader reader;

  private final ConverterConfiguration converterConfig = ConverterConfiguration.getInstance();

  /**
   * Loads a file resource configuration, returning a String
   *
   * @param fileResourceConfiguration The configuration resource to load
   * @return String
   * @throws IOException if an error occurs loading the file resource
   */
  private static String loadFileResource(File fileResourceConfiguration) throws IOException {
    return FileUtils.readFileToString(fileResourceConfiguration, StandardCharsets.UTF_8);
  }

  /**
   * Loads a class path configuration resource, returning a String
   *
   * @param resourceConfigurationPath The class path configuration resource
   * @return String the resource content
   * @throws IOException if an error occurs loading the configuration resource
   */
  private static String loadClassPathResource(String resourceConfigurationPath) throws IOException {
    return IOUtils.resourceToString(resourceConfigurationPath, StandardCharsets.UTF_8,
        ResourceReader.class.getClassLoader());
  }

  /**
   * Loads a file based resource using a three pass approach. The first pass
   * attempts to load the
   * resource from the file system. The second attempts to load it from the
   * alternate resource path.
   * on the file system. If the file is not found on the file system, the resource
   * is
   * loaded from the classpath.
   *
   * @param resourcePath The relative path to the resource (hl7/, fhir/, etc)
   * @return The resource as a String
   */
  public String getResource(String resourcePath) {
    String resourceFolderName = converterConfig.getResourceFolder();
    String additionalResourcesFolderName = converterConfig.getAdditionalResourcesLocation();
    String resource = null;

    try {
      if (resourceFolderName != null) {
        Path resourceFolderFilePath = Paths.get(resourceFolderName, resourcePath);
        if (resourceFolderFilePath.toFile().exists()) {
          resource = loadFileResource(resourceFolderFilePath.toFile());
        }
      }

      if (resource == null && additionalResourcesFolderName != null) {
        Path alternateResourceFolderFilePath = Paths.get(additionalResourcesFolderName, resourcePath);

        if (alternateResourceFolderFilePath.toFile().exists()) {
          resource = loadFileResource(alternateResourceFolderFilePath.toFile());
        }
      }

      if (resource == null) {
        resource = loadClassPathResource(resourcePath);
      }
    } catch (IOException ioEx) {
      String msg = "Unable to load resource " + resourcePath;
      throw new IllegalArgumentException(msg, ioEx);
    }
    return resource;
  }

  /**
   * Returns all message templates in the configured location(s)
   * Relies on the values in config.properties.
   *
   * @return Map of messages, by message title.
   */
  public Map<String, HL7MessageModel> getMessageTemplates() {
    Map<String, HL7MessageModel> messagetemplates = new HashMap<>();
    List<String> supportedMessageTemplates = ConverterConfiguration.getInstance().getSupportedMessageTemplates();
    if (hasWildcard(supportedMessageTemplates)) {
      // Code currently assumes we do no use the list of supported messages, once we
      // see an *.
      // In future if needed to merge, it would go here.
      supportedMessageTemplates.clear();
      supportedMessageTemplates = findAllMessageTemplateNames();
    }
    for (String template : supportedMessageTemplates) {
      HL7MessageModel rm = getMessageModel(template);
      messagetemplates.put(com.google.common.io.Files.getNameWithoutExtension(template),
          rm);
    }
    return messagetemplates;
  }

  private boolean hasWildcard(List<String> supportedMessageTemplates) {
    for (String template : supportedMessageTemplates) {
      if (template.contains("*")) {
        return true;
      }
    }
    return false;
  }

  private List<String> findAllMessageTemplateNames() {

    // Get the base templates
    List<String> foundTemplates = new ArrayList<>();
    File folder = new File(
        converterConfig.getResourceFolder() + '/' + Constants.HL7_BASE_PATH + Constants.MESSAGE_BASE_PATH);
    File[] listOfFiles = folder.listFiles();
    for (int i = 0; listOfFiles != null && i < listOfFiles.length; i++) {
      if (listOfFiles[i].isFile()) {
        foundTemplates.add(listOfFiles[i].getName());
      }
    }

    // Add the extended templates
    // Current code assumes the extended templates are exclusive of the base
    // templates.
    // In future, if there is to be a priority, or one can override the other,
    // changes needed here to merge extended templates.
    folder = new File(
        converterConfig.getAdditionalResourcesLocation() + '/' + Constants.HL7_BASE_PATH + Constants.MESSAGE_BASE_PATH);
    listOfFiles = folder.listFiles();
    for (int i = 0; listOfFiles != null && i < listOfFiles.length; i++) {
      if (listOfFiles[i].isFile()) {
        foundTemplates.add(listOfFiles[i].getName());
      }
    }

    return foundTemplates;
  }

  private HL7MessageModel getMessageModel(String templateName) {
    // Allow for names that already have .yml extension
    String yamlizedTemplateName = templateName.endsWith(".yml") ? templateName : templateName + ".yml";
    String templateFileContent = getResourceInHl7Folder(Constants.MESSAGE_BASE_PATH + yamlizedTemplateName);
    if (StringUtils.isNotBlank(templateFileContent)) {
      try {

        JsonNode parent = ObjectMapperUtil.getYAMLInstance().readTree(templateFileContent);
        Preconditions.checkState(parent != null, "Parent node from template file cannot be null");

        JsonNode resourceNodes = parent.get("resources");
        Preconditions.checkState(resourceNodes != null && !resourceNodes.isEmpty(),
            "List of resources from Parent node from template file cannot be null or empty");
        List<HL7FHIRResourceTemplateAttributes> templateAttributes = ObjectMapperUtil.getYAMLInstance().convertValue(
            resourceNodes,
            new TypeReference<List<HL7FHIRResourceTemplateAttributes>>() {
            });

        List<HL7FHIRResourceTemplate> templates = new ArrayList<>();

        templateAttributes.forEach(t -> templates.add(new HL7FHIRResourceTemplate(t)));
        Preconditions.checkState(templateAttributes != null && !templateAttributes.isEmpty(),
            "TemplateAttributes generated from template file cannot be null or empty");
        return new HL7MessageModel(templateName, templates);

      } catch (IOException e) {
        throw new IllegalArgumentException(
            "Error encountered in processing the template" + templateName, e);
      }
    } else {
      throw new IllegalArgumentException("File not present:" + templateName);
    }

  }

  public ResourceModel generateResourceModel(String path) {
    Preconditions.checkArgument(StringUtils.isNotBlank(path), "Path for resource cannot be blank");
    String templateFileContent = getResourceInHl7Folder(path + ".yml");

    try {
      InjectableValues injValues = new InjectableValues.Std().addValue("resourceName", path);
      return ObjectMapperUtil.getYAMLInstance().setInjectableValues(injValues)
          .readValue(templateFileContent, HL7DataBasedResourceModel.class);

    } catch (IOException e) {
      throw new IllegalArgumentException("Error encountered in processing the template" + path, e);
    }

  }

  public static ResourceReader getInstance() {
    if (reader == null) {
      reader = new ResourceReader();
    }
    return reader;
  }

  public static void reset() {
    reader = null;
  }

  public String getResourceInHl7Folder(String path) {
    return getResource(Constants.HL7_BASE_PATH + path);
  }

  /**
   * Checks if a resource exists at the given path.
   *
   * @param resourcePath The relative path to the resource
   * @return true if the resource exists, false otherwise
   */
  private boolean resourceExists(String resourcePath) {
    String resourceFolderName = converterConfig.getResourceFolder();
    String additionalResourcesFolderName = converterConfig.getAdditionalResourcesLocation();

    try {
      // Check in primary resource folder
      if (resourceFolderName != null) {
        Path resourceFolderFilePath = Paths.get(resourceFolderName, resourcePath);
        if (resourceFolderFilePath.toFile().exists()) {
          return true;
        }
      }

      // Check in additional resources folder
      if (additionalResourcesFolderName != null) {
        Path alternateResourceFolderFilePath = Paths.get(additionalResourcesFolderName, resourcePath);
        if (alternateResourceFolderFilePath.toFile().exists()) {
          return true;
        }
      }

      // Check in classpath
      try {
        InputStream is = ResourceReader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) {
          is.close();
          return true;
        }
      } catch (IOException e) {
        // Resource doesn't exist in classpath
      }
    } catch (Exception e) {
      LOGGER.debug("Error checking resource existence for {}: {}", resourcePath, e.getMessage());
    }

    return false;
  }

  /**
   * Gets a message template for a specific HL7 version.
   * Tries version-specific path first (e.g., hl7/v2.3/message/ADT_A03.yml),
   * then falls back to default path (e.g., hl7/message/ADT_A03.yml).
   *
   * @param messageType The message type (e.g., "ADT_A03")
   * @param version     The HL7 version (e.g., "2.3", "2.6")
   * @return HL7MessageModel for the message type, or null if not found
   */
  public HL7MessageModel getMessageTemplateForVersion(String messageType, String version) {
    if (StringUtils.isBlank(messageType)) {
      LOGGER.warn("Message type is blank, cannot load template");
      return null;
    }

    // Normalize version (e.g., "2.3" stays "2.3", "2.6" stays "2.6")
    String normalizedVersion = StringUtils.isNotBlank(version) ? version : "2.6";

    // Try version-specific template first
    String versionSpecificPath = String.format("v%s/%s%s.yml",
        normalizedVersion, Constants.MESSAGE_BASE_PATH, messageType);

    LOGGER.debug("Attempting to load version-specific template: {}", versionSpecificPath);

    if (resourceExists(Constants.HL7_BASE_PATH + versionSpecificPath)) {
      LOGGER.info("Loading version-specific template for {} v{}", messageType, normalizedVersion);
      try {
        String templateFileContent = getResourceInHl7Folder(versionSpecificPath);
        return parseMessageModel(templateFileContent, messageType);
      } catch (Exception e) {
        LOGGER.warn("Failed to load version-specific template {}, falling back to default. Reason: {}",
            versionSpecificPath, e.getMessage());
      }
    }

    // Fallback to default template
    LOGGER.debug("Loading default template for {}", messageType);
    return getMessageModel(messageType);
  }

  /**
   * Parses a message model from template file content.
   *
   * @param templateFileContent The YAML content
   * @param messageType         The message type name
   * @return HL7MessageModel
   */
  private HL7MessageModel parseMessageModel(String templateFileContent, String messageType) {
    if (StringUtils.isBlank(templateFileContent)) {
      return null;
    }

    try {
      JsonNode parent = ObjectMapperUtil.getYAMLInstance().readTree(templateFileContent);
      Preconditions.checkState(parent != null, "Parent node from template file cannot be null");

      JsonNode resourceNodes = parent.get("resources");
      Preconditions.checkState(resourceNodes != null && !resourceNodes.isEmpty(),
          "List of resources from Parent node from template file cannot be null or empty");

      List<HL7FHIRResourceTemplateAttributes> templateAttributes = ObjectMapperUtil.getYAMLInstance().convertValue(
          resourceNodes, new TypeReference<List<HL7FHIRResourceTemplateAttributes>>() {
          });

      List<HL7FHIRResourceTemplate> templates = new ArrayList<>();
      templateAttributes.forEach(t -> templates.add(new HL7FHIRResourceTemplate(t)));

      Preconditions.checkState(templateAttributes != null && !templateAttributes.isEmpty(),
          "TemplateAttributes generated from template file cannot be null or empty");

      return new HL7MessageModel(messageType, templates);

    } catch (IOException e) {
      throw new IllegalArgumentException("Error encountered in processing the template for " + messageType, e);
    }
  }

}
