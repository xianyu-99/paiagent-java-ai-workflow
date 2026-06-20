package com.paiagent.service.document;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Data cleaning service that orchestrates text cleaning through a Python pipeline
 * with inline Java fallbacks when Python is unavailable.
 *
 * <p>Cleaning steps (run in order):
 * <ol>
 *   <li>HTML stripping — remove tags, preserve paragraph structure</li>
 *   <li>Deduplication — SimHash near-duplicate + exact substring dedup</li>
 *   <li>Quality filtering — min length, punctuation ratio, CJK ratio</li>
 *   <li>PII redaction — Chinese ID cards, phone numbers, emails</li>
 * </ol>
 */
@Service
public class DataCleaningService {

    private static final Logger log = LoggerFactory.getLogger(DataCleaningService.class);

    private static final long PYTHON_TIMEOUT_SECONDS = 60;
    private static final String PIPELINE_SCRIPT = "scripts/cleaning/pipeline.py";

    // Chinese ID card: 18 digits with area code, birth date, sequence, and checksum
    private static final Pattern CN_ID_CARD_PATTERN = Pattern.compile(
            "(?<!\\d)"
                    + "[1-9]\\d{5}"                         // area code
                    + "(?:19|20)\\d{2}"                     // year
                    + "(?:0[1-9]|1[0-2])"                   // month
                    + "(?:0[1-9]|[12]\\d|3[01])"            // day
                    + "\\d{3}"                              // sequence
                    + "[0-9Xx]"                             // check digit
                    + "(?!\\d)"
    );

