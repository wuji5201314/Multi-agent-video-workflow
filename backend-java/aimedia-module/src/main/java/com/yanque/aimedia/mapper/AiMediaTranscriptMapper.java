package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaTranscriptUtteranceRes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMediaTranscriptMapper {
    /** 保存火山 ASR 的任务 ID，供 ASR_QUERY 步骤查询。 */
    int insert(AiMediaTranscriptEntity transcript);

    /** 根据处理任务查询对应的转写记录。 */
    AiMediaTranscriptEntity selectByTaskId(@Param("taskId") Long taskId);

    /** 查询一个视频最新生成的转写记录。 */
    AiMediaTranscriptEntity selectLatestByVideoId(@Param("videoId") Long videoId);

    /** 统计视频最新转写记录包含的分句数量。 */
    long countUtterances(@Param("videoId") Long videoId);

    /** 从视频最新转写记录中分页读取分句，避免一次返回完整 ASR 原始 JSON。 */
    List<AiMediaTranscriptUtteranceRes> selectUtterancePage(@Param("videoId") Long videoId,
                                                            @Param("offset") int offset,
                                                            @Param("limit") int limit);

    /** 保存 ASR 原始结果，供后续大模型整理步骤使用。 */
    int saveAsrResult(@Param("taskId") Long taskId,
                      @Param("fullText") String fullText,
                      @Param("utterancesJson") String utterancesJson);

    /** 保存大模型整理后的全文和对话。 */
    int saveRefinedResult(@Param("taskId") Long taskId,
                          @Param("fullText") String fullText,
                          @Param("utterancesJson") String utterancesJson);
}
