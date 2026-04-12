import {
  TouchableOpacity,
  Text,
  StyleSheet,
  ActivityIndicator,
  type ViewStyle,
  type TextStyle,
} from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';

interface ButtonProps {
  title: string;
  onPress: () => void;
  variant?: 'primary' | 'outline' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  disabled?: boolean;
  style?: ViewStyle;
  textStyle?: TextStyle;
  icon?: React.ReactNode;
}

export default function Button({
  title,
  onPress,
  variant = 'primary',
  size = 'lg',
  loading = false,
  disabled = false,
  style,
  textStyle,
  icon,
}: ButtonProps) {
  const isDisabled = disabled || loading;

  return (
    <TouchableOpacity
      style={[
        styles.base,
        styles[`size_${size}`],
        styles[`variant_${variant}`],
        isDisabled && styles.disabled,
        style,
      ]}
      onPress={onPress}
      disabled={isDisabled}
      activeOpacity={0.8}
    >
      {loading ? (
        <ActivityIndicator
          color={variant === 'primary' ? COLORS.black : COLORS.primary}
          size="small"
        />
      ) : (
        <>
          {icon}
          <Text
            style={[
              styles.text,
              styles[`text_${variant}`],
              styles[`textSize_${size}`],
              textStyle,
            ]}
          >
            {title}
          </Text>
        </>
      )}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
  },

  // Sizes
  size_sm: {
    height: 40,
    paddingHorizontal: SPACING.lg,
    borderRadius: RADIUS.sm,
  },
  size_md: {
    height: 48,
    paddingHorizontal: SPACING.xl,
    borderRadius: RADIUS.md,
  },
  size_lg: {
    height: 56,
    paddingHorizontal: SPACING.xxl,
    borderRadius: RADIUS.md,
  },

  // Variants
  variant_primary: {
    backgroundColor: COLORS.primary,
  },
  variant_outline: {
    backgroundColor: COLORS.transparent,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  variant_ghost: {
    backgroundColor: COLORS.transparent,
  },

  disabled: {
    opacity: 0.5,
  },

  // Text
  text: {
    fontWeight: '700',
  },
  text_primary: {
    color: COLORS.black,
  },
  text_outline: {
    color: COLORS.text,
  },
  text_ghost: {
    color: COLORS.primary,
  },

  textSize_sm: { fontSize: FONT_SIZE.sm },
  textSize_md: { fontSize: FONT_SIZE.md },
  textSize_lg: { fontSize: FONT_SIZE.lg },
});
