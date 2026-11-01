package com.yanque.aimedia.pojo.vo.resvo;

/** 创建任务后立即返回，具体转换工作在独立线程中执行。 */
public record AiMediaProcessTaskCreateRes(Long taskId, String status) {
}
