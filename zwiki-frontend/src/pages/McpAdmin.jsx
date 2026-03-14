import React, { useCallback, useEffect, useState } from 'react';
import {
  App,
  Card,
  Button,
  Space,
  Input,
  Typography,
  Alert,
} from 'antd';
import {
  SyncOutlined,
  SaveOutlined,
  FormatPainterOutlined,
} from '@ant-design/icons';
import { motion } from 'framer-motion';
import { McpApi } from '../api/mcp';
import { getCenterPalette } from '../theme/centerTheme';

const { Text } = Typography;

/**
 * Validate and auto-correct MCP JSON format locally before saving.
 * Returns { valid: boolean, errors: string[], corrected: string }
 */
function validateAndCorrectMcpJson(jsonStr) {
  const errors = [];
  let parsed;
  try {
    parsed = JSON.parse(jsonStr);
  } catch (e) {
    return { valid: false, errors: ['JSON 语法错误: ' + e.message], corrected: null };
  }

  if (!parsed.mcpServers || typeof parsed.mcpServers !== 'object') {
    errors.push('缺少 "mcpServers" 字段或格式错误');
    parsed.mcpServers = {};
  }

  const correctedServers = {};
  for (const [name, cfg] of Object.entries(parsed.mcpServers)) {
    if (!cfg || typeof cfg !== 'object') {
      errors.push(`服务 "${name}" 配置无效，已跳过`);
      continue;
    }

    const corrected = {};

    // Determine transport type
    let transportType = cfg.type || cfg.transport;
    if (!transportType) {
      if (cfg.command) {
        transportType = 'stdio';
        errors.push(`服务 "${name}" 未指定 type，已根据 command 字段自动设为 "stdio"`);
      } else {
        transportType = 'streamableHttp';
        errors.push(`服务 "${name}" 未指定 type，已自动设为 "streamableHttp"`);
      }
    }
    // Normalize type names
    const lower = transportType.toLowerCase();
    if (lower.includes('sse') || lower === 'httpsse' || lower === 'http_sse') {
      corrected.type = 'sse';
    } else if (lower.includes('stdio')) {
      corrected.type = 'stdio';
    } else if (lower.includes('streamable') || lower.includes('http')) {
      corrected.type = 'streamableHttp';
    } else {
      corrected.type = transportType;
    }

    // Copy description
    if (cfg.description) corrected.description = cfg.description;

    // Normalize enabled/isActive → isActive
    if (cfg.isActive !== undefined) {
      corrected.isActive = Boolean(cfg.isActive);
    } else if (cfg.enabled !== undefined) {
      corrected.isActive = Boolean(cfg.enabled);
      errors.push(`服务 "${name}" 使用了 "enabled"，已自动转换为 "isActive"`);
    } else {
      corrected.isActive = true;
    }

    // Copy name (display name)
    corrected.name = cfg.name || name;

    // Handle transport-specific fields
    if (corrected.type === 'stdio') {
      if (cfg.command) corrected.command = cfg.command;
      else errors.push(`服务 "${name}" 是 stdio 类型但缺少 command 字段`);
      
      corrected.args = Array.isArray(cfg.args) ? cfg.args : [];
      if (cfg.env && typeof cfg.env === 'object') corrected.env = cfg.env;
    } else {
      // HTTP-based: baseUrl + headers
      const url = cfg.baseUrl || cfg.url;
      if (url) {
        corrected.baseUrl = url;
        if (cfg.url && !cfg.baseUrl) {
          errors.push(`服务 "${name}" 使用了 "url"，已自动转换为 "baseUrl"`);
        }
      } else {
        errors.push(`服务 "${name}" 缺少 baseUrl`);
      }
      if (cfg.headers && typeof cfg.headers === 'object') {
        corrected.headers = cfg.headers;
      }
    }

    // Copy timeout
    if (cfg.timeout !== undefined) corrected.timeout = Number(cfg.timeout) || 300;

    correctedServers[name] = corrected;
  }

  const correctedJson = JSON.stringify({ mcpServers: correctedServers }, null, 2);
  return { valid: errors.length === 0, errors, corrected: correctedJson };
}

