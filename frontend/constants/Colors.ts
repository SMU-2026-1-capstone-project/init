// ShadowFit 앱 컬러 팔레트
export const COLORS = {
  // Primary - 라임 그린 액센트
  primary: '#CAFF00',
  primaryDim: 'rgba(202, 255, 0, 0.15)',
  primaryText: '#000000',

  // Background
  background: '#0D0D0D',
  surface: '#1A1A1A',
  surfaceLight: '#2A2A2A',
  card: '#1E1E1E',
  cardBorder: '#333333',

  // Text
  text: '#FFFFFF',
  textSecondary: '#999999',
  textMuted: '#666666',
  textPlaceholder: '#555555',

  // Status
  success: '#4CAF50',
  warning: '#FF9800',
  error: '#FF4444',
  info: '#2196F3',

  // Sync rate colors
  syncHigh: '#CAFF00',
  syncMid: '#FF9800',
  syncLow: '#FF4444',

  // Misc
  border: '#333333',
  divider: '#2A2A2A',
  overlay: 'rgba(0, 0, 0, 0.5)',
  transparent: 'transparent',
  white: '#FFFFFF',
  black: '#000000',
};

export const FONT_SIZE = {
  xs: 11,
  sm: 13,
  md: 15,
  lg: 17,
  xl: 20,
  xxl: 24,
  title: 28,
  hero: 36,
};

export const SPACING = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  xxl: 24,
  xxxl: 32,
};

export const RADIUS = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  full: 9999,
};

// react-navigation 호환용 (기존 코드 호환)
export default {
  light: {
    text: COLORS.black,
    background: COLORS.white,
    tint: COLORS.primary,
    tabIconDefault: '#ccc',
    tabIconSelected: COLORS.primary,
  },
  dark: {
    text: COLORS.text,
    background: COLORS.background,
    tint: COLORS.primary,
    tabIconDefault: COLORS.textMuted,
    tabIconSelected: COLORS.primary,
  },
};
