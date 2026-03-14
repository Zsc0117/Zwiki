import React, { useEffect, useRef, useState, useCallback } from 'react';
import mermaid from 'mermaid';

// 确保 Mermaid 只初始化一次
let isInitialized = false;

// 错误边界组件
class MermaidErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error('Mermaid Error Boundary:', error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          margin: '20px 0',
          padding: '16px',
          border: '1px solid #ffccc7',
          borderRadius: '6px',
          background: '#fff1f0',
          color: '#cf1322',
          textAlign: 'center',
          fontSize: '13px',
        }}>
          <strong>📊 图表组件错误</strong>
          <div style={{ marginTop: '8px', fontSize: '12px', color: '#8c8c8c' }}>
            {this.state.error?.message || '未知错误'}
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

const MermaidChart = React.memo(({ chart, id }) => {
  const containerRef = useRef(null);
  const [renderState, setRenderState] = useState('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [svgContent, setSvgContent] = useState('');
  const isMountedRef = useRef(true);
  const renderTimeoutRef = useRef(null);
  const chartIdRef = useRef(id);

  // 清理和标准化图表内容：检测并解码 URL 编码的 Mermaid 内容
  const chartContent = (() => {
    if (!chart) return '';
    let raw = chart.trim();
    // 检测是否包含 URL 编码字符（如 %0A=%20 %7C 等）
    if (/%[0-9A-Fa-f]{2}/.test(raw)) {
      try {
        const decoded = decodeURIComponent(raw);
        // 解码成功且内容有实质变化，使用解码后的内容
        if (decoded !== raw && decoded.length > 0) {
          console.info('[MermaidChart] Decoded URL-encoded Mermaid content');
          raw = decoded;
        }
      } catch {
        // decodeURIComponent 失败（内容可能不是有效的 URL 编码），保留原始内容
      }
    }
    return raw.trim();
  })();

  // 生成稳定的图表 ID
  if (!chartIdRef.current) {
    chartIdRef.current = `mermaid-chart-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }
  const chartId = chartIdRef.current;

  // 安全更新状态的函数
  const safeSetState = useCallback((setter) => {
    if (isMountedRef.current) {
      try {
        setter();
      } catch (error) {
        console.warn('状态更新错误:', error);
      }
    }
  }, []);

  // 清理定时器
  const cleanupTimeout = useCallback(() => {
    if (renderTimeoutRef.current) {
      clearTimeout(renderTimeoutRef.current);
      renderTimeoutRef.current = null;
    }
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    
    return () => {
      isMountedRef.current = false;
      cleanupTimeout();
    };
  }, [cleanupTimeout]);

  useEffect(() => {
    cleanupTimeout();
    
    if (!chartContent) {
      safeSetState(() => {
        setRenderState('error');
        setErrorMessage('没有图表内容');
        setSvgContent('');
      });
      return;
    }

    // 预验证和修复Mermaid语法
    const preprocessMermaidSyntax = (content) => {
      let processed = content.trim();

      // 修复节点标签中的嵌套方括号，例如：B[前端添加[DEEP RESEARCH]标签]
      // Mermaid 会将内层 ] 提前当作节点结束，导致 Parse error。
      // 仅在检测到嵌套时，将内层 [] 转为全角符号，保留外层节点语法。
      const normalizeNestedSquareBrackets = (input) => {
        return input
          .split(/\r?\n/)
          .map((line) => {
            let i = 0;
            let output = '';

            while (i < line.length) {
              const ch = line[i];

              // 仅处理单层节点语法，跳过 [[...]] 这类 Mermaid 特殊形状
              if (ch === '[' && line[i + 1] !== '[') {
                let j = i + 1;
                let depth = 1;

                while (j < line.length && depth > 0) {
                  if (line[j] === '[') depth += 1;
                  else if (line[j] === ']') depth -= 1;
                  j += 1;
                }

                if (depth === 0) {
                  const inner = line.slice(i + 1, j - 1);
                  const hasNested = inner.includes('[') || inner.includes(']');
                  if (hasNested) {
                    const escapedInner = inner
                      .replace(/\[/g, '【')
                      .replace(/\]/g, '】');
                    output += `[${escapedInner}]`;
                    i = j;
                    continue;
                  }
                }
              }

              output += ch;
              i += 1;
            }

            return output;
          })
          .join('\n');
      };
      processed = normalizeNestedSquareBrackets(processed);
      
      // 修复节点标签中的斜杠（/text/ 会被 Mermaid 解析为梯形节点语法）
      processed = processed.replace(/\[([^\]]*\/[^\]]*)\]/g, (match, label) => {
        // 如果标签以 / 开头或结尾（梯形语法），则转义为普通文本
        const trimmed = label.trim();
        if (trimmed.startsWith('/') || trimmed.endsWith('/')) {
          const escaped = label.replace(/\//g, '&#47;');
          return `[${escaped}]`;
        }
        return match;
      });

      // 修复 subgraph 语法问题
      // 1. 处理包含括号的 subgraph 标题
      processed = processed.replace(/subgraph\s+([^"\n\r]+?)(\s*\([^)]*\))/g, (match, title, parentheses) => {
        const cleanTitle = title.trim() + parentheses;
        return `subgraph "${cleanTitle}"`;
      });
      
      // 2. 处理没有引号的 subgraph 标题（包含空格的）
      processed = processed.replace(/subgraph\s+([^"\n\r]+?)(?=\s*\n|$)/g, (match, title) => {
        const cleanTitle = title.trim();
        // 如果标题包含空格或特殊字符，添加引号
        if (cleanTitle.includes(' ') || /[()[\]{}]/.test(cleanTitle)) {
          return `subgraph "${cleanTitle}"`;
        }
        return match;
      });
      
      return processed;
    };

    const isValidMermaidSyntax = (content) => {
      const trimmed = content.trim();
      
      // 基本格式检查
      if (trimmed.length < 10) return false;
      
      // 检查是否以有效的Mermaid关键字开头
      const validStarters = [
        /^graph\s+(TD|TB|BT|RL|LR)/i,
        /^flowchart\s+(TD|TB|BT|RL|LR)/i,
        /^sequenceDiagram/i,
        /^classDiagram/i,
        /^stateDiagram(-v2)?/i,
        /^erDiagram/i,
        /^gantt/i,
        /^journey/i,
        /^pie\s+title/i,
        /^gitgraph/i
      ];
      
      const hasValidStarter = validStarters.some(pattern => pattern.test(trimmed));
      if (!hasValidStarter) return false;
      
      // 检查是否包含基本的Mermaid语法元素
      const mermaidFeatures = [
        /-->/,     // 箭头
        /---/,     // 连线
        /\[\s*.*\s*\]/,  // 方括号节点
        /\(\s*.*\s*\)/,  // 圆括号节点
        /\{\s*.*\s*\}/,  // 大括号节点
        /participant\s+/i,  // 序列图参与者
        /class\s+\w+/i,     // 类图
        /state\s+\w+/i,     // 状态图
        /\|\w+\|/,          // 实体关系图
        /subgraph/i,        // 子图
        /direction\s+(TD|TB|BT|RL|LR)/i  // 方向指令
      ];
      
      return mermaidFeatures.some(pattern => pattern.test(trimmed));
    };

    // 预处理图表内容
    const processedChartContent = preprocessMermaidSyntax(chartContent);

    // 如果不是有效的Mermaid语法，直接标记为无效
    if (!isValidMermaidSyntax(processedChartContent)) {
      safeSetState(() => {
        setRenderState('invalid');
        setErrorMessage('');
        setSvgContent('');
      });
      return;
    }

    safeSetState(() => {
      setRenderState('loading');
      setErrorMessage('');
      setSvgContent('');
    });

    const renderChart = async () => {
      if (!isMountedRef.current) {
        return;
      }

      try {
        // 对于 Mermaid 11.x，只初始化一次
        if (!isInitialized) {
          mermaid.initialize({
            startOnLoad: false,
            theme: 'default',
            securityLevel: 'loose',
            themeVariables: {
              primaryColor: '#1890ff',
              primaryTextColor: '#262626',
              primaryBorderColor: '#d9d9d9',
              lineColor: '#595959',
              sectionBkgColor: '#f6f8fa',
              altSectionBkgColor: '#ffffff',
              gridColor: '#e1e4e8',
              secondaryColor: '#f0f2f5',
              tertiaryColor: '#fafafa'
            }
          });
          isInitialized = true;
        }

        // 使用唯一ID避免冲突
        const uniqueId = `${chartId}-${Date.now()}`;

        // 对于 Mermaid 11.x，使用 mermaid.render 方法
        const { svg } = await mermaid.render(uniqueId, processedChartContent);

        if (isMountedRef.current && svg) {
          safeSetState(() => {
            setSvgContent(svg);
            setRenderState('success');
          });
        }
      } catch (error) {
        console.error('Mermaid 渲染失败:', error);

        if (!isMountedRef.current) {
          return;
        }

        // 对于语法错误，标记为无效而不是错误
        if (error.message && (
          error.message.includes('Syntax error') ||
          error.message.includes('Parse error') ||
          error.message.includes('Lexical error')
        )) {
          safeSetState(() => {
            setRenderState('invalid');
            setErrorMessage('');
            setSvgContent('');
          });
          return;
        }

        // 尝试修复语法问题
        try {
          let fixedChart = processedChartContent;

          // 修复其他常见问题
          fixedChart = fixedChart.replace(/subgraph\s+([^"\n\r;]+?)(\s|$|;)/g, 'subgraph "$1"$2');

          const fixedId = `${chartId}-fixed-${Date.now()}`;
          const { svg } = await mermaid.render(fixedId, fixedChart);

          if (isMountedRef.current && svg) {
            safeSetState(() => {
              setSvgContent(svg);
              setRenderState('success');
            });
          }
        } catch (retryError) {
          console.error('修复后仍然失败:', retryError);
          if (isMountedRef.current) {
            safeSetState(() => {
              setRenderState('invalid');
              setErrorMessage('');
              setSvgContent('');
            });
          }
        }
      }
    };

    // 延迟渲染，确保 DOM 准备就绪
    renderTimeoutRef.current = setTimeout(() => {
      if (isMountedRef.current) {
        renderChart().catch(error => {
          console.error('异步渲染错误:', error);
          if (isMountedRef.current) {
            safeSetState(() => {
              setRenderState('invalid');
              setErrorMessage('');
              setSvgContent('');
            });
          }
        });
      }
    }, 200);
    
    return () => {
      cleanupTimeout();
    };
  }, [chartContent, chartId, safeSetState, cleanupTimeout]);

  // 对于无效的Mermaid语法，返回一个特殊标识组件
  if (renderState === 'invalid') {
    return <div data-mermaid-invalid="true" style={{ display: 'none' }} />;
  }

  if (renderState === 'error') {
    return (
      <div style={{
        margin: '20px 0',
        padding: '16px',
        border: '1px solid #ffccc7',
        borderRadius: '6px',
        background: '#fff1f0',
        color: '#cf1322',
        textAlign: 'center',
        fontSize: '13px',
      }}>
        <strong>📊 图表渲染失败</strong>
        <div style={{ marginTop: '8px', fontSize: '12px', color: '#8c8c8c' }}>
          {errorMessage}
        </div>
      </div>
    );
  }

  return (
    <div style={{
      margin: '20px 0',
      padding: '16px',
      border: '1px solid #e1e4e8',
      borderRadius: '6px',
      background: '#fafafa',
      overflow: 'auto',
      minHeight: '120px',
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center'
    }}>
      <div 
        ref={containerRef}
        style={{
          width: '100%',
          textAlign: 'center'
        }}
      >
        {renderState === 'loading' && (
          <div style={{ color: '#8c8c8c', fontSize: '12px' }}>
            🔄 正在渲染图表...
          </div>
        )}
        {renderState === 'success' && svgContent && (
          <div 
            dangerouslySetInnerHTML={{ __html: svgContent }}
            style={{ width: '100%' }}
          />
        )}
      </div>
    </div>
  );
});

MermaidChart.displayName = 'MermaidChart';

// 导出带错误边界的组件
const MermaidChartWithBoundary = (props) => (
  <MermaidErrorBoundary>
    <MermaidChart {...props} />
  </MermaidErrorBoundary>
);

export default MermaidChartWithBoundary; 