package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.Result;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgeBaseResponse;
import com.paiagent.dto.KnowledgeChunkResponse;
import com.paiagent.dto.KnowledgeDocumentResponse;
import com.paiagent.dto.KnowledgeImportTaskResponse;
import com.paiagent.dto.KnowledgeReindexResponse;
import com.paiagent.dto.KnowledgeUploadRequest;
import com.paiagent.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Tag(name = "RAG 知识库接口")
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "查询知识库列表")
    @GetMapping
    public Result<List<KnowledgeBaseResponse>> listKnowledgeBases(HttpServletRequest request) {
        return Result.success(knowledgeBaseService.listKnowledgeBases(
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public Result<KnowledgeBaseResponse> createKnowledgeBase(@Valid @RequestBody KnowledgeBaseRequest body,
                                                             HttpServletRequest request) {
        return Result.success(knowledgeBaseService.createKnowledgeBase(body, AuthContext.getUserId(request)));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id, HttpServletRequest request) {
        knowledgeBaseService.deleteKnowledgeBase(id, AuthContext.getUserId(request), AuthContext.isAdmin(request));
        return Result.success();
    }

    @Operation(summary = "上传纯文本文档")
    @PostMapping("/{id}/documents")
    public Result<KnowledgeDocumentResponse> uploadTextDocument(@PathVariable Long id,
                                                                @Valid @RequestBody KnowledgeUploadRequest body,
                                                                HttpServletRequest request) {
        return Result.success(knowledgeBaseService.uploadTextDocument(
                id,
                body.getFileName(),
                body.getContent(),
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "同步上传并导入文件")
    @PostMapping(value = "/{id}/documents/file", consumes = "multipart/form-data")
    public Result<KnowledgeDocumentResponse> uploadFile(@PathVariable Long id,
                                                        @RequestPart("file") MultipartFile file,
                                                        HttpServletRequest request) throws IOException {
        return Result.success(knowledgeBaseService.uploadFileDocument(
                id,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "异步导入纯文本文档")
    @PostMapping("/{id}/documents/async")
    public Result<KnowledgeImportTaskResponse> startTextImport(@PathVariable Long id,
                                                               @Valid @RequestBody KnowledgeUploadRequest body,
                                                               HttpServletRequest request) {
        return Result.success(knowledgeBaseService.startTextImport(
                id,
                body.getFileName(),
                body.getContent(),
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "异步上传并导入文件")
    @PostMapping(value = "/{id}/documents/file/async", consumes = "multipart/form-data")
    public Result<KnowledgeImportTaskResponse> startFileImport(@PathVariable Long id,
                                                               @RequestPart("file") MultipartFile file,
                                                               HttpServletRequest request) throws IOException {
        return Result.success(knowledgeBaseService.startFileImport(
                id,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "查询知识导入任务")
    @GetMapping("/{id}/import-tasks/{taskId}")
    public Result<KnowledgeImportTaskResponse> getImportTask(@PathVariable Long id,
                                                             @PathVariable Long taskId,
                                                             HttpServletRequest request) {
        return Result.success(knowledgeBaseService.getImportTask(
                id,
                taskId,
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "查询最近知识导入任务")
    @GetMapping("/{id}/import-tasks")
    public Result<List<KnowledgeImportTaskResponse>> listImportTasks(@PathVariable Long id,
                                                                     HttpServletRequest request) {
        return Result.success(knowledgeBaseService.listImportTasks(
                id,
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "查询知识库文档切片")
    @GetMapping("/{id}/documents/{documentId}/chunks")
    public Result<List<KnowledgeChunkResponse>> listChunks(@PathVariable Long id,
                                                           @PathVariable Long documentId,
                                                           HttpServletRequest request) {
        return Result.success(knowledgeBaseService.listChunks(
                id,
                documentId,
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "查询知识库文档")
    @GetMapping("/{id}/documents")
    public Result<List<KnowledgeDocumentResponse>> listDocuments(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(knowledgeBaseService.listDocuments(
                id,
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }

    @Operation(summary = "重建知识库向量索引")
    @PostMapping("/{id}/reindex")
    public Result<KnowledgeReindexResponse> rebuildEmbeddings(@PathVariable Long id, HttpServletRequest request) {
        return Result.success(knowledgeBaseService.rebuildEmbeddings(
                id,
                AuthContext.getUserId(request),
                AuthContext.isAdmin(request)
        ));
    }
}
