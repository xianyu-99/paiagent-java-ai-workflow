package com.paiagent.service.document;

/**
 * Interface representing the Server Agent for external document parsing (OCR, complex layouts).
 */
public interface ExternalParsingAgentClient {

    /**
     * Sends the byte content to the Server Agent for OCR/parsing.
     * 
     * @param fileName The name of the file being processed.
     * @param bytes The raw file bytes (e.g., scanned PDF, image).
     * @return The parsed structured text (usually Markdown).
     */
    String parseScannedDocument(String fileName, byte[] bytes) throws Exception;

}
