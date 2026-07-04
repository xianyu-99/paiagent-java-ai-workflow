package com.paiagent.engine.executor.impl;

import com.sun.net.httpserver.HttpServer;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TTSNodeExecutorTest {

    @Test
    void shouldResolveReferencedNodeOutputByNodeId() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "llm-1.output"
                ))
        ));

        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        nodeOutputs.put("llm-1", Map.of("output", "expected text"));
        nodeOutputs.put("other-llm", Map.of("output", "wrong text"));
        Map<String, Object> input = new HashMap<>();
        input.put("output", "flat fallback text");
        input.put("__nodeOutputs__", nodeOutputs);

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("expected text", resolved);
    }

    @Test
    void shouldResolveNestedReferencedNodeOutput() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "rag-1.payload.answer"
                ))
        ));

        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        nodeOutputs.put("rag-1", Map.of("payload", Map.of("answer", "nested text")));
        Map<String, Object> input = new HashMap<>();
        input.put("__nodeOutputs__", nodeOutputs);

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("nested text", resolved);
    }

    @Test
    void shouldResolveUserInputCompatibilityReference() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "input-default.user_input"
                ))
        ));

        Map<String, Object> input = new HashMap<>();
        input.put("input", "raw user text");

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("raw user text", resolved);
    }

    @Test
    void shouldUseFirstConfiguredInputWhenTextParamIsAbsent() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "script",
                        "type", "reference",
                        "referenceNode", "llm-1.output"
                ))
        ));

        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        nodeOutputs.put("llm-1", Map.of("output", "podcast script"));
        Map<String, Object> input = new HashMap<>();
        input.put("__nodeOutputs__", nodeOutputs);

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("podcast script", resolved);
    }

    @Test
    void shouldNormalizeSpeakerLabelsForSingleVoiceTts() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        String text = "{\"output\":\"A：大家好，欢迎来到程序员播客！B：今天聊 AI。A：[停顿]下期再见！\"}";

        String normalized = ReflectionTestUtils.invokeMethod(executor, "normalizeTtsInputText", text);

        assertEquals("大家好，欢迎来到程序员播客！\n今天聊 AI。\n下期再见！", normalized);
    }

    @Test
    void shouldMergeWavFilesUsingDataChunkOffset() throws Exception {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        byte[] first = buildWav(new byte[]{1, 2, 3}, true);
        byte[] second = buildWav(new byte[]{4, 5}, true);

        byte[] merged = ReflectionTestUtils.invokeMethod(executor, "mergeWavFiles", List.of(first, second));

        int dataOffset = findDataOffset(merged);
        int dataSize = readLittleEndianInt(merged, dataOffset - 4);
        assertEquals(5, dataSize);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Arrays.copyOfRange(merged, dataOffset, dataOffset + dataSize));
    }

    @Test
    void shouldRejectPrivateMimoApiUrlBeforeSendingRequest() {
        TTSNodeExecutor executor = new TTSNodeExecutor();

        assertThrows(IllegalArgumentException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        executor,
                        "synthesizeMimoChunk",
                        "http://127.0.0.1:8080/v1",
                        "test-key",
                        "mimo-v2-tts",
                        "mimo_default",
                        "hello"
                )
        );
    }

    @Test
    void shouldRejectPrivateAudioDownloadUrl() {
        TTSNodeExecutor executor = new TTSNodeExecutor();

        assertThrows(IllegalArgumentException.class, () ->
                ReflectionTestUtils.invokeMethod(
                        executor,
                        "downloadAudio",
                        "http://127.0.0.1:8080/audio.wav"
                )
        );
    }

    @Test
    void shouldRejectUnsafeAudioDownloadRedirectTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        try {
            TTSNodeExecutor executor = new TTSNodeExecutor();
            ReflectionTestUtils.setField(executor, "allowPrivateNetworkUrls", true);
            String redirectUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

            assertThrows(IllegalArgumentException.class, () ->
                    ReflectionTestUtils.invokeMethod(
                            executor,
                            "downloadAudio",
                            redirectUrl
                    )
            );
        } finally {
            server.stop(0);
        }
    }

    private byte[] buildWav(byte[] pcmData, boolean includeExtraChunk) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeInt(out, 0);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, 1);
        writeInt(out, 16000);
        writeInt(out, 32000);
        writeShort(out, 2);
        writeShort(out, 16);
        if (includeExtraChunk) {
            writeAscii(out, "JUNK");
            writeInt(out, 4);
            out.write(new byte[]{0, 0, 0, 0});
        }
        writeAscii(out, "data");
        writeInt(out, pcmData.length);
        out.write(pcmData);

        byte[] wav = out.toByteArray();
        writeInt(wav, 4, wav.length - 8);
        return wav;
    }

    private int findDataOffset(byte[] wav) {
        for (int i = 12; i + 8 <= wav.length; i++) {
            if (matchesAscii(wav, i, "data")) {
                return i + 8;
            }
        }
        throw new AssertionError("data chunk not found");
    }

    private boolean matchesAscii(byte[] data, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + expectedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedBytes.length; i++) {
            if (data[offset + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private void writeAscii(ByteArrayOutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
