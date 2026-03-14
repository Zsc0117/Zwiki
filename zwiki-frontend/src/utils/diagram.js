const decodeBase64 = (value) => {
  try {
    return atob(value);
  } catch {
    return '';
  }
};

export const resolveSvgMarkup = (svgData) => {
  const raw = String(svgData || '').trim();
  if (!raw) return '';

  if (raw.includes('<svg')) {
    return raw;
  }

  // data:image/svg+xml;base64,... or data:image/svg+xml;utf8,...
  if (/^data:image\/svg\+xml/i.test(raw)) {
    const commaIndex = raw.indexOf(',');
    if (commaIndex > -1) {
      const meta = raw.slice(0, commaIndex).toLowerCase();
      const payload = raw.slice(commaIndex + 1);
      if (meta.includes('base64')) {
        const decoded = decodeBase64(payload);
        if (decoded.includes('<svg')) return decoded;
      } else {
        try {
          const decoded = decodeURIComponent(payload);
          if (decoded.includes('<svg')) return decoded;
        } catch {
          // ignore decode failure
        }
      }
    }
  }

  // pure base64 svg
  if (/^[A-Za-z0-9+/=\s]+$/.test(raw) && raw.length > 40) {
    const decoded = decodeBase64(raw.replace(/\s+/g, ''));
    if (decoded.includes('<svg')) return decoded;
  }

  return '';
};

export const formatDiagramTime = (value) => {
  if (!value) return '时间未知';

  // LocalDateTime with WRITE_DATES_AS_TIMESTAMPS may become array
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value;
    const dt = new Date(
      Number(year) || 1970,
      Math.max((Number(month) || 1) - 1, 0),
      Number(day) || 1,
      Number(hour) || 0,
      Number(minute) || 0,
      Number(second) || 0,
      Math.floor((Number(nano) || 0) / 1e6),
    );
    return Number.isNaN(dt.getTime()) ? '时间未知' : dt.toLocaleString('zh-CN', { hour12: false });
  }

  const raw = String(value).trim();
  if (!raw) return '时间未知';
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T');
  const dt = new Date(normalized);
  return Number.isNaN(dt.getTime()) ? '时间未知' : dt.toLocaleString('zh-CN', { hour12: false });
};