const McpAdmin = ({ darkMode = false }) => {
  const { message } = App.useApp();
  const palette = getCenterPalette(darkMode);
  const [configJson, setConfigJson] = useState('');
  const [configLoading, setConfigLoading] = useState(false);
  const [configSaving, setConfigSaving] = useState(false);
  const [configDirty, setConfigDirty] = useState(false);
  const [validationErrors, setValidationErrors] = useState([]);

  const fetchConfig = useCallback(async () => {
    setConfigLoading(true);
    setValidationErrors([]);
    try {
      const resp = await McpApi.getConfig();
      if (resp.code === 200) {
        const raw = resp.data || '{\n  "mcpServers": {}\n}';
        try {
          setConfigJson(JSON.stringify(JSON.parse(raw), null, 2));
        } catch {
          setConfigJson(raw);
        }
        setConfigDirty(false);
      } else {
        message.error(resp.msg || resp.message || '获取配置失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '获取配置失败');
    } finally {
      setConfigLoading(false);
    }
  }, [message]);

  // Real-time validation on config change
  useEffect(() => {
    if (!configJson.trim()) {
      setValidationErrors([]);
      return;
    }
    const { errors } = validateAndCorrectMcpJson(configJson);
    setValidationErrors(errors);
  }, [configJson]);

  const handleAutoFormat = () => {
    const { corrected, errors } = validateAndCorrectMcpJson(configJson);
    if (corrected) {
      setConfigJson(corrected);
      setConfigDirty(true);
      if (errors.length > 0) {
        message.info(`已自动修正 ${errors.length} 个格式问题`);
      } else {
        message.success('格式已规范化');
      }
    } else {
      message.error('JSON 语法错误，无法自动修正');
    }
  };

  useEffect(() => {
    fetchConfig();
  }, [fetchConfig]);

  const saveConfig = async () => {
    try {
      JSON.parse(configJson);
    } catch {
      message.error('JSON 格式错误，请检查语法');
      return;
    }
    setConfigSaving(true);
    try {
      const resp = await McpApi.saveConfig(configJson);
      if (resp.code === 200) {
        message.success('配置已保存并生效');
        setConfigDirty(false);
      } else {
        message.error(resp.msg || resp.message || '保存失败');
      }
    } catch (e) {
      message.error(e?.normalized?.message || '保存失败');
    } finally {
      setConfigSaving(false);
    }
  };

  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.4 }}>
      <Card
        title={
          <span>
            MCP 管理 <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal' }}>mcp.json</Text>
            {configDirty ? <Text type="warning" style={{ fontSize: 13, fontWeight: 'normal' }}> (未保存)</Text> : null}
          </span>
        }
        extra={
          <Space>
            <Button icon={<SyncOutlined />} onClick={fetchConfig} loading={configLoading}>
              刷新
            </Button>
            <Button icon={<FormatPainterOutlined />} onClick={handleAutoFormat}>
              自动修正格式
            </Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={saveConfig}
              loading={configSaving}
              disabled={!configDirty}
            >
              保存
            </Button>
          </Space>
        }
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          编辑下方 JSON 配置来管理 MCP Server，保存后自动规范化格式并立即生效。支持的字段：type (sse/stdio/streamableHttp)、baseUrl、headers、isActive、name、description、command、args、env、timeout。
        </Text>
        {validationErrors.length > 0 && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="配置格式提示"
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {validationErrors.slice(0, 5).map((err, idx) => (
                  <li key={idx} style={{ fontSize: 12 }}>{err}</li>
                ))}
                {validationErrors.length > 5 && (
                  <li style={{ fontSize: 12, color: palette.textMuted }}>还有 {validationErrors.length - 5} 个提示...</li>
                )}
              </ul>
            }
          />
        )}
        <Input.TextArea
          value={configJson}
          onChange={(e) => {
            setConfigJson(e.target.value);
            setConfigDirty(true);
          }}
          autoSize={{ minRows: 16, maxRows: 36 }}
          style={{
            fontFamily: 'Consolas, "Courier New", monospace',
            fontSize: 14,
            lineHeight: '1.6',
            backgroundColor: '#1e1e1e',
            color: '#d4d4d4',
            border: '1px solid #333',
            borderRadius: 6,
            padding: '12px 16px',
          }}
          placeholder={'{\n  "mcpServers": {\n    "server-name": {\n      "url": "https://...",\n      "transport": "streamableHttp",\n      "headers": {},\n      "enabled": true,\n      "timeout": 120\n    }\n  }\n}'}
        />
      </Card>
    </motion.div>
  );
};

export default McpAdmin;
