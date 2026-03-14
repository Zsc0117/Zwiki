 -- Zwiki 数据库初始化脚本

-- 创建Wiki服务数据库
CREATE DATABASE IF NOT EXISTS zwiki DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE zwiki;

CREATE TABLE IF NOT EXISTS zwiki_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64),
    project_name VARCHAR(255) NOT NULL,
    project_url VARCHAR(512),
    user_name VARCHAR(64),
    source_type VARCHAR(32),
    project_path VARCHAR(512),
    status VARCHAR(32) DEFAULT 'pending',
    fail_reason TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zwiki_catalogue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    catalogue_id VARCHAR(64) NOT NULL UNIQUE,
    parent_catalogue_id VARCHAR(64),
    name VARCHAR(255),
    title VARCHAR(512),
    prompt TEXT,
    dependent_file TEXT,
    children TEXT,
    content LONGTEXT,
    status INT DEFAULT 0,
    fail_reason TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_user_task_id (user_id, task_id),
    INDEX idx_catalogue_id (catalogue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zwiki_memory_index (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repository_id VARCHAR(64) NOT NULL,
    content_type VARCHAR(32),
    content_name VARCHAR(255),
    content_hash VARCHAR(64),
    indexed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_repository_id (repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zwiki_chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    task_id VARCHAR(64),
    role VARCHAR(32),
    content TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_user_task (user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 论文生成/反馈相关表
CREATE TABLE IF NOT EXISTS zwiki_thesis_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    doc_type VARCHAR(32) NOT NULL DEFAULT 'thesis',
    version INT NOT NULL,
    section VARCHAR(255),
    feedback_type VARCHAR(20) NOT NULL,
    feedback_content TEXT,
    original_content LONGTEXT,
    optimized_content LONGTEXT,
    processed TINYINT(1) DEFAULT 0 NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    INDEX idx_task_id (task_id),
    INDEX idx_user_task_id (user_id, task_id),
    INDEX idx_version (version),
    INDEX idx_task_id_doc_type_version (task_id, doc_type, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zwiki_thesis_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    thesis_title VARCHAR(255),
    thesis_info TEXT,
    abstract_content TEXT,
    keywords VARCHAR(512),
    chapter_contents JSON,
    references_list TEXT,
    doc_type VARCHAR(32) NOT NULL DEFAULT 'thesis',
    version INT NOT NULL,
    full_content LONGTEXT,
    html_preview LONGTEXT,
    docx_file_path VARCHAR(512),
    markdown_file_path VARCHAR(512),
    pdf_file_path VARCHAR(512),
    status VARCHAR(20) NOT NULL,
    version_notes TEXT,
    template_path VARCHAR(512),
    inserted_diagrams TEXT,
    is_current TINYINT(1) DEFAULT 0 NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    INDEX idx_task_id_version (task_id, version),
    INDEX idx_user_task_id (user_id, task_id),
    INDEX idx_task_id_doc_type_version (task_id, doc_type, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zwiki_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    avatar_url VARCHAR(512),
    email VARCHAR(255),
    role VARCHAR(32) DEFAULT 'USER' COMMENT '角色: USER, ADMIN',
    status VARCHAR(32) DEFAULT 'active',
    catalogue_model VARCHAR(128) COMMENT '生成目录使用的模型',
    doc_gen_model VARCHAR(128) COMMENT '文档生成使用的模型',
    chat_model VARCHAR(128) COMMENT '项目问答(Ask AI)使用的模型',
    assistant_model VARCHAR(128) COMMENT '智能助手使用的模型',
    notification_enabled TINYINT(1) DEFAULT 1 COMMENT '是否开启通知: 0-关, 1-开',
    email_notification_enabled TINYINT(1) DEFAULT 0 COMMENT '是否开启邮件通知: 0-关, 1-开',
    preferences JSON COMMENT '用户偏好设置(扩展)',
    last_login_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_zwiki_user_user_id (user_id),
    INDEX idx_zwiki_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 为已有数据库添加 role 字段的 ALTER 语句（如表已存在）
-- ALTER TABLE zwiki_user ADD COLUMN role VARCHAR(32) DEFAULT 'USER' COMMENT '角色: USER, ADMIN' AFTER email;

-- 移除默认模型列（已废弃，场景模型直接走负载均衡兜底）
-- ALTER TABLE zwiki_user DROP COLUMN default_model;

-- 场景模型配置（按场景指定LLM模型）
-- ALTER TABLE zwiki_user ADD COLUMN catalogue_model VARCHAR(128) COMMENT '生成目录使用的模型' AFTER status;
-- ALTER TABLE zwiki_user ADD COLUMN doc_gen_model VARCHAR(128) COMMENT '文档生成使用的模型' AFTER catalogue_model;
-- ALTER TABLE zwiki_user ADD COLUMN chat_model VARCHAR(128) COMMENT '项目问答(Ask AI)使用的模型' AFTER doc_gen_model;
-- ALTER TABLE zwiki_user ADD COLUMN assistant_model VARCHAR(128) COMMENT '智能助手使用的模型' AFTER chat_model;

CREATE TABLE IF NOT EXISTS zwiki_oauth_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(64) NOT NULL,
    login VARCHAR(255),
    name VARCHAR(255),
    avatar_url VARCHAR(512),
    email VARCHAR(255),
    access_token LONGTEXT,
    token_scopes VARCHAR(512),
    token_expires_at DATETIME,
    raw_json LONGTEXT,
    last_login_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_zwiki_oauth_provider_uid (provider, provider_user_id),
    UNIQUE KEY uk_zwiki_oauth_user_provider (user_id, provider),
    INDEX idx_zwiki_oauth_user_id (user_id),
    INDEX idx_zwiki_oauth_login (login)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 系统通知表
CREATE TABLE IF NOT EXISTS zwiki_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL UNIQUE COMMENT '通知唯一ID',
    user_id VARCHAR(64) NOT NULL COMMENT '接收用户ID',
    notification_type VARCHAR(32) NOT NULL COMMENT '通知类型: TASK_QUEUED, TASK_STARTED, TASK_COMPLETED, TASK_FAILED, SYSTEM',
    title VARCHAR(255) COMMENT '通知标题',
    message TEXT COMMENT '通知内容',
    task_id VARCHAR(64) COMMENT '关联任务ID',
    project_name VARCHAR(255) COMMENT '项目名称',
    resource_url VARCHAR(512) COMMENT '跳转链接',
    status VARCHAR(32) COMMENT '任务状态',
    progress INT COMMENT '任务进度(0-100)',
    error_message TEXT COMMENT '错误信息',
    extra_data JSON COMMENT '扩展数据',
    is_read TINYINT(1) DEFAULT 0 NOT NULL COMMENT '是否已读: 0-未读, 1-已读',
    read_time DATETIME COMMENT '阅读时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_notification_user_id (user_id),
    INDEX idx_notification_user_read (user_id, is_read),
    INDEX idx_notification_type (notification_type),
    INDEX idx_notification_task_id (task_id),
    INDEX idx_notification_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通知表';

-- ----------------------------
-- Table structure for zwiki_review_history
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_review_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) COMMENT '触发审查的用户ID',
    repo_full_name VARCHAR(255) NOT NULL COMMENT '仓库全名 owner/repo',
    pr_number INT NOT NULL COMMENT 'PR编号',
    overall_rating VARCHAR(32) COMMENT '总体评级',
    summary TEXT COMMENT '审查摘要',
    comment_count INT DEFAULT 0 COMMENT '评论数',
    error_count INT DEFAULT 0 COMMENT '错误数',
    warning_count INT DEFAULT 0 COMMENT '警告数',
    info_count INT DEFAULT 0 COMMENT '信息数',
    review_detail JSON COMMENT '完整审查详情JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_review_user (user_id),
    INDEX idx_review_repo (repo_full_name),
    INDEX idx_review_repo_pr (repo_full_name, pr_number),
    INDEX idx_review_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码审查历史记录表';

-- ----------------------------
-- Table structure for zwiki_llm_balancer_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_llm_balancer_config (
    id BIGINT PRIMARY KEY COMMENT '固定为1，表示全局配置',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用负载均衡',
    strategy VARCHAR(64) DEFAULT 'round_robin' COMMENT '策略名称',
    max_attempts_per_request INT DEFAULT 3 COMMENT '单次请求最大重试次数',
    unhealthy_cooldown_seconds INT DEFAULT 300 COMMENT '模型熔断冷却时间(秒)',
    allow_fallback_on_explicit_model TINYINT(1) DEFAULT 1 COMMENT '显式指定模型时是否允许回退',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM负载均衡全局配置表';

INSERT INTO zwiki_llm_balancer_config (id, enabled, strategy, max_attempts_per_request, unhealthy_cooldown_seconds, allow_fallback_on_explicit_model)
VALUES (1, 1, 'round_robin', 3, 300, 1)
ON DUPLICATE KEY UPDATE id = id;

-- ----------------------------
-- Table structure for zwiki_llm_key
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_llm_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    name VARCHAR(128) NOT NULL COMMENT 'Key名称',
    provider VARCHAR(64) NOT NULL COMMENT 'Provider，key与provider 1:1',
    api_key_cipher TEXT NOT NULL COMMENT '加密后的API Key',
    api_key_masked VARCHAR(64) NOT NULL COMMENT '脱敏后的API Key',
    base_url VARCHAR(512) DEFAULT NULL COMMENT '自定义API端点URL',
    api_version VARCHAR(32) DEFAULT NULL COMMENT 'API版本(Azure专用)',
    extra_headers JSON DEFAULT NULL COMMENT '额外请求头(JSON对象,企业网关用)',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    description VARCHAR(512) DEFAULT NULL COMMENT '描述',
    call_count BIGINT DEFAULT 0 COMMENT '累计调用次数',
    error_count BIGINT DEFAULT 0 COMMENT '累计错误次数',
    input_tokens BIGINT DEFAULT 0 COMMENT '累计输入Token',
    output_tokens BIGINT DEFAULT 0 COMMENT '累计输出Token',
    total_tokens BIGINT DEFAULT 0 COMMENT '累计总Token',
    last_used_time DATETIME COMMENT '最后使用时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_llm_key_user_name (user_id, name),
    INDEX idx_llm_key_user_provider_enabled (user_id, provider, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户LLM Key表';

-- ----------------------------
-- Table structure for zwiki_llm_provider_stats
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_llm_provider_stats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    provider VARCHAR(64) NOT NULL COMMENT 'Provider: dashscope/openai/...',
    call_count BIGINT DEFAULT 0 COMMENT '累计调用次数',
    error_count BIGINT DEFAULT 0 COMMENT '累计错误次数',
    input_tokens BIGINT DEFAULT 0 COMMENT '累计输入Token',
    output_tokens BIGINT DEFAULT 0 COMMENT '累计输出Token',
    total_tokens BIGINT DEFAULT 0 COMMENT '累计总Token',
    last_used_time DATETIME COMMENT '最后使用时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_provider (user_id, provider),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM Provider 累计统计表';

-- ----------------------------
-- Table structure for zwiki_llm_usage_daily
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_llm_usage_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    stat_date DATE NOT NULL COMMENT '统计日期',
    dimension_type VARCHAR(16) NOT NULL COMMENT '维度: MODEL/KEY/PROVIDER',
    dimension_id VARCHAR(128) NOT NULL COMMENT '维度值: model_name/key_id/provider',
    call_count BIGINT DEFAULT 0,
    error_count BIGINT DEFAULT 0,
    input_tokens BIGINT DEFAULT 0,
    output_tokens BIGINT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_daily_dimension (user_id, stat_date, dimension_type, dimension_id),
    INDEX idx_user_date (user_id, stat_date),
    INDEX idx_dimension (dimension_type, dimension_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 每日用量统计表（支持趋势图）';

-- ----------------------------
-- Table structure for zwiki_llm_model
-- ----------------------------
DROP TABLE IF EXISTS zwiki_llm_model;
CREATE TABLE zwiki_llm_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id VARCHAR(64) COMMENT '用户ID(为空表示公共模型)',
    llm_key_id BIGINT COMMENT '关联LLM Key',
    name VARCHAR(128) NOT NULL COMMENT '模型名称(API调用用)',
    display_name VARCHAR(128) COMMENT '显示名称',
    provider VARCHAR(64) DEFAULT 'dashscope' COMMENT '模型提供商: dashscope, openai, etc',
    model_type VARCHAR(32) DEFAULT 'TEXT' COMMENT '模型类型: TEXT-文本, IMAGE-图片, VOICE-语音, VIDEO-视频, MULTIMODAL-多模态',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    weight INT DEFAULT 1 COMMENT '权重(用于加权轮询)',
    priority INT DEFAULT 0 COMMENT '优先级(数值越大优先级越高)',
    quota_limit BIGINT COMMENT '配额限制(token数)',
    input_tokens BIGINT DEFAULT 0 COMMENT '累计输入Token数',
    output_tokens BIGINT DEFAULT 0 COMMENT '累计输出Token数',
    total_tokens BIGINT DEFAULT 0 COMMENT '累计总Token数',
    call_count BIGINT DEFAULT 0 COMMENT '调用次数',
    error_count BIGINT DEFAULT 0 COMMENT '错误次数',
    last_used_time DATETIME COMMENT '最后使用时间',
    quota_reset_date DATE COMMENT '配额重置日期',
    description TEXT COMMENT '模型描述',
    capabilities VARCHAR(255) COMMENT '模型能力: chat,vision,code,ocr,tool',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_key_model_name (llm_key_id, name),
    INDEX idx_llm_model_user_id (user_id),
    INDEX idx_llm_model_llm_key_id (llm_key_id),
    INDEX idx_llm_model_enabled (enabled),
    INDEX idx_llm_model_provider (provider),
    INDEX idx_llm_model_type (model_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM模型配置表';

-- ----------------------------
-- Initial LLM models data
-- -----------------------------------------------------
INSERT INTO zwiki_llm_model (user_id, name, display_name, provider, model_type, enabled, weight, priority, quota_limit, input_tokens, output_tokens, total_tokens, call_count, error_count, quota_reset_date, capabilities) VALUES
('94ede26710e8418a80ba04d8ba69d470', 'qwen-flash-character', '通义千问Flash-角色版', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-23', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'deepseek-v3.2', 'DeepSeek-V3.2', 'dashscope', 'TEXT', 1, 3, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-03', 'chat,code,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-vl-max-2025-08-13', 'Qwen-VL-Max', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-08', 'chat,vision'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-mt-flash', 'Qwen-MT-Flash', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-04', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'kimi-k2.5', 'Kimi-K2.5', 'dashscope', 'TEXT', 1, 3, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-30', 'chat,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'kimi-k2-thinking', 'Kimi-K2-Thinking', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-08', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen3-vl-plus-2025-12-19', 'Qwen3-VL-Plus', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-19', 'chat,vision'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-mt-lite', 'Qwen-MT-Lite', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-18', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen3-max-preview', 'Qwen3-Max-Preview', 'dashscope', 'TEXT', 1, 2, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-03', 'chat,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen3-vl-flash-2026-01-22', 'Qwen3-VL-Flash', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-22', 'chat,vision'),
('94ede26710e8418a80ba04d8ba69d470', 'tongyi-xiaomi-analysis-pro', '通义-小米分析-Pro', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-09', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'tongyi-xiaomi-analysis-flash', '通义-小米分析-Flash', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-09', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'deepseek-v3.1', 'DeepSeek-V3.1', 'dashscope', 'TEXT', 1, 3, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-18', 'chat,code,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-vl-plus-2025-08-15', 'Qwen-VL-Plus', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-11', 'chat,vision'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-plus-2025-12-01', 'Qwen-Plus', 'dashscope', 'TEXT', 1, 3, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-01', 'chat,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-vl-ocr-2025-11-20', 'Qwen-VL-OCR', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-18', 'ocr'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen-vl-ocr-2025-08-28', 'Qwen-VL-OCR', 'dashscope', 'MULTIMODAL', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-03', 'ocr'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen2.5-1.5b-instruct', 'Qwen2.5-1.5B', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-25', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'qwen2.5-0.5b-instruct', 'Qwen2.5-0.5B', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-25', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'deepseek-r1-distill-llama-70b', 'DeepSeek-R1-Distill-Llama-70B', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-25', 'chat,code'),
('94ede26710e8418a80ba04d8ba69d470', 'llama-4-maverick-17b-128e-instruct', 'Llama-4-Maverick-17B', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-25', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'llama-4-scout-17b-16e-instruct', 'Llama-4-Scout-17B', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-02-25', 'chat'),
('94ede26710e8418a80ba04d8ba69d470', 'MiniMax-M2.1', 'MiniMax-M2.1', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-04-23', 'chat,tool'),
('94ede26710e8418a80ba04d8ba69d470', 'glm-4.7', 'GLM-4.7', 'dashscope', 'TEXT', 1, 1, 0, 1000000, 0, 0, 0, 0, 0, '2026-03-25', 'chat,tool');

-- ----------------------------
-- Table structure for zwiki_github_review_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_github_review_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    repo_full_name VARCHAR(256) NOT NULL COMMENT '仓库全名 owner/repo',
    webhook_secret VARCHAR(256) DEFAULT '' COMMENT 'Webhook签名密钥',
    custom_pat TEXT DEFAULT NULL COMMENT '自定义PAT(加密存储,为空则用OAuth token)',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用审查',
    last_review_at DATETIME DEFAULT NULL COMMENT '最后审查时间',
    review_count INT DEFAULT 0 COMMENT '累计审查次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_repo (user_id, repo_full_name),
    INDEX idx_repo (repo_full_name),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GitHub代码审查配置表';

-- ----------------------------
-- Table structure for zwiki_wiki_webhook_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_wiki_webhook_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    task_id VARCHAR(64) NOT NULL COMMENT '关联任务ID',
    repo_full_name VARCHAR(256) NOT NULL COMMENT '仓库全名 owner/repo',
    webhook_secret VARCHAR(256) DEFAULT '' COMMENT 'Webhook签名密钥',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用自动更新',
    last_trigger_at DATETIME DEFAULT NULL COMMENT '最后触发时间',
    trigger_count INT DEFAULT 0 COMMENT '累计触发次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_task (user_id, task_id),
    INDEX idx_repo (repo_full_name),
    INDEX idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Wiki Webhook自动更新配置表';

-- ----------------------------
-- Table structure for zwiki_doc_change
-- ----------------------------
-- ----------------------------
-- Table structure for zwiki_diagram
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_diagram (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    diagram_id VARCHAR(64) NOT NULL UNIQUE COMMENT '图表唯一ID',
    task_id VARCHAR(64) NOT NULL COMMENT '关联任务ID',
    user_id VARCHAR(64) NOT NULL COMMENT '创建者用户ID',
    name VARCHAR(255) NOT NULL COMMENT '图表名称',
    xml_data LONGTEXT COMMENT 'draw.io XML数据',
    svg_data LONGTEXT COMMENT 'SVG预览数据',
    source_url LONGTEXT COMMENT 'AI生成的draw.io原始URL',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_diagram_task_id (task_id),
    INDEX idx_diagram_user_id (user_id),
    INDEX idx_diagram_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Draw.io图表数据表';

-- Migration: add source_url column to existing zwiki_diagram table
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'zwiki_diagram' AND COLUMN_NAME = 'source_url');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE zwiki_diagram ADD COLUMN source_url LONGTEXT COMMENT ''AI生成的draw.io原始URL'' AFTER svg_data', 'ALTER TABLE zwiki_diagram MODIFY COLUMN source_url LONGTEXT COMMENT ''AI生成的draw.io原始URL''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS zwiki_doc_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL COMMENT '关联任务ID',
    catalogue_id VARCHAR(64) COMMENT '章节ID（空表示整文档）',
    change_type VARCHAR(32) NOT NULL COMMENT '变更类型: ADD-新增, MODIFY-修改, DELETE-删除',
    trigger_source VARCHAR(64) COMMENT '触发来源: WEBHOOK-push事件, MANUAL-手动, SYSTEM-系统',
    before_content_hash VARCHAR(64) COMMENT '变更前内容哈希',
    after_content_hash VARCHAR(64) COMMENT '变更后内容哈希',
    before_content_summary TEXT COMMENT '变更前内容摘要（前500字）',
    after_content_summary TEXT COMMENT '变更后内容摘要（前500字）',
    diff_content TEXT COMMENT '差异内容（统一格式diff）',
    triggered_by VARCHAR(64) COMMENT '触发者（用户ID或webhook）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_catalogue_id (catalogue_id),
    INDEX idx_created_at (created_at),
    INDEX idx_change_type (change_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档变更历史表';

-- ----------------------------
-- Table structure for zwiki_api_key
-- ----------------------------
CREATE TABLE IF NOT EXISTS zwiki_api_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id VARCHAR(64) NOT NULL UNIQUE COMMENT 'API Key ID（前缀标识）',
    api_key VARCHAR(256) NOT NULL UNIQUE COMMENT '完整 API Key = key_id.secret',
    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
    name VARCHAR(128) NOT NULL DEFAULT '默认项目' COMMENT 'Key 名称',
    last_used_at DATETIME DEFAULT NULL COMMENT '上次使用时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_apikey_user_id (user_id),
    INDEX idx_apikey_api_key (api_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 MCP API Key 表';
