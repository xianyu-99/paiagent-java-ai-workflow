package com.paiagent.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class DocumentParsingServiceTest {

    private final DocumentParsingService documentParsingService = new DocumentParsingService(emptyParsingAgentProvider());

    @Test
    void parseMarkdownByHeading() {
        ParsedDocument document = documentParsingService.parseText(
                "faq.md",
                "# 登录问题\n用户可以使用账号密码登录。\n\n# RAG 问题\n知识库会按标题切片。"
        );

        assertEquals("plain-text", document.parserType());
        assertEquals("text/markdown", document.contentType());
        assertEquals(2, document.segments().size());
        assertEquals("登录问题", document.segments().getFirst().sectionTitle());
        assertTrue(document.segments().get(1).text().contains("知识库会按标题切片"));
    }

    @Test
    void parseDocxByHeading() throws Exception {
        try (XWPFDocument docx = new XWPFDocument()) {
            XWPFParagraph heading = docx.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("安装说明");

            XWPFParagraph body = docx.createParagraph();
            body.createRun().setText("先启动 MySQL、Redis 和 Qdrant，再启动后端。");

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            docx.write(outputStream);

            ParsedDocument document = documentParsingService.parseFile(
                    "guide.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    outputStream.toByteArray()
            );

            assertEquals("poi-docx", document.parserType());
            assertEquals(1, document.segments().size());
            assertEquals("安装说明", document.segments().getFirst().sectionTitle());
            assertTrue(document.rawText().contains("先启动 MySQL"));
        }
    }

    @Test
    void parsePdfByPage() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            addPdfPage(pdf, "First page knowledge text");
            addPdfPage(pdf, "Second page knowledge text");

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            pdf.save(outputStream);

            ParsedDocument document = documentParsingService.parseFile(
                    "guide.pdf",
                    "application/pdf",
                    outputStream.toByteArray()
            );

            assertEquals("pdfbox", document.parserType());
            assertEquals(2, document.segments().size());
            assertEquals(1, document.segments().getFirst().pageNumber());
            assertEquals(2, document.segments().get(1).pageNumber());
            assertTrue(document.rawText().contains("Second page knowledge text"));
        }
    }

    @Test
    void parseJsonAsFormattedText() {
        ParsedDocument document = documentParsingService.parseFile(
                "faq.json",
                "application/json",
                "{\"question\":\"What is RAG?\",\"answer\":\"Retrieval augmented generation\"}".getBytes()
        );

        assertEquals("json", document.parserType());
        assertEquals("application/json", document.contentType());
        assertEquals(1, document.segments().size());
        assertTrue(document.rawText().contains("Retrieval augmented generation"));
    }

    @Test
    void detectLegacyDocContentType() {
        assertEquals("application/msword", documentParsingService.detectContentType("guide.doc", null));
    }

    private void addPdfPage(PDDocument pdf, String text) throws Exception {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream contentStream = new PDPageContentStream(pdf, page)) {
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(72, 720);
            contentStream.showText(text);
            contentStream.endText();
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ExternalParsingAgentClient> emptyParsingAgentProvider() {
        ObjectProvider<ExternalParsingAgentClient> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
