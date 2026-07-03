package com.paiagent.service.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock implementation of the ExternalParsingAgentClient.
 * Simulates a Server Agent processing scanned documents with OCR, including retry and rate limiting scenarios.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "paiagent.document.mock-ocr-enabled", havingValue = "true")
public class MockOcrParsingAgent implements ExternalParsingAgentClient {

    private final AtomicInteger attemptCounter = new AtomicInteger(0);

    @Override
    public String parseScannedDocument(String fileName, byte[] bytes) throws Exception {
        log.info("Sending document '{}' to OCR Server Agent (size: {} bytes)", fileName, bytes.length);

        int maxRetries = 5;
        int attempt = 0;
        long backoffDelay = 2000; // start with 2s

        while (attempt < maxRetries) {
            attempt++;
            int currentAttemptCount = attemptCounter.incrementAndGet();

            try {
                // Simulate network latency
                TimeUnit.MILLISECONDS.sleep(500);

                // Simulate 429 Too Many Requests or random network failure (fails ~50% of the time on first few attempts)
                if (currentAttemptCount % 2 == 0 && attempt < 3) {
                    throw new RuntimeException("HTTP 429: Too Many Requests from Server Agent");
                }

                log.info("OCR Server Agent successfully processed '{}' on attempt {}", fileName, attempt);
                
                // Return dummy parsed markdown for the scanned document
                return """
                        # Parsed Scanned Document: %s
                        
                        This is a simulated OCR result returned from the Server Agent.
                        
                        ## Key Findings
                        - The document is clearly a scanned image or non-selectable PDF.
                        - OCR has successfully extracted structured text.
                        - The semantic structure (headers, lists) was preserved using Vision-Language Models.
                        
                        | Data | Value |
                        |---|---|
                        | Status | Processed |
                        | Source | Scanned PDF Fallback |
                        
                        ## Conclusion
                        By handing this off to the Server Agent, the main backend avoids OOM and high CPU overhead, while ensuring high availability through retries.
                        """.formatted(fileName);

            } catch (Exception e) {
                log.warn("OCR Server Agent invocation failed on attempt {}/{}: {}", attempt, maxRetries, e.getMessage());
                if (attempt >= maxRetries) {
                    log.error("OCR Server Agent failed after {} attempts. Escalating to human review.", maxRetries);
                    throw new Exception("OCR Processing failed after max retries: " + e.getMessage(), e);
                }
                
                log.info("Applying exponential backoff, waiting {} ms before retry...", backoffDelay);
                TimeUnit.MILLISECONDS.sleep(backoffDelay);
                backoffDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s...
            }
        }
        
        throw new Exception("Unexpected termination of OCR retry loop.");
    }
}
