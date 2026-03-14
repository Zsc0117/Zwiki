import React, { useState, useEffect, useCallback } from 'react';
import {
  App,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Spin,
  Tooltip,
  Typography,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ArrowLeftOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { DiagramApi } from '../api/diagram';
import DrawioEditor from '../components/DrawioEditor';
import { resolveSvgMarkup, formatDiagramTime } from '../utils/diagram';

const { Title, Text } = Typography;

const DiagramPage = () => {
  const { taskId } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { message } = App.useApp();

  const [diagrams, setDiagrams] = useState([]);
  const [loading, setLoading] = useState(true);
  const [autoOpenHandled, setAutoOpenHandled] = useState(false);

  // Editor state
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingDiagram, setEditingDiagram] = useState(null); // null = new diagram
  const [editingXml, setEditingXml] = useState('');
  const [editingDrawioUrl, setEditingDrawioUrl] = useState(null); // URL mode for AI-generated diagrams

  // Rename modal
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameDiagram, setRenameDiagram] = useState(null);
  const [renameValue, setRenameValue] = useState('');

  // New diagram name modal
  const [newNameOpen, setNewNameOpen] = useState(false);
  const [newName, setNewName] = useState('');

  const fetchDiagrams = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await DiagramApi.list(taskId);
      if (res?.code === 200) {
        setDiagrams(res.data || []);
      } else {
        message.error(res?.msg || '获取图表列表失败');
      }
    } catch (e) {
      console.error('Failed to load diagrams:', e);
      message.error('获取图表列表失败');
    } finally {
      setLoading(false);
    }
  }, [taskId, message]);

  useEffect(() => {
    fetchDiagrams();
  }, [fetchDiagrams]);

  // Open existing diagram for edit
  const handleEditDiagram = useCallback(async (diagram) => {
    try {
      const res = await DiagramApi.getDetail(diagram.diagramId);
      if (res?.code === 200) {
        const d = res.data;
        setEditingDiagram(d);
        // If diagram has sourceUrl but no xmlData, open in URL mode
        if (d.sourceUrl && !d.xmlData) {
          setEditingDrawioUrl(d.sourceUrl);
          setEditingXml('');
        } else {
          setEditingDrawioUrl(null);
          setEditingXml(d.xmlData || '');
        }
        setEditorOpen(true);
      } else {
        message.error('获取图表数据失败');
      }
    } catch (e) {
      message.error('获取图表数据失败');
    }
  }, [message]);

  // Auto-open diagram from ?open= query param
  useEffect(() => {
    if (loading || autoOpenHandled) return;
    const openId = searchParams.get('open');
    if (!openId) return;
    setAutoOpenHandled(true);
    // Clear the query param from URL without navigation
    setSearchParams({}, { replace: true });
    const target = diagrams.find(d => d.diagramId === openId);
    if (target) {
      handleEditDiagram(target);
    } else {
      // Diagram might not be in list yet, try fetching directly
      (async () => {
        try {
          const res = await DiagramApi.getDetail(openId);
          if (res?.code === 200 && res.data) {
            const d = res.data;
            if (d.sourceUrl && !d.xmlData) {
              setEditingDiagram(d);
              setEditingDrawioUrl(d.sourceUrl);
              setEditingXml('');
              setEditorOpen(true);
            } else {
              setEditingDiagram(d);
              setEditingXml(d.xmlData || '');
              setEditingDrawioUrl(null);
              setEditorOpen(true);
            }
          }
        } catch (e) {
          console.warn('[DiagramPage] auto-open diagram failed:', e);
        }
      })();
    }
  }, [loading, autoOpenHandled, searchParams, diagrams, setSearchParams, handleEditDiagram]);

  // Create new diagram
  const handleCreateNew = () => {
    setNewName('');
    setNewNameOpen(true);
  };

  const handleConfirmCreate = async () => {
    if (!newName.trim()) {
      message.warning('请输入图表名称');
      return;
    }
    setNewNameOpen(false);
    setEditingDiagram(null);
    setEditingXml('');
    setEditorOpen(true);
  };

  // Save from editor
  const handleEditorSave = useCallback(async (xmlData, svgData, options = {}) => {
    // Only process when we have both xml and svg
    if (xmlData === null || svgData === null) return;
    const silent = options.autoCapture === true;

    try {
      if (editingDiagram) {
        // Update existing
        await DiagramApi.update(editingDiagram.diagramId, { xmlData, svgData });
        if (!silent) message.success('图表已保存');
      } else {
        // Create new
        const res = await DiagramApi.create({
          taskId,
          name: newName.trim() || '未命名图表',
          xmlData,
          svgData,
        });
        if (res?.code === 200) {
          if (!silent) message.success('图表已创建');
          // Set as editing so subsequent saves are updates
          setEditingDiagram({ diagramId: res.data.diagramId, name: res.data.name });
        } else {
          if (!silent) message.error(res?.msg || '创建失败');
        }
      }
      fetchDiagrams();
    } catch (e) {
      if (!silent) message.error('保存失败');
    }
  }, [editingDiagram, taskId, newName, fetchDiagrams, message]);

  const handleEditorClose = () => {
    setEditorOpen(false);
    setEditingDiagram(null);
    setEditingXml('');
    setEditingDrawioUrl(null);
    fetchDiagrams();
  };

  // Delete diagram
  const handleDelete = async (diagramId) => {
    try {
      const res = await DiagramApi.delete(diagramId);
      if (res?.code === 200) {
        message.success('图表已删除');
        fetchDiagrams();
      } else {
        message.error(res?.msg || '删除失败');
      }
    } catch (e) {
      message.error('删除失败');
    }
  };

  // Rename diagram
  const handleRename = async () => {
    if (!renameValue.trim()) {
      message.warning('名称不能为空');
      return;
    }
    try {
      const res = await DiagramApi.update(renameDiagram.diagramId, { name: renameValue.trim() });
      if (res?.code === 200) {
        message.success('重命名成功');
        setRenameOpen(false);
        fetchDiagrams();
      } else {
        message.error(res?.msg || '重命名失败');
      }
    } catch (e) {
      message.error('重命名失败');
    }
  };

  return (
    <div style={{ padding: '24px 32px', maxWidth: 1200, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 24, gap: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate(`/repo/${taskId}`)}
        >
          返回文档
        </Button>
        <Title level={3} style={{ margin: 0, flex: 1 }}>架构绘图</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateNew}>
          新建图表
        </Button>
      </div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      ) : diagrams.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Empty
            description="暂无图表，点击上方按钮创建"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        </div>
      ) : (
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
          gap: 18,
        }}>
          {diagrams.map((d) => {
            const previewSvg = resolveSvgMarkup(d.svgData);
            const hasSourceUrl = Boolean(String(d.sourceUrl || '').trim());

            return (
            <Card
              key={d.diagramId}
              hoverable
              onClick={() => handleEditDiagram(d)}
              style={{
                borderRadius: 14,
                overflow: 'hidden',
                border: '1px solid #e5e7eb',
                boxShadow: '0 4px 14px rgba(15, 23, 42, 0.06)',
              }}
              styles={{ body: { padding: 0 } }}
              cover={
                <div style={{
                  height: 188,
                  background: 'linear-gradient(160deg, #f8fbff 0%, #f0f9ff 100%)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  overflow: 'hidden',
                  borderBottom: '1px solid #eef2f7',
                  position: 'relative',
                }}>
                  {previewSvg ? (
                    <div
                      dangerouslySetInnerHTML={{ __html: previewSvg }}
                      style={{
                        width: '100%',
                        height: '100%',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: 12,
                        overflow: 'hidden',
                      }}
                    />
                  ) : hasSourceUrl ? (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, color: '#0369a1' }}>
                      <div style={{ fontSize: 38 }}>📐</div>
                      <div style={{ fontSize: 12, fontWeight: 600 }}>AI 生成图表（可打开编辑）</div>
                    </div>
                  ) : (
                    <div style={{ color: '#94a3b8', fontSize: 46 }}>📊</div>
                  )}
                </div>
              }
            >
              <div style={{ padding: '12px 14px' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Text strong ellipsis style={{ flex: 1, fontSize: 14 }}>
                    {d.name}
                  </Text>
                  <div style={{ display: 'flex', gap: 4 }} onClick={(e) => e.stopPropagation()}>
                    <Tooltip title="重命名">
                      <Button
                        type="text"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => {
                          setRenameDiagram(d);
                          setRenameValue(d.name);
                          setRenameOpen(true);
                        }}
                      />
                    </Tooltip>
                    <Popconfirm
                      title="确认删除此图表？"
                      onConfirm={() => handleDelete(d.diagramId)}
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                    >
                      <Tooltip title="删除">
                        <Button type="text" size="small" icon={<DeleteOutlined />} danger />
                      </Tooltip>
                    </Popconfirm>
                  </div>
                </div>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {formatDiagramTime(d.updatedAt || d.createdAt)}
                </Text>
              </div>
            </Card>
            );
          })}
        </div>
      )}

      {/* Draw.io Editor */}
      <DrawioEditor
        open={editorOpen}
        onClose={handleEditorClose}
        xmlData={editingXml}
        onSave={handleEditorSave}
        drawioUrl={editingDrawioUrl}
      />

      {/* New diagram name modal */}
      <Modal
        title="新建图表"
        open={newNameOpen}
        onOk={handleConfirmCreate}
        onCancel={() => setNewNameOpen(false)}
        okText="创建"
        cancelText="取消"
      >
        <Input
          placeholder="请输入图表名称"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onPressEnter={handleConfirmCreate}
          autoFocus
          maxLength={100}
        />
      </Modal>

      {/* Rename modal */}
      <Modal
        title="重命名图表"
        open={renameOpen}
        onOk={handleRename}
        onCancel={() => setRenameOpen(false)}
        okText="确定"
        cancelText="取消"
      >
        <Input
          placeholder="请输入新名称"
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          onPressEnter={handleRename}
          autoFocus
          maxLength={100}
        />
      </Modal>
    </div>
  );
};

export default DiagramPage;
