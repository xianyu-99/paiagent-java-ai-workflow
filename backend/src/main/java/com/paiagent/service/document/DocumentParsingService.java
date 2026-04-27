package com.paiagent.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentParsingService {

    public ParsedDocument parseText(String fileName, String content) {
        String safeName = StringUtils.hasText(fileName) ? fileName.trim() : "manual.txt";
        String text = content == null ? "" : content;
        return parsePlainOrMarkdown(safeName, detectContentType(safeName, null), text);
    }

    public ParsedDocument parseFile(String fileName, String contentType, byte[] bytes) {
        String safeName = StringUtils.hasText(fileName) ? fileName.trim() : "untitled.txt";
        String lowerName = safeName.toLowerCase(Locale.ROOT);
        String detectedType = detectContentType(safeName, contentType);
        try {
            if (lowerName.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType)) {
                return parsePdf(safeName, detectedType, bytes);
            }
            if (lowerName.endsWith(".docx")
                    || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)) {
                return parseDocx(safeName, detectedType, bytes);
            }
            if (lowerName.endsWith(".doc")) {
                throw new IllegalArgumentException("暂不支持旧版 .doc，请另存为 .docx 后上传");
            }
            return parsePlainOrMarkdown(safeName, detectedType, new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文档解析失败: " + e.getMessage(), e);
        }
    }

    private ParsedDocument parsePdf(String fileName, String contentType, byte[] bytes) throws Exception {
        List<ParsedSegment> segments = new ArrayList<>();
        StringBuilder rawText = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                int start = rawText.length();
                rawText.append(text).append("\n\n");
                segments.add(new ParsedSegment(
                        text,
                        fileName,
                        contentType,
                        "Page " + page,
                        page,
                        start,
                        start + text.length()
                ));
            }
        }
        return new ParsedDocument(fileName, contentType, "pdfbox", rawText.toString().trim(), segments);
    }

    private ParsedDocument parseDocx(String fileName, String contentType, byte[] bytes) throws Exception {
        List<ParsedSegment> segments = new ArrayList<>();
        StringBuilder rawText = new StringBuilder();
        String currentTitle = null;
        StringBuilder currentText = new StringBuilder();
        int segmentStart = 0;

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = normalize(paragraph.getText());
                    if (!StringUtils.hasText(text)) {
                        continue;
                    }
                    if (isHeading(paragraph)) {
                        flushSegment(segments, fileName, contentType, currentTitle, null, segmentStart, currentText, rawText);
                        currentTitle = text;
                        segmentStart = rawText.length();
                        rawText.append(text).append("\n");
                        continue;
                    }
                    if (currentText.isEmpty()) {
                        segmentStart = rawText.length();
                    }
                    currentText.append(text).append("\n");
                    rawText.append(text).append("\n");
                } else if (element instanceof XWPFTable table) {
                    String text = normalize(table.getText());
                    if (!StringUtils.hasText(text)) {
                        continue;
                    }
                    if (currentText.isEmpty()) {
                        segmentStart = rawText.length();
                    }
                    currentText.append(text).append("\n");
                    rawText.append(text).append("\n");
                }
            }
        }
        flushSegment(segments, fileName, contentType, currentTitle, null, segmentStart, currentText, rawText);
        return new ParsedDocument(fileName, contentType, "poi-docx", rawText.toString().trim(), segments);
    }

    private ParsedDocument parsePlainOrMarkdown(String fileName, String contentType, String content) {
        String text = normalize(content);
        List<ParsedSegment> segments = new ArrayList<>();
        StringBuilder rawText = new StringBuilder();

        if (fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".markdown")) {
            String currentTitle = null;
            StringBuilder currentText = new StringBuilder();
            int segmentStart = 0;
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    flushSegment(segments, fileName, contentType, currentTitle, null, segmentStart, currentText, rawText);
                    currentTitle = trimmed.replaceFirst("^#+\\s*", "");
                    segmentStart = rawText.length();
                    rawText.append(trimmed).append("\n");
                    continue;
                }
                if (StringUtils.hasText(trimmed)) {
                    if (currentText.isEmpty()) {
                        segmentStart = rawText.length();
                    }
                    currentText.append(trimmed).append("\n");
                }
                rawText.append(line).append("\n");
            }
            flushSegment(segments, fileName, contentType, currentTitle, null, segmentStart, currentText, rawText);
        }

        if (segments.isEmpty() && StringUtils.hasText(text)) {
            segments.add(new ParsedSegment(text, fileName, contentType, null, null, 0, text.length()));
            rawText.append(text);
        }

        return new ParsedDocument(fileName, contentType, "plain-text", rawText.toString().trim(), segments);
    }

    private void flushSegment(List<ParsedSegment> segments,
                              String fileName,
                              String contentType,
                              String sectionTitle,
                              Integer pageNumber,
                              int startOffset,
                              StringBuilder currentText,
                              StringBuilder rawText) {
        String text = normalize(currentText.toString());
        if (StringUtils.hasText(text)) {
            segments.add(new ParsedSegment(
                    text,
                    fileName,
                    contentType,
                    sectionTitle,
                    pageNumber,
                    startOffset,
                    Math.min(rawText.length(), startOffset + text.length())
            ));
        }
        currentText.setLength(0);
    }

    private boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return false;
        }
        String normalized = style.toLowerCase(Locale.ROOT);
        return normalized.startsWith("heading")
                || normalized.startsWith("title")
                || normalized.contains("标题");
    }

    public String detectContentType(String fileName, String contentType) {
        String safeName = StringUtils.hasText(fileName) ? fileName.trim() : "untitled.txt";
        if (StringUtils.hasText(contentType) && !"application/octet-stream".equalsIgnoreCase(contentType)) {
            return contentType;
        }
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .trim();
    }
}
