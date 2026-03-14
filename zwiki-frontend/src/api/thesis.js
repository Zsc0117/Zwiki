import api from './http';

export const ThesisApi = {
  generate: (taskId, thesisInfo = {}, docType, config) => {
    return api.post(`/thesis/interactive/generate/${taskId}`, thesisInfo, {
      timeout: 300000,
      params: docType ? { docType } : undefined,
      ...(config || {})
    });
  },
  progress: (taskId, docType, config) => {
    return api.get(`/thesis/interactive/progress/${taskId}`, {
      ...config,
      params: docType ? { docType } : undefined
    });
  },
  regenerate: (taskId, payload = {}, docType, config) => {
    return api.post(`/thesis/interactive/regenerate/${taskId}`, payload, {
      timeout: 600000,
      params: docType ? { docType } : undefined,
      ...(config || {})
    });
  },
  uploadTemplate: (taskId, formData, config) => {
    return api.post(`/thesis/interactive/upload-template/${taskId}`, formData, {
      ...config,
      headers: {
        'Content-Type': 'multipart/form-data',
        ...(config && config.headers ? config.headers : {})
      }
    });
  },
  fill: (taskId, templatePath, config) => {
    return api.post(`/thesis/interactive/fill/${taskId}`, null, {
      ...config,
      params: { templatePath }
    });
  },
  preview: (taskId, version, docType, config) => {
    return api.get(`/thesis/interactive/preview/${taskId}`, {
      timeout: 60000,
      params: {
        ...(version ? { version } : {}),
        ...(docType ? { docType } : {})
      },
      ...(config || {})
    });
  },
  feedback: (payload, config) => {
    return api.post('/thesis/interactive/feedback', payload, {
      timeout: 120000,
      ...(config || {})
    });
  },
  optimize: (taskId, feedbackId, config) => {
    return api.post(`/thesis/interactive/optimize/${feedbackId}`, null, {
      timeout: 300000,
      params: { taskId },
      ...(config || {})
    });
  },
  versions: (taskId, docType, config) => {
    return api.get(`/thesis/interactive/versions/${taskId}`, {
      timeout: 30000,
      params: docType ? { docType } : undefined,
      ...(config || {})
    });
  },
  confirm: (taskId, version, docType, config) => {
    return api.post(`/thesis/interactive/confirm/${taskId}`, null, {
      ...config,
      params: {
        version,
        ...(docType ? { docType } : {})
      }
    });
  },
  downloadDocx: (taskId, version, docType, thesisInfo = {}, config) => {
    return api.post(`/thesis/interactive/download-docx/${taskId}`, thesisInfo, {
      ...config,
      params: {
        ...(version ? { version } : {}),
        ...(docType ? { docType } : {})
      }
    });
  },
  downloadMarkdown: (taskId, version, docType, config) => {
    return api.get(`/thesis/interactive/download-markdown/${taskId}`, {
      ...config,
      params: {
        ...(version ? { version } : {}),
        ...(docType ? { docType } : {})
      }
    });
  }
};

export default ThesisApi;
