import api from './http';

export const DocChangeApi = {
  /**
   * 获取任务的变更历史（分页）
   */
  getChanges(taskId, page = 0, size = 20) {
    return api.get(`/wiki/doc-change/list/${taskId}`, { params: { page, size } });
  },

  /**
   * 获取指定章节的变更历史
   */
  getCatalogueChanges(taskId, catalogueId) {
    return api.get(`/wiki/doc-change/catalogue/${taskId}/${catalogueId}`);
  },

  /**
   * 获取任务的变更统计
   */
  getChangeStats(taskId) {
    return api.get(`/wiki/doc-change/stats/${taskId}`);
  },

  /**
   * 获取指定时间范围内的变更
   */
  getChangesByTimeRange(taskId, start, end) {
    return api.get(`/wiki/doc-change/range/${taskId}`, { params: { start, end } });
  },

  /**
   * 手动触发变更对比
   */
  manualCompare(taskId) {
    return api.post(`/wiki/doc-change/compare/${taskId}`);
  },

  /**
   * 获取单个变更详情
   */
  getChangeDetail(changeId) {
    return api.get(`/wiki/doc-change/detail/${changeId}`);
  },

  /**
   * 清理任务的变更历史
   */
  clearChanges(taskId) {
    return api.delete(`/wiki/doc-change/clear/${taskId}`);
  }
};
