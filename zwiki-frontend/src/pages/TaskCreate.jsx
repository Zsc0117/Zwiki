import React, { useState } from 'react';
import { 
  App,
  Card, 
  Form, 
  Input, 
  Button, 
  Radio, 
  Upload, 
  Space, 
  Divider 
} from 'antd';
import { 
  UploadOutlined, 
  SaveOutlined, 
  RollbackOutlined 
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { TaskApi } from '../api/task';

const TaskCreate = () => {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [sourceType, setSourceType] = useState('github');
  const [fileList, setFileList] = useState([]);

  // 提交表单
  const handleSubmit = async (values) => {
    setLoading(true);
    try {
      let response;
      
      if (sourceType === 'github') {
        // 从Git创建
        response = await TaskApi.createFromGit({
          ...values,
          sourceType: 'github'
        });
      } else {
        // 从Zip创建
        if (fileList.length === 0) {
          message.error('请上传ZIP文件');
          setLoading(false);
          return;
        }
        
        const formData = new FormData();
        formData.append('file', fileList[0].originFileObj);
        formData.append('projectName', values.projectName);
        formData.append('userName', values.userName);
        formData.append('sourceType', 'zip');
        
        response = await TaskApi.createFromZip(formData);
      }
      
      if (response.code === 200) {
        message.success(sourceType === 'zip' ? '任务已创建，文件正在后台解压处理中' : '创建任务成功');
        navigate('/center/tasks');
      } else {
        message.error(response.msg || '创建任务失败');
      }
    } catch (error) {
      console.error('创建任务出错:', error);
      message.error(error?.normalized?.message || '创建任务失败');
    } finally {
      setLoading(false);
    }
  };

  // 处理文件上传改变
  const handleFileChange = ({ fileList }) => {
    setFileList(fileList);
  };

  // 处理源类型改变
  const handleSourceTypeChange = (e) => {
    setSourceType(e.target.value);
    // 切换类型时重置部分表单字段
    form.setFieldsValue({
      projectUrl: undefined,
      branch: undefined,
      userName: '',
      password: undefined,
    });
    
    // 清除文件列表
    if (e.target.value === 'github') {
      setFileList([]);
    }
  };

  // 文件上传前校验
  const beforeUpload = (file) => {
    const isZip = file.type === 'application/zip' || 
                file.type === 'application/x-zip-compressed' ||
                file.name.endsWith('.zip');
    if (!isZip) {
      message.error('只能上传ZIP文件');
    }
    return isZip;
  };

  // 上传文件的属性
  const uploadProps = {
    onRemove: () => {
      setFileList([]);
    },
    beforeUpload: beforeUpload,
    onChange: handleFileChange,
    fileList,
    maxCount: 1,
    customRequest: ({ onSuccess }) => {
      setTimeout(() => {
        onSuccess("ok");
      }, 0);
    }
  };

  // 动画配置
  const containerVariants = {
    hidden: { opacity: 0, y: 50 },
    visible: { 
      opacity: 1, 
      y: 0,
      transition: { duration: 0.5, ease: "easeOut" }
    }
  };

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      <Card 
        title="创建新任务"
        extra={
          <Button 
            icon={<RollbackOutlined />} 
            onClick={() => navigate('/center/tasks')}
          >
            返回列表
          </Button>
        }
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{ sourceType: 'github' }}
        >
          <Form.Item
            name="projectName"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="请输入项目名称" />
          </Form.Item>
          
          <Form.Item
            name="sourceType"
            label="源类型"
          >
            <Radio.Group onChange={handleSourceTypeChange} value={sourceType}>
              <Radio value="github">GitHub链接</Radio>
              <Radio value="zip">ZIP文件</Radio>
            </Radio.Group>
          </Form.Item>
          
          <Divider />

          {sourceType === 'github' ? (
            <>
              <Form.Item
                name="projectUrl"
                label="项目URL"
                rules={[{ required: true, message: '请输入项目URL' }]}
              >
                <Input placeholder="请输入项目URL" />
              </Form.Item>

              <Form.Item
                name="branch"
                label="分支"
              >
                <Input placeholder="请输入分支名称，默认为master" />
              </Form.Item>
              
              <Form.Item
                name="userName"
                label="用户名"
              >
                <Input placeholder="请输入Git仓库用户名（如需要）" />
              </Form.Item>
              
              <Form.Item
                name="password"
                label="密码"
              >
                <Input.Password placeholder="请输入Git仓库密码（如需要）" />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                name="userName"
                label="用户名"
                rules={[{ required: true, message: '请输入用户名' }]}
              >
                <Input placeholder="请输入用户名" />
              </Form.Item>

              <Form.Item
                label="ZIP文件"
                required
              >
                <Upload {...uploadProps} accept=".zip">
                  <Button icon={<UploadOutlined />}>选择ZIP文件</Button>
                </Upload>
              </Form.Item>
            </>
          )}
          
          <Divider />
          
          <Form.Item>
            <Space>
              <Button 
                type="primary" 
                htmlType="submit" 
                loading={loading}
                icon={<SaveOutlined />}
              >
                创建任务
              </Button>
              <Button 
                onClick={() => form.resetFields()}
                disabled={loading}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </motion.div>
  );
};

export default TaskCreate; 