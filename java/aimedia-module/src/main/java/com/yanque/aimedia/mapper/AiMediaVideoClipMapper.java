package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 视频素材切片持久化接口。 */
@Mapper
public interface AiMediaVideoClipMapper {
    /** 保存一条最终入选的切片方案。 */
    int insert(AiMediaVideoClipEntity clip);

    /** 重新执行方案生成节点时，清理同一任务旧的方案。 */
    int deleteByTaskId(@Param("taskId") Long taskId);

    /** 查询一个原视频最新生成的最终素材方案，数量最多三条。 */
    List<AiMediaVideoClipEntity> selectByVideoId(@Param("videoId") Long videoId);

    /** 查询当前处理任务生成的全部切片方案。 */
    List<AiMediaVideoClipEntity> selectByTaskId(@Param("taskId") Long taskId);

    /** 根据素材 ID 查询独立切片文件信息。 */
    AiMediaVideoClipEntity selectById(@Param("id") Long id);

    /** 单条切片上传完成后保存 TOS Key，支持失败后从未完成片段继续。 */
    int saveRenderedFile(@Param("id") Long id,
                         @Param("objectKey") String objectKey,
                         @Param("fileSize") long fileSize);

    /** 跨原视频查询素材库。分页由 PageHelper 在服务层统一处理。 */
    List<AiMediaVideoClipListRow> selectPage(@Param("keyword") String keyword,
                                             @Param("clipType") String clipType,
                                             @Param("speaker") String speaker);
}
