import http from './http';

/**
 * 获取用户的失败任务列表
 */
export const getFailedTasks = () => {
    return http.get('/auth/task-compensation/failed-tasks');
};

/**
 * 获取任务的未完成目录列表
 */
export const getIncompleteCatalogues = (taskId) => {
    return http.get(`/auth/task-compensation/incomplete-catalogues/${taskId}`);
};

/**
 * 重试失败的目录
 * @param {string} taskId 任务ID
 * @param {string[]} catalogueIds 要重试的目录ID列表（可选，为空则重试所有）
 */
export const retryIncompleteCatalogues = (taskId, catalogueIds = null) => {
    const body = catalogueIds ? { catalogueIds } : {};
    return http.post(`/auth/task-compensation/retry/${taskId}`, body);
};

export default {
    getFailedTasks,
    getIncompleteCatalogues,
    retryIncompleteCatalogues
};
