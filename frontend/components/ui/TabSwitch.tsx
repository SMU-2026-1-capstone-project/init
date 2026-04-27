import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { COLORS, FONT_SIZE, RADIUS, SPACING } from '@/constants/Colors';

interface TabSwitchProps {
  tabs: string[];
  activeIndex: number;
  onTabChange: (index: number) => void;
}

export default function TabSwitch({ tabs, activeIndex, onTabChange }: TabSwitchProps) {
  return (
    <View style={styles.container}>
      {tabs.map((tab, index) => {
        const isActive = index === activeIndex;
        return (
          <TouchableOpacity
            key={tab}
            style={[styles.tab, isActive && styles.activeTab]}
            onPress={() => onTabChange(index)}
            activeOpacity={0.8}
          >
            <Text style={[styles.tabText, isActive && styles.activeTabText]}>
              {tab}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    padding: 4,
  },
  tab: {
    flex: 1,
    paddingVertical: SPACING.md,
    alignItems: 'center',
    borderRadius: RADIUS.sm,
  },
  activeTab: {
    backgroundColor: COLORS.primary,
  },
  tabText: {
    fontSize: FONT_SIZE.md,
    fontWeight: '600',
    color: COLORS.textSecondary,
  },
  activeTabText: {
    color: COLORS.black,
  },
});
