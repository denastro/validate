// Copyright 2006-2017, by the California Institute of Technology.
// ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
// Any commercial use must be negotiated with the Office of Technology Transfer
// at the California Institute of Technology.
//
// This software is subject to U. S. export control laws and regulations
// (22 C.F.R. 120-130 and 15 C.F.R. 730-774). To the extent that the software
// is subject to U.S. export control laws and regulations, the recipient has
// the responsibility to obtain export licenses or other export authority as
// may be required before exporting such information to foreign countries or
// providing access to foreign nationals.
//
// $Id$
package gov.nasa.pds.tools.validate.rule.pds4;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import gov.nasa.pds.tools.label.ExceptionType;
import gov.nasa.pds.tools.util.DocumentsChecker;
import gov.nasa.pds.tools.util.DocumentUtil;
import gov.nasa.pds.tools.util.FileSizesUtil;
import gov.nasa.pds.tools.util.ImageUtil;
import gov.nasa.pds.tools.util.LabelParser;
import gov.nasa.pds.tools.util.MD5Checksum;
import gov.nasa.pds.tools.util.PDFUtil;
import gov.nasa.pds.tools.util.Utility;
import gov.nasa.pds.tools.util.XMLExtractor;
import gov.nasa.pds.tools.validate.ProblemDefinition;
import gov.nasa.pds.tools.validate.ProblemType;
import gov.nasa.pds.tools.validate.ValidationProblem;
import gov.nasa.pds.tools.validate.ValidationTarget;
import gov.nasa.pds.tools.validate.rule.AbstractValidationRule;
import gov.nasa.pds.tools.validate.rule.ValidationTest;
import net.sf.saxon.om.DocumentInfo;
import net.sf.saxon.tree.tiny.TinyNodeImpl;

/**
 * Implements a rule to validate file references found in a label.
 * 
 * @author mcayanan
 *
 */
public class FileReferenceValidationRule extends AbstractValidationRule {

  private static final Logger LOG = LoggerFactory.getLogger(FileReferenceValidationRule.class);
  private static final Pattern LABEL_PATTERN = Pattern.compile(".*\\.xml", Pattern.CASE_INSENSITIVE);
  
  /**
   * XPath to the file references within a PDS4 data product label.
   */
  private final String FILE_OBJECTS_XPATH =
    "//*[starts-with(name(), 'File_Area')]/File | //Document_File";

  
  private Map<URL, String> checksumManifest;
  private PDFUtil pdfUtil = null;  // Define pdfUtil so we can reuse it for every call to validateFileReferences() function.
  private ImageUtil imageUtil = null;  // Define imageUtil so we can reuse it for every call to isJPEG() function.
  private DocumentsChecker documentsChecker = null; // Define documentsChecker so we can reuse it.
  private DocumentUtil documentUtil = null;

  public FileReferenceValidationRule() {
    checksumManifest = new HashMap<URL, String>();
  }

  @Override
  public boolean isApplicable(String location) {
    if (Utility.isDir(location) || !Utility.canRead(location)) {
      return false;
    }
    
    if(!getContext().containsKey(PDS4Context.LABEL_DOCUMENT)) {
      return false;
    }
    Matcher matcher = LABEL_PATTERN.matcher(FilenameUtils.getName(location));
    return matcher.matches();
  }

  @ValidationTest
  public void validateFileReferences() {
    URI uri = null;
    try {
      uri = getTarget().toURI();
    } catch (URISyntaxException e) {
      // Should never happen
    }
    if (getContext().getChecksumManifest() != null) {
      checksumManifest = getContext().getChecksumManifest();
    }
    Document label = getContext().getContextValue(PDS4Context.LABEL_DOCUMENT, Document.class);
    DOMSource source = new DOMSource(label);
    source.setSystemId(uri.toString());
    try {
      DocumentInfo xml = LabelParser.parse(source);
      LOG.debug("FileReferenceValidationRule:validateFileReferences:uri {}",uri);
      validate(xml);
    } catch (TransformerException te) {
      ProblemDefinition pd = new ProblemDefinition(ExceptionType.ERROR, 
          ProblemType.INTERNAL_ERROR, te.getMessage());
      if (te.getLocator() != null) {
        getListener().addProblem(new ValidationProblem(pd, 
            new ValidationTarget(getTarget()), 
            te.getLocator().getLineNumber(), 
            te.getLocator().getColumnNumber(), te.getMessage()));
      } else {
        getListener().addProblem(new ValidationProblem(pd, 
            new ValidationTarget(getTarget())));        
      }
    }
    LOG.debug("validateFileReferences:leaving:uri {}",uri);
  }

