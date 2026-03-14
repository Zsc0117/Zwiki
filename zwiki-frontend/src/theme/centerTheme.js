export const centerPalette = {
  primary: '#20b486',
  primaryHover: '#32c798',
  primaryActive: '#16946f',
  primarySoft: '#e8faf2',
  primarySoftStrong: '#d8f4e8',
  primaryBorder: '#a9e6cc',
  heading: '#1f3d34',
  text: '#385c51',
  textMuted: '#6c8c82',
  layoutBg: '#f5fdf9',
  layoutBgAlt: '#eefaf5',
  panelBg: '#ffffff',
  siderBg: '#f8fffb',
  contentBg: 'rgba(255, 255, 255, 0.84)',
  headerBgStart: '#f2fff9',
  headerBgEnd: '#ddf6eb',
  headerBorder: 'rgba(32, 180, 134, 0.16)',
  shadow: 'rgba(32, 180, 134, 0.14)',
  shadowStrong: 'rgba(32, 180, 134, 0.18)',
  menuHoverBg: '#eefaf4',
  menuSelectedColor: '#11795b',
  menuSelectedBg: '#def5e8',
  tableHeaderBg: '#f3fbf7',
  tableRowHoverBg: '#eefaf4',
  tableBorderColor: '#e2f3ea',
  collapseHeaderBg: '#f7fcf9',
  collapseContentBg: '#ffffff',
  tagDefaultBg: '#f3fbf7',
  queue: '#20b486',
  accent: '#14b8a6',
  success: '#2fbe8c',
  warning: '#f59e0b',
  danger: '#ef4444',
  system: '#c59a42',
  neutral: '#8c8c8c',
};

export const getCenterPalette = (darkMode = false) => {
  if (!darkMode) {
    return centerPalette;
  }

  return {
    ...centerPalette,
    primary: '#67d6b1',
    primaryHover: '#83e0c0',
    primaryActive: '#46c89a',
    primarySoft: 'rgba(103, 214, 177, 0.14)',
    primarySoftStrong: 'rgba(103, 214, 177, 0.2)',
    primaryBorder: 'rgba(103, 214, 177, 0.28)',
    heading: '#e6fff6',
    text: 'rgba(230, 255, 246, 0.88)',
    textMuted: 'rgba(214, 239, 230, 0.64)',
    layoutBg: '#0f1917',
    layoutBgAlt: '#121f1b',
    panelBg: '#16231f',
    siderBg: 'rgba(19, 33, 28, 0.94)',
    contentBg: 'rgba(20, 32, 28, 0.88)',
    headerBgStart: '#172722',
    headerBgEnd: '#0f1d19',
    headerBorder: 'rgba(103, 214, 177, 0.16)',
    shadow: 'rgba(0, 0, 0, 0.28)',
    shadowStrong: 'rgba(0, 0, 0, 0.36)',
    menuHoverBg: 'rgba(103, 214, 177, 0.1)',
    menuSelectedColor: '#c8ffeb',
    menuSelectedBg: 'rgba(103, 214, 177, 0.16)',
    tableHeaderBg: '#1c2b26',
    tableRowHoverBg: '#1a2924',
    tableBorderColor: '#223730',
    collapseHeaderBg: '#1a2824',
    collapseContentBg: '#16231f',
    tagDefaultBg: '#1d2d28',
  };
};

export const getCenterTheme = (darkMode = false) => {
  const palette = getCenterPalette(darkMode);

  return {
    token: {
      colorPrimary: palette.primary,
      colorInfo: palette.primary,
      colorSuccess: palette.success,
      colorLink: palette.primary,
      colorLinkHover: palette.primaryHover,
      borderRadius: 12,
    },
    components: {
      Button: {
        borderRadius: 10,
        controlHeight: 38,
        colorPrimaryHover: palette.primaryHover,
        colorPrimaryActive: palette.primaryActive,
      },
      Card: {
        borderRadiusLG: 18,
        boxShadow: `0 12px 36px ${palette.shadow}`,
      },
      Menu: {
        itemColor: palette.text,
        itemHoverColor: palette.heading,
        itemHoverBg: palette.menuHoverBg,
        itemSelectedColor: palette.menuSelectedColor,
        itemSelectedBg: palette.menuSelectedBg,
        itemBorderRadius: 12,
        itemMarginInline: 12,
        itemMarginBlock: 6,
        activeBarHeight: 0,
      },
      Table: {
        headerBg: palette.tableHeaderBg,
        headerColor: palette.heading,
        rowHoverBg: palette.tableRowHoverBg,
        borderColor: palette.tableBorderColor,
      },
      Collapse: {
        headerBg: palette.collapseHeaderBg,
        contentBg: palette.collapseContentBg,
      },
      Tabs: {
        inkBarColor: palette.primary,
        itemSelectedColor: palette.primary,
        itemHoverColor: palette.primaryHover,
      },
      Select: {
        activeBorderColor: palette.primary,
        hoverBorderColor: palette.primaryHover,
      },
      Input: {
        activeBorderColor: palette.primary,
        hoverBorderColor: palette.primaryHover,
      },
      Switch: {
        colorPrimary: palette.primary,
        colorPrimaryHover: palette.primaryHover,
      },
      Tag: {
        defaultBg: palette.tagDefaultBg,
      },
    },
  };
};

export default getCenterTheme();
