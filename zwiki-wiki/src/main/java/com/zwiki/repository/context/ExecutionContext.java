package com.zwiki.repository.context;
import com.zwiki.domain.param.CreateTaskParams;
import com.zwiki.repository.entity.Task;
import lombok.Data;

/**
 *  执行上下文
 *
 * @author: CYM-pai
 * @date: 2026/01/28 17:49
 **/
@Data
public class ExecutionContext {
    private String taskId;

    private Task task;

    private CreateTaskParams createParams;

    private String localPath;
}
