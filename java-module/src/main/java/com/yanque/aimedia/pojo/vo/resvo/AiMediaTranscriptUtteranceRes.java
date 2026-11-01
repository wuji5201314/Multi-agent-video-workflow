package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

/** ASR 转写中的一个分句，用于分页展示对话内容。 */
@Data
public class AiMediaTranscriptUtteranceRes {
    /** 分句序号，从 1 开始。 */
    private Integer segmentNo;
    /** 分句开始时间，单位毫秒。 */
    private Long startTime;
    /** 分句结束时间，单位毫秒。 */
    private Long endTime;
    /** 分句文字。 */
    private String text;
    /** 火山 ASR 返回的说话人标识，未识别说话人时为空。 */
    private String speaker;
}