  private boolean checkGenericDocument(ValidationTarget target, URL urlRef, TinyNodeImpl fileObject, String fileName,
                                       URL parent, String directory, String documentStandardId, String documentType, List<ValidationProblem> problems, ProblemType problemType) {
      boolean passFlag = true;
      // Check for document file validity by getting the mime type associated with the file name.
      try {
          problems.addAll(handleGenericDocument(target, urlRef,
              fileObject, fileName, parent, directory, documentStandardId, problemType));
      } catch (Exception e) {
          ProblemDefinition def = new ProblemDefinition(ExceptionType.ERROR, ProblemType.INTERNAL_ERROR,
                  "Error occurred while processing " + documentType + " file content for "
                  + FilenameUtils.getName(urlRef.toString()) + ": "
                  + e.getMessage());
              problems.add(new ValidationProblem(def, target,
              fileObject.getLineNumber(), -1));
         passFlag = false;
      }
      LOG.debug("checkGenericDocument:fileName,passFlag {},{}",fileName,passFlag);
      return(passFlag);
  }
    
  private boolean validate(DocumentInfo xml) {
    boolean passFlag = true;
    List<ValidationProblem> problems = new ArrayList<ValidationProblem>();
    ValidationTarget target = new ValidationTarget(getTarget());
    try {
      // Perform checksum validation on the label itself
      problems.addAll(handleChecksum(target, new URL(xml.getSystemId())));
    } catch(Exception e) {
      ProblemDefinition pd = new ProblemDefinition(
          ExceptionType.ERROR, ProblemType.INTERNAL_ERROR, 
          "Error occurred while calculating checksum for "
          + FilenameUtils.getName(xml.getSystemId()) + ": "
          + e.getMessage());
      problems.add(new ValidationProblem(pd, target));
      passFlag = false;
    }
    try {
      LOG.debug("FileReferenceValidationRule:validate:building extractor");
      XMLExtractor extractor = new XMLExtractor(xml);
      LOG.debug("FileReferenceValidationRule:validate:extractor {}",extractor);
      URL labelUrl = new URL(xml.getSystemId());
      LOG.debug("FileReferenceValidationRule:validate:labelUrl {}",labelUrl);
      URL parent = labelUrl.toURI().getPath().endsWith("/") ?
          labelUrl.toURI().resolve("..").toURL() :
            labelUrl.toURI().resolve(".").toURL();
      LOG.debug("FileReferenceValidationRule:validate:parent {}",parent);
      try {
        // Search for "xml:base" attributes within the merged XML. This will
        // tell us if there are any xincludes.
        LOG.debug("FileReferenceValidationRule:validate:getValuesFromDoc(//@xml:base");
        List<String> xincludes = extractor.getValuesFromDoc("//@xml:base");
        LOG.debug("FileReferenceValidationRule:validate:xincludes.size {}",xincludes.size());
        for (String xinclude : xincludes) {
          URL xincludeUrl = new URL(parent, xinclude);
          LOG.debug("FileReferenceValidationRule:validate:xincludeUrl {}",xincludeUrl);
          try {
            xincludeUrl.openStream().close();
            // Check that the casing of the file reference matches the
            // casing of the file located on the file system.
            try {
              File fileRef = FileUtils.toFile(xincludeUrl);
              LOG.debug("FileReferenceValidationRule:validate:xincludeUrl,fileRef.getPath() {},{}",xincludeUrl,fileRef.getPath());
              if (fileRef != null &&
                  !fileRef.getCanonicalPath().endsWith(fileRef.getName())) {
                ProblemDefinition def = new ProblemDefinition(ExceptionType.WARNING, 
                    ProblemType.FILE_REFERENCE_CASE_MISMATCH, 
                    "File reference'" + fileRef.toString()
                      + "' exists but the case doesn't match");
                problems.add(new ValidationProblem(def, target));
              }
            } catch (IOException io) {
              ProblemDefinition def = new ProblemDefinition(ExceptionType.FATAL,
                  ProblemType.INTERNAL_ERROR,
                  "Error occurred while checking for the existence of the "
                  + "uri reference '" + xincludeUrl.toString() + "': "
                  + io.getMessage());
              problems.add(new ValidationProblem(def, target));
              passFlag = false;
            }
            try {
              // Perform checksum validation on the xincludes.
              problems.addAll(
                  handleChecksum(target, xincludeUrl)
                  );
            } catch (Exception e) {
              ProblemDefinition def = new ProblemDefinition(
                  ExceptionType.ERROR, ProblemType.INTERNAL_ERROR, 
                  "Error occurred while calculating checksum for "
                  + FilenameUtils.getName(xincludeUrl.toString()) + ": "
                  + e.getMessage());
              problems.add(new ValidationProblem(def, target));
              passFlag = false;
            }
          } catch (IOException io) {
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.ERROR, ProblemType.MISSING_REFERENCED_FILE,
                "URI reference does not exist: " + xincludeUrl.toString());
            problems.add(new ValidationProblem(def, target));
            passFlag = false;
          }
        }
        
 	    // issue_42: Add capability to ignore product-level validation
        if (!getContext().getSkipProductValidation()) {    	   
          List<TinyNodeImpl> fileObjects = extractor.getNodesFromDoc(
              FILE_OBJECTS_XPATH);
          LOG.debug("FileReferenceValidationRule:validate:fileObjects.size() {}",fileObjects.size());
          for (TinyNodeImpl fileObject : fileObjects) {
            String name = "";
            String checksum = "";
            String directory = "";
            String filesize  = "";
            String documentStandardId = null;
            List<TinyNodeImpl> children = new ArrayList<TinyNodeImpl>();
            try {
              children = extractor.getNodesFromItem("*", fileObject);
            } catch (XPathExpressionException xpe) {
              ProblemDefinition def = new ProblemDefinition(
                  ExceptionType.ERROR, ProblemType.INTERNAL_ERROR, 
                  "Problem occurred while trying to get all the children "
                      + "of the file object node: " + xpe.getMessage());
              problems.add(new ValidationProblem(def, target, 
                  fileObject.getLineNumber(), -1));
              passFlag = false;
              continue;
            }

            // Get file mapping for handling Document objects
            HashMap<String, String> fileMapping = new HashMap<String, String>(); 
            for (TinyNodeImpl child : children) {
              // Get the value of 'document_standard_id' tag.
              if ("document_standard_id".equals(child.getLocalPart())) {
                  documentStandardId = child.getStringValue(); 
                  fileMapping.put(name, documentStandardId);
              }
              if ("file_name".equals(child.getLocalPart())) {
                name = child.getStringValue();
                fileMapping.put(name, "");
                LOG.debug("FileReferenceValidationRule:validate:name {}",name);
              } else if ("md5_checksum".equals(child.getLocalPart())) {
                checksum = child.getStringValue();
                LOG.debug("FileReferenceValidationRule:validate:checksum {}",checksum);
              } else if ("directory_path_name".equals(child.getLocalPart())) {
                directory = child.getStringValue();
                LOG.debug("FileReferenceValidationRule:validate:directory {}",directory);
                LOG.debug("FileReferenceValidationRule:validate:getName [{}]",FilenameUtils.getName(directory));
                LOG.debug("FileReferenceValidationRule:validate:getFullPath [{}]",FilenameUtils.getFullPath(directory));

                // Check to make sure the directory name is NOT absolute.
                // https://github.com/NASA-PDS/validate/issues/349 validate allows absolute path in directory_path_name but shouldn't 

                Path p = Paths.get(FilenameUtils.getFullPath(directory));  // Use getFullPath() function to get the actual name to avoid sonatype-lift complains.
                if (p.isAbsolute()) {
                    LOG.error("The directory name {} for tag 'directory_path_name' cannot be absolute",directory);
                    ProblemDefinition def = new ProblemDefinition(
                        ExceptionType.ERROR, ProblemType.UNALLOWED_DIRECTORY_NAME, 
                        "The directory name " + directory + " for tag 'directory_path_name' cannot be absolute.");
                    problems.add(new ValidationProblem(def, target, 
                        fileObject.getLineNumber(), -1));
                    passFlag = false;
                }
              } else if ("file_size".equals(child.getLocalPart())) { // Fetch the file_size value from label.
                filesize = child.getStringValue();
                LOG.debug("FileReferenceValidationRule:validate:filesize {}",filesize);
              }
            } // for (TinyNodeImpl child : children)

            // Checks for File_Area information
            if (name.isEmpty()) {
              ProblemDefinition def = new ProblemDefinition(
                  ExceptionType.ERROR, ProblemType.UNKNOWN_VALUE, 
                  "Missing file_name in label.");
              problems.add(new ValidationProblem(def, target, 
                  fileObject.getLineNumber(), -1));
              passFlag = false;
            } else {
              URL urlRef = null;
              if (!directory.isEmpty()) {
                urlRef = new URL(parent, directory + File.separator + name);  // Switch from '/' to a system-independent file separator.
              } else {
                urlRef = new URL(parent, name);
              }
              try {
                urlRef.openStream().close();
                // Check that the casing of the file reference matches the
                // casing of the file located on the file system.
                try {
                  File fileRef = FileUtils.toFile(urlRef);
                  if (fileRef != null &&
                      !fileRef.getCanonicalPath().endsWith(fileRef.getName())) {
                    ProblemDefinition def = new ProblemDefinition(ExceptionType.WARNING, 
                        ProblemType.FILE_REFERENCE_CASE_MISMATCH, 
                        "File reference'" + fileRef.toString()
                          + "' exists but the case doesn't match");
                    problems.add(new ValidationProblem(def, target, 
                        fileObject.getLineNumber(), -1));
                  } else {
                      LOG.debug("FileReferenceValidationRule:validate:getTarget,name,urlRef {},{},{},",getTarget(),name,urlRef);
                 }
                } catch (IOException io) {
                  ProblemDefinition def = new ProblemDefinition(ExceptionType.FATAL,
                      ProblemType.INTERNAL_ERROR,
                      "Error occurred while checking for the existence of the "
                      + "uri reference '" + urlRef.toString() + "': "
                      + io.getMessage());
                  problems.add(new ValidationProblem(def, target, 
                      fileObject.getLineNumber(), -1));
                  passFlag = false;
                }
                try {
                  problems.addAll(handleChecksum(target, urlRef,
                      fileObject, checksum));
                } catch (Exception e) {
                  ProblemDefinition def = new ProblemDefinition(
                      ExceptionType.ERROR, ProblemType.INTERNAL_ERROR, 
                      "Error occurred while calculating checksum for "
                      + FilenameUtils.getName(urlRef.toString()) + ": "
                      + e.getMessage());
                  problems.add(new ValidationProblem(def, target, 
                      fileObject.getLineNumber(), -1));
                  passFlag = false;
                }

                // Check for provided file_size value and against the calculated size.
                try {
                  problems.addAll(handleFilesize(target, urlRef,
                      fileObject, filesize));
                } catch (Exception e) {
                  ProblemDefinition def = new ProblemDefinition(
                      ExceptionType.ERROR, ProblemType.INTERNAL_ERROR, 
                      "Error occurred while calculating filesize for "
                      + FilenameUtils.getName(urlRef.toString()) + ": "
                      + e.getMessage());
                  problems.add(new ValidationProblem(def, target, 
                      fileObject.getLineNumber(), -1));
                  passFlag = false;
                }

                // Check Document info
                
                for (Map.Entry<String,String> entry : fileMapping.entrySet()) {
                  String filename = entry.getKey();
                  String doctype = entry.getValue();

                  LOG.debug("FileReferenceValidationRule:validate:name,filename,doctype {},{},{}",name,filename,doctype);

                  // Note that we have to check for "PDF/A" and "PDF" as well.
                  if (doctype.equalsIgnoreCase("PDF/A") || doctype.equalsIgnoreCase("PDF")) {
                     // Check for PDF file validity.
                    try {
                      problems.addAll(handlePDF(target, urlRef,
                          fileObject, filename, parent, directory));
                    } catch (Exception e) {
                      ProblemDefinition def = new ProblemDefinition(
                          ExceptionType.ERROR, ProblemType.INTERNAL_ERROR,
                          "Error occurred while processing PDF file content for "
                          + FilenameUtils.getName(urlRef.toString()) + ": "
                          + e.getMessage());
                      problems.add(new ValidationProblem(def, target,
                          fileObject.getLineNumber(), -1));
                      passFlag = false;
                    }
                  } else if (doctype.equalsIgnoreCase("JPEG")) {
                    // Check for JPEG file validity.
                    try {
                      problems.addAll(handleJPEG(target, urlRef,
                          fileObject, filename, parent, directory));
                    } catch (Exception e) {
                      ProblemDefinition def = new ProblemDefinition(
                          ExceptionType.ERROR, ProblemType.INTERNAL_ERROR,
                          "Error occurred while processing JPEG file content for "
                          + FilenameUtils.getName(urlRef.toString()) + ": "
                          + e.getMessage());
                      problems.add(new ValidationProblem(def, target,
                          fileObject.getLineNumber(), -1));
                      passFlag = false;
                    }
                  } else if (doctype.equalsIgnoreCase("PNG")) {
                    // Check for PNG file validity.
                    try {
                      problems.addAll(handlePNG(target, urlRef,
                          fileObject, filename, parent, directory));
                    } catch (Exception e) {
                      ProblemDefinition def = new ProblemDefinition(
                          ExceptionType.ERROR, ProblemType.INTERNAL_ERROR,
                          "Error occurred while processing PNG file content for "
                          + FilenameUtils.getName(urlRef.toString()) + ": "
                          + e.getMessage());
                      problems.add(new ValidationProblem(def, target,
                          fileObject.getLineNumber(), -1));
                      passFlag = false;
                    }
                  } else if (!doctype.equalsIgnoreCase("UTF-8 Text") && !doctype.equalsIgnoreCase("7-Bit ASCII Text") && !doctype.equalsIgnoreCase("Rich Text")) {
                    LOG.debug("FileReferenceValidationRule:validate:urlRef,doctype {},{}",urlRef,doctype);
                    // Use the enum ProblemType based on a specific doctype and pass it to checkGenericDocument() function.
                    if (this.documentUtil == null) {
                        // Only instantiate this class once.
                        this.documentUtil = new DocumentUtil();
                    }
                    ProblemType problemType = this.documentUtil.getProblemType(doctype);
                    // Is is possible that there's no corresponding problemType.  Must check for null-ness before calling checkGenericDocument() function.
                    if (problemType == null) {
                        LOG.error("FileReferenceValidationRule:Cannot retrieve ProblemType from provided doctype {}",doctype);
                    } else {
                        passFlag = this.checkGenericDocument(target, urlRef, fileObject, filename, parent, directory, documentStandardId, doctype, problems, problemType);
                    }
                  } else if (doctype.equalsIgnoreCase("UTF-8 Text") || doctype.equalsIgnoreCase("7-Bit ASCII Text") || doctype.equalsIgnoreCase("Rich Text")) {
                    // If text, pass through as true.
                    continue;
                  }
                }
              } catch (IOException io) {
                ProblemDefinition def = new ProblemDefinition(
                    ExceptionType.ERROR, ProblemType.MISSING_REFERENCED_FILE,
                    "URI reference does not exist: " + urlRef.toString());
                problems.add(new ValidationProblem(def, target, 
                    fileObject.getLineNumber(), -1));
                passFlag = false;
              }
            }
        } // end if (!getContext().getSkipProductValidation()) {
        }
      } catch (XPathExpressionException xpe) {
        String message = "Error occurred while evaluating the following xpath expression '"
            + FILE_OBJECTS_XPATH + "': " + xpe.getMessage();
        ProblemDefinition def = new ProblemDefinition(
            ExceptionType.FATAL, ProblemType.INTERNAL_ERROR, message);
        problems.add(new ValidationProblem(def, target));
        passFlag = false;
      }
    } catch (Exception e) {
      // Print the stack trace for debugging and log the error message.
      e.printStackTrace();
      String message = "Error occurred while reading the uri: " + e.getMessage();
      LOG.error("validate:" + message);
      ProblemDefinition def = new ProblemDefinition(
          ExceptionType.FATAL, ProblemType.INTERNAL_ERROR, message);
      problems.add(new ValidationProblem(def, target));
      passFlag = false;
    }
    // Add the problems to the exception container.
    for (ValidationProblem problem : problems) {
      getListener().addProblem(problem);
    }
    LOG.debug("FileReferenceValidationRule:validate:DONE: passFlag {}",passFlag);
    return passFlag;
  }

  private List<ValidationProblem> handleChecksum(ValidationTarget target, URL fileRef)
  throws Exception {
    return handleChecksum(target, fileRef, null, null);
  }

  /**
   * Method to handle checksum processing.
   *
   * @param systemId The source (product label).
   * @param urlRef The uri of the file being processed.
   * @param fileObject The Node representation of the file object.
   * @param checksumInLabel Supplied checksum in the label. Can pass in
   * an empty value. If a null value is passed instead, it tells the
   * method to not do a check to see if the generated value matches
   * a supplied value. This would be in cases where a label's own
   * checksum is being validated.
   *
   * @return The resulting checksum. This will either be the generated value,
   * the value from the manifest file (if supplied), or the value from the
   * supplied value in the product label (if provided).
   *
   * @throws Exception If there was an error generating the checksum
   *  (if the flag was on)
   */
  private List<ValidationProblem> handleChecksum(ValidationTarget target, URL urlRef,
      TinyNodeImpl fileObject, String checksumInLabel)
  throws Exception {
    LOG.debug("handleChecksum:target,urlRef,checksumInLabel {},{},{}",target,urlRef,checksumInLabel);
    if (checksumManifest.isEmpty() && (checksumInLabel == null || checksumInLabel.isEmpty())) {
        String message = "No checksum found in the manifest for '"
            + urlRef + "'";
        LOG.debug("handleChecksum:"+message);
        return new ArrayList<ValidationProblem>();
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        String generatedChecksum = MD5Checksum.getMD5Checksum(urlRef);
        int lineNumber = -1;
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }
        if (!checksumManifest.isEmpty()) {
          if (checksumManifest.containsKey(urlRef)) {
            String suppliedChecksum = checksumManifest.get(urlRef);
            String message = "";
            ProblemType type = null;
            ExceptionType severity = null;

            LOG.debug("handleChecksum:urlRef " + urlRef + ",suppliedChecksum " + suppliedChecksum + ", generatedChecksum " + generatedChecksum);

            // Note that the checksum can be uppercase or lowercase so the comparison should be case insensitive.
            if (!suppliedChecksum.equalsIgnoreCase(generatedChecksum)) {
              message = "Generated checksum '" + generatedChecksum
                  + "' does not match supplied checksum '"
                  + suppliedChecksum + "' in the manifest for '"
                  + urlRef + "'";
              severity = ExceptionType.ERROR;
              type = ProblemType.CHECKSUM_MISMATCH;
            } else {
              message =  "Generated checksum '" + generatedChecksum
                  + "' matches the supplied checksum '" + suppliedChecksum
                  + "' in the manifest for '" + urlRef
                  + "'";
              severity = ExceptionType.INFO;
              type = ProblemType.CHECKSUM_MATCHES;
            }
            if (!message.isEmpty()) {
              ProblemDefinition def = new ProblemDefinition(severity, type, 
                  message);
              messages.add(new ValidationProblem(def, target, lineNumber, -1));
            }
          } else {
            String message = "No checksum found in the manifest for '"
                + urlRef + "'";
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.ERROR, ProblemType.MISSING_CHECKSUM, message);
            messages.add(new ValidationProblem(def, target, lineNumber, -1));
          }
        }
        if (checksumInLabel != null) {
          if (!checksumInLabel.isEmpty()) {
            String message = "";
            ProblemType type = null;
            ExceptionType severity = null;
            if (!generatedChecksum.equals(checksumInLabel)) {
              message = "Generated checksum '" + generatedChecksum
                  + "' does not match supplied checksum '"
                  + checksumInLabel + "' in the product label for '"
                  + urlRef + "'";
              type = ProblemType.CHECKSUM_MISMATCH;
              severity = ExceptionType.ERROR;
            } else {
              message = "Generated checksum '" + generatedChecksum
                  + "' matches the supplied checksum '" + checksumInLabel
                  + "' in the product label for '"
                  + urlRef + "'";
              type = ProblemType.CHECKSUM_MATCHES;
              severity = ExceptionType.INFO;
            }
            if (!message.isEmpty()) {
              ProblemDefinition def = new ProblemDefinition(severity, type, 
                  message);
              messages.add(new ValidationProblem(def, target, lineNumber, -1));
            }
          } else {
            String message = "No checksum to compare against in the product label "
                + "for '" + urlRef + "'";
            LOG.debug("handleChecksum:"+message);
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.INFO, ProblemType.MISSING_CHECKSUM_INFO, message);
            messages.add(new ValidationProblem(def, target, lineNumber, -1));
          }
        }
        return messages;
    }
  }

  /**
   * Method to handle filesize processing.
   *
   * @param target The source (product label).
   * @param urlRef The uri of the file being processed.
   * @param fileObject The Node representation of the file object.
   * @param filesizeInLabel Supplied filesize in the label. Can pass in
   * an empty value. If a null value is passed instead, it tells the
   * method to not do a check to see if the generated value matches
   * a supplied value. This would be in cases where a label's own
   * filesize is being validated.
   *
   * @return The resulting list of problems with filesize processing.
   *
   * @throws Exception If there was an error generating the filesize
   *  (if the flag was on)
   */
  private List<ValidationProblem> handleFilesize(ValidationTarget target, URL urlRef,
      TinyNodeImpl fileObject, String filesizeInLabel)
  throws Exception {
    LOG.debug("handleFilesize:target,urlRef,filesizeInLabel {},{},{}",target,urlRef,filesizeInLabel);
    if (filesizeInLabel == null || filesizeInLabel.isEmpty())  {
        String message = "No filesize to compare against in the product label "
                + "for '" + urlRef + "'";
        LOG.debug("handleFilesize:"+message);
        return new ArrayList<ValidationProblem>();  // Return an empty list.
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        long fileSizeAsInt = FileSizesUtil.getExternalFilesize(urlRef);  // Get the actual file size.
        String generatedFilesize = Long.toString(fileSizeAsInt); 
        int lineNumber = -1;
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }

        // For dealing with file size, there is no manifest to check like the checksum processing.
        // If the file size is provided, compare it with the actual file size generated.

        if (filesizeInLabel != null) {
          if (!filesizeInLabel.isEmpty()) {
            String message = "";
            ProblemType type = null;
            ExceptionType severity = null;
            if (!generatedFilesize.equals(filesizeInLabel)) {
              message = "Generated filesize '" + generatedFilesize
                  + "' does not match supplied filesize '"
                  + filesizeInLabel + "' in the product label for '"
                  + urlRef + "'";
              type = ProblemType.FILESIZE_MISMATCH;
              severity = ExceptionType.ERROR;
            } else {
              message = "Generated filesize '" + generatedFilesize
                  + "' matches the supplied filesize '" + filesizeInLabel
                  + "' in the product label for '"
                  + urlRef + "'";
              type = ProblemType.FILESIZE_MATCHES;
              severity = ExceptionType.INFO;
            }
            if (!message.isEmpty()) {
              ProblemDefinition def = new ProblemDefinition(severity, type, 
                  message);
              messages.add(new ValidationProblem(def, target, lineNumber, -1));
            }
          } else {
            // The file size is not provided, report for info only.
            String message = "No filesize to compare against in the product label "
                + "for '" + urlRef + "'";
            LOG.debug("handleFilesize:"+message);
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.INFO, ProblemType.MISSING_FILESIZE_INFO, message);
            messages.add(new ValidationProblem(def, target, lineNumber, -1));
          }
        }
        return messages;
    }
  }  

  private List<ValidationProblem> handlePDF(ValidationTarget target, URL fileRef,
                                            TinyNodeImpl fileObject, String pdfName, URL parent, String directory)
  throws Exception {
    LOG.debug("handlePDF:target,fileRef,pdfName {},{},{}",target,fileRef,pdfName);
    boolean pdfValidateFlag = false;
    if ((pdfName == null) || (fileObject == null)) {
        if (pdfName == null) {
           String message = "The file is not a PDF file, no need to perform check "
                   + "for '" + fileRef + "'";
           LOG.debug("handlePDF:"+message);
        }
        if (fileObject == null) {
            String message = "The fileObject is null, no need to perform check "
                + "for '" + fileRef + "'";
            LOG.debug("handlePDF:"+message);
        }
        return new ArrayList<ValidationProblem>();  // Return an empty list.
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        int lineNumber = -1;
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }

        if (this.pdfUtil == null) {
            // Save pdfUtil so it can be reused.
            this.pdfUtil = new PDFUtil(fileRef);
        }

        // First, let's check the filename even makes sense
        DocumentsChecker check = new DocumentsChecker();
        if (check.isMimeTypeCorrect(fileRef.toString(), "PDF/A")) {
          // The parent is also needed for validateFileStandardConformity function.
          pdfValidateFlag = this.pdfUtil.validateFileStandardConformity(pdfName, parent);
  
          // Report an error if the PDF file is not PDF/A compliant.
          if (!pdfValidateFlag) {
              URL urlRef = null;
              if (!directory.isEmpty()) {
                  urlRef = new URL(parent, directory + File.separator + pdfName);  // Make the separator OS agnostic.
              } else {
                  urlRef = new URL(parent, pdfName);
              }
              if (this.pdfUtil.getExternalErrorFilename() != null) {
                  // Only point to the error file if it exist.
                  LOG.error("handlePDF:"+urlRef.toString() + " is not valid PDF/A file or does not exist. Error file can be found at " + this.pdfUtil.getExternalErrorFilename());
              } else {
                  LOG.error("handlePDF:"+urlRef.toString() + " is not valid PDF/A file or does not exist.");
              }
              ProblemDefinition def = new ProblemDefinition(
                  ExceptionType.ERROR, ProblemType.NON_PDFA_FILE,
                  this.pdfUtil.getErrorMessage());
              messages.add(new ValidationProblem(def, target,
                  lineNumber, -1));
          }
        } else {
          String message = fileRef.toString() + " is an invalid PDF/A filename.";
          LOG.error("handlePDF:" + message);
          ProblemDefinition def = new ProblemDefinition(
              ExceptionType.ERROR, ProblemType.NON_PDFA_FILE,
              message);
          messages.add(new ValidationProblem(def, target,
              lineNumber, -1));
        }

        return messages;
    }
  }

  private List<ValidationProblem> handleJPEG(ValidationTarget target, URL fileRef,
                                            TinyNodeImpl fileObject, String jpegName, URL parent, String directory)
  throws Exception {
    LOG.debug("handleJPEGtarget,fileRef,jpegName {},{},{}",target,fileRef,jpegName);
    boolean jpegValidateFlag = false;
    if ((jpegName == null) || (fileObject == null)) {
        if (jpegName == null) {
           String message = "The file is not a JPEG file, no need to perform check "
                   + "for '" + fileRef + "'";
           LOG.debug("handleJPEG:"+message);
        }
        if (fileObject == null) {
            String message = "The fileObject is null, no need to perform check "
                + "for '" + fileRef + "'";
            LOG.debug("handleJPEG:"+message);
        }
        return new ArrayList<ValidationProblem>();  // Return an empty list.
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        int lineNumber = -1;
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }

        if (this.imageUtil == null) {
            // Save imageUtil so it can be reused.
            this.imageUtil = new ImageUtil(fileRef);
        }

        jpegValidateFlag = this.imageUtil.isJPEG(jpegName, parent);

        // Report a  warning if the JPEG file is not compliant.
        if (!jpegValidateFlag) {
            URL urlRef = null;
            if (!directory.isEmpty()) {
                urlRef = new URL(parent, directory + File.separator + jpegName);
            } else {
                urlRef = new URL(parent, jpegName);
            }
            LOG.error("handleJPEG:" + urlRef.toString() + " is not valid JPEG file");
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.WARNING, ProblemType.NON_JPEG_FILE,
                urlRef.toString() + " is not valid JPEG file");
            messages.add(new ValidationProblem(def, target,
                lineNumber, -1));
        }

        return messages;
    }
  }

  private List<ValidationProblem> handlePNG(ValidationTarget target, URL fileRef,
                                            TinyNodeImpl fileObject, String pngName, URL parent, String directory)
  throws Exception {
    LOG.debug("handlePNGtarget,fileRef,pngName {},{},{}",target,fileRef,pngName);
    boolean validateFlag = false;
    if ((pngName == null) || (fileObject == null)) {
        if (pngName == null) {
           String message = "The file is not a PNG file, no need to perform check "
                   + "for '" + fileRef + "'";
           LOG.debug("handlePNG:"+message);
        }
        if (fileObject == null) {
            String message = "The fileObject is null, no need to perform check "
                + "for '" + fileRef + "'";
            LOG.debug("handlePNG:"+message);
        }
        return new ArrayList<ValidationProblem>();  // Return an empty list.
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        int lineNumber = -1;
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }

        if (this.imageUtil == null) {
            // Save imageUtil so it can be reused.
            this.imageUtil = new ImageUtil(fileRef);
        }

        validateFlag = this.imageUtil.isPNG(pngName, parent);

        // Report a  warning if the PNG file is not compliant.
        if (!validateFlag) {
            URL urlRef = null;
            if (!directory.isEmpty()) {
                urlRef = new URL(parent, directory + File.separator + pngName);
            } else {
                urlRef = new URL(parent, pngName);
            }
            LOG.error("handlePNG:" + urlRef.toString() + " is not valid PNG file");
            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.WARNING, ProblemType.NON_PNG_FILE,
                urlRef.toString() + " is not valid PNG file");
            messages.add(new ValidationProblem(def, target,
                lineNumber, -1));
        }

        return messages;
    }
  }

  private List<ValidationProblem> handleGenericDocument(ValidationTarget target, URL fileRef,
                                            TinyNodeImpl fileObject, String textName, URL parent, String directory,
                                            String documentStandardId, ProblemType problemType)
  throws Exception {
    LOG.debug("handleGenericDocument:Target,fileRef,textName {},{},{}",target,fileRef,textName);
    boolean mimeTypeIsCorrectFlag = false;
    if ((textName == null) || (fileObject == null)) {
        if (textName == null) {
           String message = "The file is not a Text file, no need to perform check "
                   + "for '" + fileRef + "'";
           LOG.debug("handleGenericDocument:"+message);
        }
        if (fileObject == null) {
            String message = "The fileObject is null, no need to perform check "
                + "for '" + fileRef + "'";
            LOG.debug("handleGenericDocument:"+message);
        }
        return new ArrayList<ValidationProblem>();  // Return an empty list.
    } else {
        List<ValidationProblem> messages = new ArrayList<ValidationProblem>();
        int lineNumber = -1; 
        if (fileObject != null) {
          lineNumber = fileObject.getLineNumber();
        }

        if (this.documentsChecker == null) {
            // Save documentsChecker so it can be reused.
            this.documentsChecker = new DocumentsChecker();
        }


        // https://github.com/NASA-PDS/validate/issues/419 validate 2.2.0-SNAPSHOT warns about a pretty benign bundle + readme.txt
        // Due to the mime type may not be specified in the label, only perform the check if documentStandardId is not null.
        if (documentStandardId != null) {
            mimeTypeIsCorrectFlag = this.documentsChecker.isMimeTypeCorrect(textName,documentStandardId);
            LOG.debug("handleGenericDocument:textName,documentStandardId,mimeTypeIsCorrectFlag {},{},{}",textName,documentStandardId,mimeTypeIsCorrectFlag);
        } else {
            mimeTypeIsCorrectFlag = true; // Set to true even though the label does not have the documentStandardId set to anything.
        }

        // Report a  warning if the mime type is not correct
        if (!mimeTypeIsCorrectFlag) {
            URL urlRef = null;
            if (!directory.isEmpty()) {
                urlRef = new URL(parent, directory + File.separator + textName);
            } else {
                urlRef = new URL(parent, textName);
            }
            // Add the possible file extensions to error message.
            String errorMessage = urlRef.toString() + " with document standard '" + documentStandardId + "' is not correct.  Expected file suffix(es): " + this.documentsChecker.getPossibleFileExtensions(documentStandardId).toString();
            LOG.warn("handleGenericDocument:" + errorMessage);

            ProblemDefinition def = new ProblemDefinition(
                ExceptionType.WARNING, problemType,
                errorMessage);
            messages.add(new ValidationProblem(def, target,
                lineNumber, -1));
        }

        return messages;
    }   
  }

}
