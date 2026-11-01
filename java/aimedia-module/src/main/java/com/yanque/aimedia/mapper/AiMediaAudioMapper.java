package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaAudioEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiMediaAudioMapper {
    /** 保存 FFmpeg 生成的音频产物。 */
    int insert(AiMediaAudioEntity audio);

    /** 根据处理任务查询音频产物。 */
    AiMediaAudioEntity selectByTaskId(@Param("taskId") Long taskId);

    /** 查询一个原始视频最新生成的音频。 */
    AiMediaAudioEntity selectLatestByVideoId(@Param("videoId") Long videoId);
}
