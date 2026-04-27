package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.ForbiddenException;
import com.paiagent.common.Result;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgeBaseResponse;
import com.paiagent.dto.KnowledgeDocumentResponse;
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

import java.nio.charset.StandardCharsets;
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
        try {
            knowledgeBaseService.deleteKnowledgeBase(id, AuthContext.getUserId(request), AuthContext.isAdmin(request));
            return Result.success();
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "上传纯文本文档")
    @PostMapping("/{id}/documents")
    public Result<KnowledgeDocumentResponse> uploadTextDocument(@PathVariable Long id,
                                                                @Valid @RequestBody KnowledgeUploadRequest body,
                                                                HttpServletRequest request) {
        try {
            return Result.success(knowledgeBaseService.uploadTextDocument(
                    id,
                    body.getFileName(),
                    body.getContent(),
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "上传 txt / md 文件")
    @PostMapping(value = "/{id}/documents/file", consumes = "multipart/form-data")
    public Result<KnowledgeDocumentResponse> uploadFile(@PathVariable Long id,
                                                        @RequestPart("file") MultipartFile file,
                                                        HttpServletRequest request) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return Result.success(knowledgeBaseService.uploadTextDocument(
                    id,
                    file.getOriginalFilename(),
                    content,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "查询知识库文档")
    @GetMapping("/{id}/documents")
    public Result<List<KnowledgeDocumentResponse>> listDocuments(@PathVariable Long id, HttpServletRequest request) {
        try {
            return Result.success(knowledgeBaseService.listDocuments(
                    id,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
