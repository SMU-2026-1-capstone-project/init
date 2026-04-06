import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { Calendar, type DateData } from 'react-native-calendars';
import { useState } from 'react';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';

// TODO: API 연동 후 실제 데이터로 교체
const MOCK_MARKED_DATES: Record<string, any> = {
  '2026-03-01': { marked: true, dotColor: COLORS.primary },
  '2026-03-02': { marked: true, dotColor: COLORS.primary },
  '2026-03-03': { marked: true, dotColor: COLORS.warning },
  '2026-03-05': { marked: true, dotColor: COLORS.primary },
  '2026-03-07': { marked: true, dotColor: COLORS.warning },
  '2026-03-09': { marked: true, dotColor: COLORS.primary },
  '2026-03-10': { marked: true, dotColor: COLORS.warning },
  '2026-03-12': { marked: true, dotColor: COLORS.primary },
  '2026-03-14': { marked: true, dotColor: COLORS.warning },
  '2026-03-17': { marked: true, dotColor: COLORS.warning },
  '2026-03-19': { marked: true, dotColor: COLORS.primary },
  '2026-03-22': { marked: true, dotColor: COLORS.primary },
  '2026-03-23': { marked: true, dotColor: COLORS.primary },
};

export default function HomeScreen() {
  const router = useRouter();
  const [selectedDate, setSelectedDate] = useState('');
  const today = new Date().toISOString().split('T')[0];

  const markedDates = {
    ...MOCK_MARKED_DATES,
    ...(selectedDate
      ? { [selectedDate]: { ...MOCK_MARKED_DATES[selectedDate], selected: true, selectedColor: COLORS.primary } }
      : {}),
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* 헤더 */}
      <View style={styles.header}>
        <View>
          <Text style={styles.appTitle}>ShadowFit</Text>
          <Text style={styles.appSubtitle}>AI 자세 교정 트레이너</Text>
        </View>
        <TouchableOpacity onPress={() => router.push('/(tabs)/mypage')}>
          <FontAwesome name="user-circle-o" size={28} color={COLORS.textSecondary} />
        </TouchableOpacity>
      </View>

      {/* 상단 통계 카드 */}
      <View style={styles.statsRow}>
        <StatCard icon="🔥" value="11일" label="이번 달" />
        <StatCard icon="🎯" value="84%" label="평균 싱크로율" highlight />
        <StatCard icon="🏆" value="5일" label="연속 기록" />
      </View>

      {/* 캘린더 */}
      <View style={styles.calendarContainer}>
        <Calendar
          markedDates={markedDates}
          onDayPress={(day: DateData) => setSelectedDate(day.dateString)}
          theme={{
            calendarBackground: COLORS.card,
            textSectionTitleColor: COLORS.textSecondary,
            dayTextColor: COLORS.text,
            todayTextColor: COLORS.primary,
            selectedDayTextColor: COLORS.black,
            monthTextColor: COLORS.text,
            textDisabledColor: COLORS.textMuted,
            arrowColor: COLORS.primary,
            textMonthFontWeight: '700',
            textDayFontSize: 14,
            textMonthFontSize: 16,
          }}
          style={styles.calendar}
        />
      </View>

      {/* 선택 날짜 정보 */}
      <View style={styles.dateInfo}>
        <Text style={styles.dateInfoText}>
          {selectedDate
            ? `${new Date(selectedDate).getMonth() + 1}월 ${new Date(selectedDate).getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date(selectedDate).getDay()]}요일`
            : `${new Date().getMonth() + 1}월 ${new Date().getDate()}일 ${['일', '월', '화', '수', '목', '금', '토'][new Date().getDay()]}요일`}
        </Text>
        <Text style={styles.noRecordText}>
          {MOCK_MARKED_DATES[selectedDate || today]
            ? '운동 기록이 있습니다'
            : '운동 기록이 없습니다'}
        </Text>
      </View>

      {/* 운동 시작 버튼 */}
      <View style={styles.footer}>
        <Button
          title="▷  운동 시작하기"
          onPress={() => router.push('/(tabs)/exercise')}
        />
      </View>
    </SafeAreaView>
  );
}

function StatCard({ icon, value, label, highlight }: {
  icon: string;
  value: string;
  label: string;
  highlight?: boolean;
}) {
  return (
    <View style={[styles.statCard, highlight && styles.statCardHighlight]}>
      <Text style={styles.statIcon}>{icon}</Text>
      <Text style={[styles.statValue, highlight && styles.statValueHighlight]}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.md,
  },
  appTitle: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.primary },
  appSubtitle: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  statsRow: {
    flexDirection: 'row',
    paddingHorizontal: SPACING.xxl,
    gap: SPACING.md,
    marginVertical: SPACING.md,
  },
  statCard: {
    flex: 1,
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.md,
    alignItems: 'center',
  },
  statCardHighlight: { borderColor: COLORS.primary },
  statIcon: { fontSize: 18, marginBottom: 4 },
  statValue: { fontSize: FONT_SIZE.xl, fontWeight: '800', color: COLORS.text },
  statValueHighlight: { color: COLORS.primary },
  statLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  calendarContainer: {
    marginHorizontal: SPACING.xxl,
    borderRadius: RADIUS.lg,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
  },
  calendar: { borderRadius: RADIUS.lg },

  dateInfo: { paddingHorizontal: SPACING.xxl, paddingVertical: SPACING.lg },
  dateInfoText: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },
  noRecordText: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted, marginTop: 4 },

  footer: {
    position: 'absolute',
    bottom: 70,
    left: 0,
    right: 0,
    paddingHorizontal: SPACING.xxl,
  },
});
