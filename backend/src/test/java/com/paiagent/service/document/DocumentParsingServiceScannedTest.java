package com.paiagent.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class DocumentParsingServiceScannedTest {

    private DocumentParsingService parsingService;
    private ExternalParsingAgentClient mockParsingAgentClient;

    @BeforeEach
    void setUp() {
        mockParsingAgentClient = Mockito.mock(ExternalParsingAgentClient.class);
        
        ObjectProvider<ExternalParsingAgentClient> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockParsingAgentClient);

        parsingService = new DocumentParsingService(provider);
    }

    @Test
    void testScannedPdfTriggersOcrFallback() throws Exception {
        // Create an empty PDF (simulating a scanned document with no extractable text)
        byte[] emptyPdfBytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            emptyPdfBytes = out.toByteArray();
        }

        // Mock the OCR Server Agent to return Markdown
        String mockOcrResult = "# OCR Result\nThis is a scanned document processed by the Agent.";
        when(mockParsingAgentClient.parseScannedDocument(anyString(), any(byte[].class))).thenReturn(mockOcrResult);

        // Execute parsing
        ParsedDocument parsedDoc = parsingService.parseFile("scanned.pdf", "application/pdf", emptyPdfBytes);

        // Verify fallback happened
        assertEquals("ocr-server-agent", parsedDoc.parserType());
        assertTrue(parsedDoc.rawText().contains("OCR Result"));
        assertEquals(1, parsedDoc.segments().size());
        assertEquals("OCR Result", parsedDoc.segments().getFirst().sectionTitle());
        Mockito.verify(mockParsingAgentClient, Mockito.times(1)).parseScannedDocument(anyString(), any(byte[].class));
    }
}
