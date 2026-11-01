package com.yanque.aimedia.enums;

import java.util.List;

/**
 * 当前阶段的离线处理步骤定义。
 *
 * 步骤顺序在 Java 中统一维护，创建任务时会把快照写入步骤表。
 * ASR 使用“提交任务 + 查询结果”两个步骤，避免异步识别长期占用工作线程。
 */
public enum MediaProcessStepCode {
    PREPARE_VIDEO(10),
    EXTRACT_AUDIO(20),
    ASR_SUBMIT(30),
    ASR_QUERY(40),
    TRANSCRIPT_REFINEMENT(50),
    MATERIAL_SEGMENT_GENERATION(60),
    MATERIAL_CLIP_RENDER(70),
    MATERIAL_INDEX(80);

    private final int order;

    MediaProcessStepCode(int order) {
        this.order = order;
    }

    public int getOrder() {
        return order;
    }

    public static List<MediaProcessStepCode> initialSteps() {
        return List.of(
                PREPARE_VIDEO,
                EXTRACT_AUDIO,
                ASR_SUBMIT,
                ASR_QUERY,
                TRANSCRIPT_REFINEMENT,
                MATERIAL_SEGMENT_GENERATION,
                MATERIAL_CLIP_RENDER,
                MATERIAL_INDEX
        );
    }
}
