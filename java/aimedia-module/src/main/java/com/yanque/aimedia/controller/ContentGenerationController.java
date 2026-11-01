package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.common.AiMediaUserContext;
import com.yanque.aimedia.biz.AiContentMaterialTaskBiz;
import com.yanque.aimedia.biz.AiContentReviewTaskBiz;
import com.yanque.aimedia.biz.AiContentPreviewTaskBiz;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationContentReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationResumeReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationStartReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentMaterialTaskCreateReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentMaterialImageRegenerateReq;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentReviewTaskRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentPreviewTaskRes;
import com.yanque.aimedia.service.AiContentMaterialTaskService;
import com.yanque.aimedia.service.AiContentReviewTaskService;
import com.yanque.aimedia.service.AiContentPreviewTaskService;
import com.yanque.aimedia.service.ContentGenerationPythonClient;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内容生成 LangGraph 工作流的后台管理接口。 */
@RestController
@RequestMapping("/api/ai/content-generation")
public class ContentGenerationController {
    /** 转发 YanQue-AI 工作流请求。 */
    private final ContentGenerationPythonClient pythonClient;
    private final AiContentMaterialTaskBiz materialTaskBiz;
    private final AiContentMaterialTaskService materialTaskService;
    private final AiContentReviewTaskBiz reviewTaskBiz;
    private final AiContentReviewTaskService reviewTaskService;
    private final AiContentPreviewTaskBiz previewTaskBiz;
    private final AiContentPreviewTaskService previewTaskService;

    public ContentGenerationController(ContentGenerationPythonClient pythonClient,
                                       AiContentMaterialTaskBiz materialTaskBiz,
                                       AiContentMaterialTaskService materialTaskService,
                                       AiContentReviewTaskBiz reviewTaskBiz,
                                       AiContentReviewTaskService reviewTaskService,
                                       AiContentPreviewTaskBiz previewTaskBiz,
                                       AiContentPreviewTaskService previewTaskService) {
        this.pythonClient = pythonClient;
        this.materialTaskBiz = materialTaskBiz;
        this.materialTaskService = materialTaskService;
        this.reviewTaskBiz = reviewTaskBiz;
        this.reviewTaskService = reviewTaskService;
        this.previewTaskBiz = previewTaskBiz;
        this.previewTaskService = previewTaskService;
    }

    /** 启动完整内容生成流程，MVP 当前运行到选题人工确认节点。 */
    @PostMapping("/start")
    public ApiResponse<JSONObject> start(@Valid @RequestBody ContentGenerationStartReq req) {
        return ApiResponse.success(pythonClient.start(req));
    }

    /** 恢复指定内容生成会话，执行确认选题或重新生成。 */
    @PostMapping("/{conversationId}/resume")
    public ApiResponse<JSONObject> resume(
            @PathVariable String conversationId,
            @Valid @RequestBody ContentGenerationResumeReq req) {
        return ApiResponse.success(pythonClient.resume(conversationId, req));
    }

    /** 仅重试失败的资料检索节点，不重新生成或选择选题。 */
    @PostMapping("/{conversationId}/research/retry")
    public ApiResponse<JSONObject> retryResearch(@PathVariable String conversationId) {
        return ApiResponse.success(pythonClient.retryResearch(conversationId));
    }

    /** 首次生成文章，或保留选题和资料包按反馈重新创作。 */
    @PostMapping("/{conversationId}/content/generate")
    public ApiResponse<JSONObject> generateContent(
            @PathVariable String conversationId,
            @Valid @RequestBody ContentGenerationContentReq req) {
        return ApiResponse.success(pythonClient.generateContent(conversationId, req));
    }

    /** 创建素材匹配后台任务；相同会话和文章版本重复点击返回原任务。 */
    @PostMapping("/{conversationId}/materials/tasks")
    public ApiResponse<ContentMaterialTaskCreateRes> createMaterialTask(
            @PathVariable String conversationId,
            @Valid @RequestBody ContentMaterialTaskCreateReq req) {
        return ApiResponse.success(materialTaskBiz.createAndStart(
                conversationId, req.getContentRevision(), AiMediaUserContext.getUserId()));
    }

    /** 查询素材任务及当前已经保存的视频、图片结果。 */
    @GetMapping("/materials/tasks/{taskId}")
    public ApiResponse<ContentMaterialTaskRes> materialTask(@PathVariable Long taskId) {
        return ApiResponse.success(materialTaskService.detail(taskId));
    }

    /** 失败任务重新运行；部分失败时 Python 子图只处理未完成图片。 */
    @PostMapping("/materials/tasks/{taskId}/retry")
    public ApiResponse<ContentMaterialTaskCreateRes> retryMaterialTask(@PathVariable Long taskId) {
        return ApiResponse.success(materialTaskBiz.retryAndStart(taskId));
    }

    /** 按人工意见只重做一张图片，成功后自动作废并重跑旧审核。 */
    @PostMapping("/materials/tasks/{taskId}/items/{needId}/regenerate")
    public ApiResponse<ContentMaterialTaskRes> regenerateMaterialImage(
            @PathVariable Long taskId,
            @PathVariable String needId,
            @Valid @RequestBody ContentMaterialImageRegenerateReq req) {
        return ApiResponse.success(materialTaskBiz.regenerateImage(taskId, needId, req.getFeedback()));
    }

    /** 查询审核分数、摘要和问题明细。 */
    @GetMapping("/reviews/tasks/{taskId}")
    public ApiResponse<ContentReviewTaskRes> reviewTask(@PathVariable Long taskId) {
        return ApiResponse.success(reviewTaskService.detail(taskId));
    }

    /** 仅 FAILED 可以原地重试；REJECTED 必须先产生新文章版本。 */
    @PostMapping("/reviews/tasks/{taskId}/retry")
    public ApiResponse<ContentReviewTaskRes> retryReviewTask(@PathVariable Long taskId) {
        return ApiResponse.success(reviewTaskBiz.retryAndStart(taskId));
    }

    /** 人工确认审核通过；可覆盖 ERROR/REJECTED，问题明细仍保留用于审计。 */
    @PostMapping("/reviews/tasks/{taskId}/approve")
    public ApiResponse<ContentReviewTaskRes> approveReviewTask(@PathVariable Long taskId) {
        return ApiResponse.success(previewTaskBiz.approveAndStart(taskId, AiMediaUserContext.getUserId()));
    }

    /** 查询已审核内容的最终视频或图文轮播；URL 每次动态签名。 */
    @GetMapping("/previews/tasks/{taskId}")
    public ApiResponse<ContentPreviewTaskRes> previewTask(@PathVariable Long taskId) {
        return ApiResponse.success(previewTaskService.detail(taskId));
    }

    /** 仅重新渲染失败的最终成品，不重复审核，也不重复生成素材。 */
    @PostMapping("/previews/tasks/{taskId}/retry")
    public ApiResponse<ContentPreviewTaskRes> retryPreviewTask(@PathVariable Long taskId) {
        return ApiResponse.success(previewTaskBiz.retryAndStart(taskId));
    }
}