    // Chinese phone numbers: 1[3-9]xxxxxxxxx
    private static final Pattern CN_PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
    );

    // Email addresses
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"
    );

    // ID card checksum weights (GB 11643-1999)
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECK_CHARS = "10X98765432";

    // --- Configuration ---
    @Value("${paiagent.rag.cleaning.enabled:true}")
    private boolean cleaningEnabled;

    @Value("${paiagent.rag.cleaning.python-path:python}")
    private String pythonPath;

    @Value("${paiagent.rag.cleaning.html-strip:true}")
    private boolean htmlStripEnabled;

    @Value("${paiagent.rag.cleaning.dedup:true}")
    private boolean dedupEnabled;

    @Value("${paiagent.rag.cleaning.dedup-threshold:0.85}")
    private double dedupThreshold;

    @Value("${paiagent.rag.cleaning.pii-redact:true}")
    private boolean piiRedactEnabled;

    @Value("${paiagent.rag.cleaning.min-segment-length:50}")
    private int minSegmentLength;

    /**
     * Clean raw text through the configured pipeline.
     *
     * @param rawText the raw text to clean
     * @param options optional override options (may be null for defaults)
     * @return CleaningResult containing cleaned text and statistics
     */
    public CleaningResult clean(String rawText, CleaningOptions options) {
        if (!cleaningEnabled) {
            return new CleaningResult(rawText, Map.of("skipped", true));
        }

        if (rawText == null || rawText.isBlank()) {
            return new CleaningResult("", Map.of("empty_input", true));
        }

        CleaningOptions opts = options != null ? options : CleaningOptions.defaults();

        // Try Python pipeline first, fall back to inline Java
        try {
            return cleanViaPython(rawText, opts);
        } catch (Exception e) {
            log.warn("Python cleaning pipeline failed ({}), falling back to inline Java cleaning", e.getMessage());
            log.debug("Python pipeline failure details", e);
            return cleanInline(rawText, opts);
        }
    }

    /**
     * Convenience overload without options.
     */
    public CleaningResult clean(String rawText) {
        return clean(rawText, null);
    }

    /**
     * Clean via external Python pipeline process.
     */
    private CleaningResult cleanViaPython(String rawText, CleaningOptions opts) throws IOException, InterruptedException {
        Path tempInput = null;
        Path tempOutput = null;
        try {
            tempInput = Files.createTempFile("paiagent_clean_in_", ".txt");
            tempOutput = Files.createTempFile("paiagent_clean_out_", ".json");
            Files.writeString(tempInput, rawText, StandardCharsets.UTF_8);

            List<String> steps = buildStepList(opts);
            List<String> command = new ArrayList<>();
            command.add(pythonPath);
            command.add(PIPELINE_SCRIPT);
            command.add("--input");
            command.add(tempInput.toAbsolutePath().toString());
            command.add("--output");
            command.add(tempOutput.toAbsolutePath().toString());
            command.add("--format");
            command.add("json");
            command.add("--steps");
            command.add(String.join(",", steps));
            command.add("--dedup-threshold");
            command.add(String.valueOf(dedupThreshold));
            command.add("--min-length");
            command.add(String.valueOf(minSegmentLength));
            command.add("--pii-mode");
            command.add("redact");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(PYTHON_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Python pipeline timed out after " + PYTHON_TIMEOUT_SECONDS + "s");
            }

            if (process.exitValue() != 0) {
                String stderr = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Python pipeline exited with code " + process.exitValue() + ": " + stderr);
            }

            String jsonOutput = Files.readString(tempOutput, StandardCharsets.UTF_8);
            JSONObject result = JSON.parseObject(jsonOutput);
            String cleanedText = result.getString("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = result.getObject("stats", Map.class);

            return new CleaningResult(cleanedText, stats);
        } finally {
            if (tempInput != null) {
                try { Files.deleteIfExists(tempInput); } catch (IOException ignored) { }
            }
            if (tempOutput != null) {
                try { Files.deleteIfExists(tempOutput); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Inline Java cleaning — used when Python is unavailable.
     */
    CleaningResult cleanInline(String rawText, CleaningOptions opts) {
        String text = rawText;
        Map<String, Object> allStats = new LinkedHashMap<>();
        int originalLength = text.length();

        // Step 1: Normalize line endings (replaces the old standalone normalize)
        text = normalize(text);

        // Step 2: HTML stripping
        if (opts.htmlStrip()) {
            text = cleanHtmlInline(text);
        }

        // Step 3: Deduplication
        if (opts.dedup()) {
            DedupResult dedupResult = deduplicateInline(text, opts.dedupThreshold());
            text = dedupResult.text();
            allStats.put("dedup", Map.of(
                    "original_count", dedupResult.originalCount(),
                    "unique_count", dedupResult.uniqueCount(),
                    "removed_count", dedupResult.removedCount(),
                    "removal_pct", dedupResult.removalPct()
            ));
        }

        // Step 4: Quality filtering
        if (opts.filter()) {
            FilterResult filterResult = filterNoiseInline(text, opts.minLength());
            text = filterResult.text();
            allStats.put("filter", Map.of(
                    "original_chars", filterResult.originalChars(),
                    "filtered_chars", filterResult.filteredChars(),
                    "removed_lines", filterResult.removedLines(),
                    "kept_lines", filterResult.keptLines(),
                    "filter_pct", filterResult.filterPct()
            ));
        }

        // Step 5: PII redaction
        if (opts.piiRedact()) {
            PiiResult piiResult = redactPiiInline(text);
            text = piiResult.text();
            allStats.put("redact", Map.of(
                    "id_cards_redacted", piiResult.idCardsRedacted(),
                    "phone_numbers_redacted", piiResult.phoneNumbersRedacted(),
                    "emails_redacted", piiResult.emailsRedacted(),
                    "total_redacted", piiResult.totalRedacted()
            ));
        }

        allStats.put("overall", Map.of(
                "input_chars", originalLength,
                "output_chars", text.length(),
                "reduction_pct", originalLength > 0
                        ? Math.round((originalLength - text.length()) / (double) originalLength * 1000.0) / 10.0
                        : 0.0
        ));

        return new CleaningResult(text, allStats);
    }

    /**
     * Normalize: CRLF → LF, CR → LF, control chars to space, trim.
     */
    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .trim();
    }

    // ── Inline HTML cleaning (regex-based, no Jsoup dependency) ──────────

    private String cleanHtmlInline(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Remove script, style, nav, footer, header, aside, noscript blocks
        String text = html.replaceAll(
                "(?is)<(script|style|nav|footer|header|aside|noscript)\\b[^>]*>.*?</\\1>",
                ""
        );

        // Remove HTML comments
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // Replace block-level tags with newlines
        text = text.replaceAll("(?i)</?(?:p|h[1-6]|li|div|section|article|br|tr|table|ul|ol|dl|dt|dd|blockquote|pre|hr)\\b[^>]*>",
                "\n");

        // Remove remaining HTML tags
        text = text.replaceAll("<[^>]+>", "");

        // Decode common HTML entities
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");

        // Collapse whitespace
        text = collapseWhitespace(text);

        return text.trim();
    }

    // ── Inline deduplication ─────────────────────────────────────────────

    private DedupResult deduplicateInline(String text, double threshold) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> filtered = new ArrayList<>();
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                filtered.add(trimmed);
            }
        }

        if (filtered.size() <= 1) {
            return new DedupResult(text, filtered.size(), filtered.size(), 0, 0.0);
        }

        int originalCount = filtered.size();

        // Step 1: Exact dedup
        Map<String, Integer> seen = new HashMap<>();
        List<String> exactKept = new ArrayList<>();
        List<Integer> exactKeptIdx = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            String para = filtered.get(i);
            String normalized = para.toLowerCase().replaceAll("\\s+", " ");
            if (normalized.length() < minSegmentLength) {
                exactKept.add(para);
                exactKeptIdx.add(i);
            } else if (!seen.containsKey(normalized)) {
                seen.put(normalized, i);
                exactKept.add(para);
                exactKeptIdx.add(i);
            }
        }

        // Step 2: Near-duplicate via Java SimHash
        if (exactKept.size() > 1) {
            long[] hashes = new long[exactKept.size()];
            for (int i = 0; i < exactKept.size(); i++) {
                hashes[i] = simHash64(exactKept.get(i));
            }

            int hammingThreshold = (int) Math.round((1.0 - threshold) * 64);
            List<String> nearKept = new ArrayList<>();
            for (int i = 0; i < exactKept.size(); i++) {
                boolean isDup = false;
                for (int j = 0; j < nearKept.size(); j++) {
                    if (hammingDistance(hashes[i], hashes[exactKeptIdx.get(j)]) <= hammingThreshold) {
                        isDup = true;
                        break;
                    }
                }
                if (!isDup) {
                    nearKept.add(exactKept.get(i));
                }
            }

            String result = String.join("\n\n", nearKept);
            int removed = originalCount - nearKept.size();
            double pct = originalCount > 0 ? Math.round(removed * 1000.0 / originalCount) / 10.0 : 0.0;
            return new DedupResult(result, originalCount, nearKept.size(), removed, pct);
        }

        String result = String.join("\n\n", exactKept);
        int removed = originalCount - exactKept.size();
        double pct = originalCount > 0 ? Math.round(removed * 1000.0 / originalCount) / 10.0 : 0.0;
        return new DedupResult(result, originalCount, exactKept.size(), removed, pct);
    }

    /**
     * Compute a 64-bit SimHash fingerprint for text.
     * Uses character bigram features — works for both Chinese and English.
     */
    static long simHash64(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }

        // Normalize
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() < 4) {
            return murmur3_64(text);
        }

        // Extract features: character bigrams
        Map<String, Integer> features = new HashMap<>();
        for (int i = 0; i < text.length() - 1; i++) {
            String bigram = text.substring(i, i + 2);
            features.merge(bigram, 1, Integer::sum);
        }

        // Word bigrams for English text
        String[] words = text.split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            String wordBigram = "w_" + words[i] + "_" + words[i + 1];
            features.merge(wordBigram, 1, Integer::sum);
        }

        // Accumulate 64-bit vector
        int[] v = new int[64];
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            long hash = murmur3_64(entry.getKey());
            int weight = entry.getValue();
            for (int i = 0; i < 64; i++) {
                if (((hash >> i) & 1) == 1) {
                    v[i] += weight;
                } else {
                    v[i] -= weight;
                }
            }
        }

        // Produce fingerprint
        long fingerprint = 0L;
        for (int i = 0; i < 64; i++) {
            if (v[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    /**
     * Simplified MurmurHash3-like 64-bit hash.
     */
    static long murmur3_64(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        long h = (long) data.length * 0xC6A4A7935BD1E995L;

        int nblocks = data.length / 8;
        for (int i = 0; i < nblocks; i++) {
            long k = getLongLE(data, i * 8);
            k *= 0x87C37B91114253D5L;
            k = Long.rotateLeft(k, 31);
            k *= 0x4CF5AD432745937FL;
            h ^= k;
            h = Long.rotateLeft(h, 27);
            h = h * 5 + 0x52DCE729L;
        }

        int remaining = data.length % 8;
        if (remaining > 0) {
            long k = 0;
            for (int i = 0; i < remaining; i++) {
                k |= ((long) (data[nblocks * 8 + i] & 0xFF)) << (i * 8);
            }
            k *= 0x87C37B91114253D5L;
            k = Long.rotateLeft(k, 31);
            k *= 0x4CF5AD432745937FL;
            h ^= k;
        }

        h ^= data.length;
        h ^= h >>> 33;
        h *= 0xC6A4A7935BD1E995L;
        h ^= h >>> 33;

        return h;
    }

    private static long getLongLE(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (data[offset + i] & 0xFF)) << (i * 8);
        }
        return value;
    }

    static int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    // ── Inline quality filtering ─────────────────────────────────────────

    private FilterResult filterNoiseInline(String text, int minLength) {
        String[] lines = text.split("\n", -1);
        int originalLines = lines.length;
        List<String> kept = new ArrayList<>();
        int removed = 0;

        for (String line : lines) {
            String trimmed = line.strip();

            // Remove empty lines
            if (trimmed.isEmpty()) {
                removed++;
                continue;
            }

            // Skip lines too short (but keep likely headings)
            if (trimmed.length() < minLength) {
                if (looksLikeHeading(trimmed)) {
                    kept.add(line);
                } else {
                    removed++;
                }
                continue;
            }

            // Punctuation ratio check
            if (punctuationRatio(trimmed) > 0.60) {
                removed++;
                continue;
            }

            // Control character ratio check
            if (controlCharRatio(trimmed) > 0.10) {
                removed++;
                continue;
            }

            // CJK ratio check (only applies if CJK is present)
            double cjkRatio = cjkRatio(trimmed);
            if (cjkRatio > 0 && cjkRatio < 0.10) {
                removed++;
                continue;
            }

            kept.add(line);
        }

        String result = String.join("\n", kept).replaceAll("\\n{3,}", "\n\n");
        int originalChars = text.length();
        int filteredChars = result.length();
        double pct = originalChars > 0
                ? Math.round((originalChars - filteredChars) * 1000.0 / originalChars) / 10.0
                : 0.0;

        return new FilterResult(result, originalChars, filteredChars, removed, kept.size(), pct);
    }

    private double punctuationRatio(String text) {
        if (text.isEmpty()) return 0;
        int punct = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int type = Character.getType(ch);
            if (type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL) {
                punct++;
            } else if ("，。！？；：\u201c\u201d\u2018\u2019（）【】《》…—～·".indexOf(ch) >= 0) {
                punct++;
            }
        }
        return (double) punct / text.length();
    }

    private double controlCharRatio(String text) {
        if (text.isEmpty()) return 0;
        int control = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 32 && ch != '\t' && ch != '\n' && ch != '\r') {
                control++;
            } else if (ch >= 0x7F && ch <= 0x9C) {
                control++;
            }
        }
        return (double) control / text.length();
    }

    private double cjkRatio(String text) {
        if (text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.codePointAt(i))) {
                cjk++;
            }
        }
        return (double) cjk / text.length();
    }

    /**
     * Check if a code point is in the CJK range.
     * Mirrors KnowledgeBaseService.isCjk for consistency.
     */
    static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0x2F800 && codePoint <= 0x2FA1F)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0x3040 && codePoint <= 0x309F)
                || (codePoint >= 0x30A0 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }

    private boolean looksLikeHeading(String text) {
        if (text == null || text.isEmpty()) return false;
        // Markdown heading
        if (text.matches("^#{1,6}\\s.*")) return true;
        // Short text with Chinese heading patterns
        if (text.length() <= 30 && text.matches(".*[第第].*[章节].*")) return true;
        if (text.length() <= 30 && text.matches(".*[一二三四五六七八九十]+[、．.].*")) return true;
        // Short text ending with colon
        if (text.length() <= 40 && (text.endsWith("：") || text.endsWith(":") || text.endsWith("?"))) return true;
        return false;
    }

    // ── Inline PII redaction ─────────────────────────────────────────────

    private PiiResult redactPiiInline(String text) {
        int idCardsRedacted = 0;
        int phonesRedacted = 0;
        int emailsRedacted = 0;

        // Redact ID card numbers (with checksum validation)
        Matcher idMatcher = CN_ID_CARD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (idMatcher.find()) {
            String idNumber = idMatcher.group().toUpperCase();
            if (validateCnIdChecksum(idNumber)) {
                idMatcher.appendReplacement(sb, "[REDACTED]");
                idCardsRedacted++;
            } else {
                idMatcher.appendReplacement(sb, Matcher.quoteReplacement(idMatcher.group()));
            }
        }
        idMatcher.appendTail(sb);
        text = sb.toString();

        // Redact phone numbers
        Matcher phoneMatcher = CN_PHONE_PATTERN.matcher(text);
        sb = new StringBuffer();
        while (phoneMatcher.find()) {
            phoneMatcher.appendReplacement(sb, "[REDACTED]");
            phonesRedacted++;
        }
        phoneMatcher.appendTail(sb);
        text = sb.toString();

        // Redact emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
        sb = new StringBuffer();
        while (emailMatcher.find()) {
            emailMatcher.appendReplacement(sb, "[REDACTED]");
            emailsRedacted++;
        }
        emailMatcher.appendTail(sb);
        text = sb.toString();

        return new PiiResult(text, idCardsRedacted, phonesRedacted, emailsRedacted,
                idCardsRedacted + phonesRedacted + emailsRedacted);
    }

    /**
     * Validate Chinese 18-digit ID card checksum using GB 11643-1999 algorithm.
     */
    static boolean validateCnIdChecksum(String idNumber) {
        if (idNumber == null || idNumber.length() != 18) {
            return false;
        }

        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = idNumber.charAt(i);
            if (!Character.isDigit(ch)) {
                return false;
            }
            sum += Character.digit(ch, 10) * ID_WEIGHTS[i];
        }

        char expectedCheck = ID_CHECK_CHARS.charAt(sum % 11);
        char actualCheck = Character.toUpperCase(idNumber.charAt(17));

        return expectedCheck == actualCheck;
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private String collapseWhitespace(String text) {
        // Collapse >2 consecutive newlines to 2
        text = text.replaceAll("\\n{3,}", "\n\n");
        // Trim whitespace per line
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (sb.length() > 0 || !trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    private List<String> buildStepList(CleaningOptions opts) {
        List<String> steps = new ArrayList<>();
        if (opts.htmlStrip()) steps.add("clean_html");
        if (opts.dedup()) steps.add("dedup");
        if (opts.filter()) steps.add("filter");
        if (opts.piiRedact()) steps.add("redact");
        return steps;
    }

    // ── Result types ─────────────────────────────────────────────────────

    /**
     * Result of a cleaning operation.
     */
    public record CleaningResult(String text, Map<String, Object> stats) {
        /**
         * @return true if cleaning actually changed the text
         */
        public boolean wasCleaned() {
            return stats != null && !stats.isEmpty() && !stats.containsKey("skipped")
                    && !stats.containsKey("empty_input");
        }

        /**
         * @return human-readable summary of cleaning statistics
         */
        public String summary() {
            if (stats == null || stats.isEmpty()) {
                return "No cleaning performed";
            }
            if (stats.containsKey("overall")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> overall = (Map<String, Object>) stats.get("overall");
                Number reduction = (Number) overall.getOrDefault("reduction_pct", 0);
                Number inputChars = (Number) overall.getOrDefault("input_chars", 0);
                Number outputChars = (Number) overall.getOrDefault("output_chars", 0);
                return String.format("Cleaned: %d → %d chars (%.1f%% reduction)",
                        inputChars.longValue(), outputChars.longValue(), reduction.doubleValue());
            }
            return "Cleaning completed";
        }
    }

    private record DedupResult(String text, int originalCount, int uniqueCount,
                               int removedCount, double removalPct) {}

    private record FilterResult(String text, int originalChars, int filteredChars,
                                int removedLines, int keptLines, double filterPct) {}

    private record PiiResult(String text, int idCardsRedacted, int phoneNumbersRedacted,
                             int emailsRedacted, int totalRedacted) {}
}
