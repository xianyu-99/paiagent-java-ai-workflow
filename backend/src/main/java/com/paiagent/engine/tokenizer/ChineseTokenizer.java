package com.paiagent.engine.tokenizer;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.WordDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ChineseTokenizer {

    private static final Logger log = LoggerFactory.getLogger(ChineseTokenizer.class);

    private static final String DEFAULT_DOMAIN_DICT = "classpath:domain-dict.txt";

    private final JiebaSegmenter segmenter;

    private final Set<String> stopWords;

    private final int maxSearchTerms;

    public ChineseTokenizer(
            @Value("${paiagent.rag.retrieval.tokenizer.stop-words:的,了,在,是,我,有,和,就,不,人,都,一,一个,上,也,很,到,说,要,去,你,会,着,没有,看,好,自己,这}") String stopWordsConfig,
            @Value("${paiagent.rag.retrieval.tokenizer.max-search-terms:16}") int maxSearchTerms) {
        this.segmenter = new JiebaSegmenter();
        this.stopWords = parseStopWords(stopWordsConfig);
        this.maxSearchTerms = maxSearchTerms;
        log.info("ChineseTokenizer initialized: stopWords={}, maxSearchTerms={}", stopWords.size(), maxSearchTerms);
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("domain-dict.txt")) {
                if (is != null) {
                    loadUserDict(is);
                    log.info("Loaded domain dictionary from classpath:domain-dict.txt");
                } else {
                    log.info("No domain dictionary found at classpath:domain-dict.txt, using jieba default");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load domain dictionary: {}", e.getMessage());
        }
    }

    /**
     * Tokenize Chinese text using jieba segmentation.
     * Handles mixed Chinese/English text naturally: jieba segments Chinese
     * and passes through alphabetic/numeric tokens.
     */
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        List<String> words = segmenter.sentenceProcess(text);
        List<String> tokens = new ArrayList<>();
        for (String word : words) {
            word = word.trim();
            if (word.isEmpty()) {
                continue;
            }
            // jieba may keep punctuation and single chars; filter noise
            if (word.length() == 1 && !isHanOrAlpha(word.codePointAt(0))) {
                continue;
            }
            tokens.add(word.toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /**
     * Extract search terms from a query: tokenize, remove stop words,
     * deduplicate (preserving order), and limit to maxSearchTerms.
     */
    public List<String> searchTerms(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        List<String> tokens = tokenize(query);
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (!isSearchable(token)) {
                continue;
            }
            if (seen.add(token)) {
                result.add(token);
                if (result.size() >= maxSearchTerms) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Load a custom dictionary file (one word per line, format: word [freq] [tag]).
     * Domain terms can be loaded at startup to improve segmentation accuracy.
     */
    public void loadUserDict(InputStream dictStream) {
        if (dictStream == null) {
            return;
        }
        try {
            Path tempFile = Files.createTempFile("jieba_user_dict_", ".txt");
            Files.copy(dictStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            WordDictionary.getInstance().init(tempFile);
            log.info("Loaded user dictionary from stream");
        } catch (Exception e) {
            log.error("Failed to load user dictionary", e);
        }
    }

    private boolean isSearchable(String token) {
        if (token.length() < 2) {
            return false;
        }
        if (stopWords.contains(token)) {
            return false;
        }
        // Skip pure punctuation
        return token.codePoints().anyMatch(Character::isLetterOrDigit)
                || token.codePoints().anyMatch(ChineseTokenizer::isHan);
    }

    private static boolean isHanOrAlpha(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static Set<String> parseStopWords(String config) {
        if (!StringUtils.hasText(config)) {
            return Collections.emptySet();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
