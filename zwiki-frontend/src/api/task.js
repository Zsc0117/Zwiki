import api from './http';

// 任务API接口
export const TaskApi = {
  // 从Git创建任务
  createFromGit: (params, config) => {
    return api.post('/task/create/git', params, config);
  },

  // 仅通过仓库链接创建/复用任务
  createFromRepoUrl: (params, config) => {
    return api.post('/task/create/repo', params, config);
  },
  
  // 从Zip创建任务（大文件上传，超时设为10分钟）
  createFromZip: (formData, config) => {
    return api.post('/task/create/zip', formData, {
      ...config,
      timeout: 600000,
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    });
  },
  
  // 分页获取任务列表
  getTasksByPage: (params, config) => {
    return api.post('/task/listPage', params, config);
  },
  
  // 获取任务详情
  getTaskDetail: (taskId, config) => {
    return api.get(`/task/detail?taskId=${taskId}`, config);
  },
  
  // 更新任务
  updateTask: (task, config) => {
    return api.put('/task/update', task, config);
  },
  
  // 删除任务
  deleteTask: (taskId, config) => {
    return api.delete(`/task/delete?taskId=${taskId}`, config);
  },
  
  // 获取目录树
  getCatalogueTree: (taskId, config) => {
    return api.get(`/task/catalogue/tree?taskId=${taskId}`, config);
  },

  // 获取所有任务（用于 URL→taskId 映射）
  listAll: (config) => {
    return api.post('/task/listPage', { pageIndex: 1, pageSize: 200 }, config);
  }
};

export default TaskApi;