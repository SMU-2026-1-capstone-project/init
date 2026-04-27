import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';

// TODO: API 연동 후 실제 데이터로 교체
const MOCK_WEEKLY = {
  range: '3월 23일 - 29일',
  workouts: 4,
  minutes: 35,
  calories: 250,
  exercises: [
    { name: '숄더프레스', sets: 4, reps: 10, syncRate: 93 },
    { name: '사이드레터럴레이즈', sets: 3, reps: 15, syncRate: 88 },
    { name: '프론트레이즈', sets: 3, reps: 12, syncRate: 91 },
    { name: '페이스풀', sets: 3, reps: 15, syncRate: 85 },
  ],
  dailyCalories: [250, 0, 0, 0, 0, 0, 0],
  days: ['3월 23일', '3월 24일', '3월 25일', '3월 26일', '3월 27일', '3월 28일', '3월 29일'],
};

export default function ActivityScreen() {
  const router = useRouter();

  const maxCal = Math.max(...MOCK_WEEKLY.dailyCalories, 1);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={styles.header}>
          <Text style={styles.title}>Activity</Text>
        </View>

        {/* 주간 네비게이션 */}
        <View style={styles.weekNav}>
          <TouchableOpacity><FontAwesome name="chevron-left" size={14} color={COLORS.textSecondary} /></TouchableOpacity>
          <Text style={styles.weekRange}>{MOCK_WEEKLY.range}</Text>
          <TouchableOpacity><FontAwesome name="chevron-right" size={14} color={COLORS.textSecondary} /></TouchableOpacity>
        </View>

        {/* 주간 통계 */}
        <View style={styles.statsRow}>
          <WeeklyStat icon="💪" value={String(MOCK_WEEKLY.workouts)} label="Workouts" />
          <WeeklyStat icon="⏱" value={String(MOCK_WEEKLY.minutes)} label="Min" />
          <WeeklyStat icon="🔥" value={String(MOCK_WEEKLY.calories)} label="Kcal" />
        </View>
        <View style={styles.changeRow}>
          <Text style={styles.changeText}>— 0%</Text>
          <Text style={styles.changeText}>— 0%</Text>
          <Text style={styles.changeText}>— 0%</Text>
        </View>

        {/* 운동일지 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>운동일지</Text>

          <View style={styles.summaryCard}>
            <Text style={styles.summaryText}>
              {MOCK_WEEKLY.workouts} Workouts   {MOCK_WEEKLY.minutes} Min   {MOCK_WEEKLY.calories} Kcal
            </Text>
            <FontAwesome name="chevron-right" size={12} color={COLORS.textMuted} />
          </View>

          {/* 바 차트 */}
          <View style={styles.chartContainer}>
            {MOCK_WEEKLY.dailyCalories.map((cal, i) => (
              <View key={i} style={styles.chartCol}>
                <View style={styles.barWrapper}>
                  <View
                    style={[
                      styles.bar,
                      {
                        height: cal > 0 ? Math.max((cal / maxCal) * 80, 8) : 0,
                      },
                    ]}
                  />
                </View>
                <Text style={styles.chartLabel}>{MOCK_WEEKLY.days[i]}</Text>
              </View>
            ))}
          </View>
        </View>

        {/* 운동 상세 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>
            {MOCK_WEEKLY.days[0]} 운동 상세
          </Text>
          {MOCK_WEEKLY.exercises.map((ex) => (
            <View key={ex.name} style={styles.exerciseCard}>
              <View style={styles.exerciseInfo}>
                <Text style={styles.exerciseName}>{ex.name}</Text>
                <Text style={styles.exerciseSets}>{ex.sets}세트 × {ex.reps}회</Text>
              </View>
              <View style={styles.syncBadge}>
                <Text style={[styles.syncValue, { color: getSyncColor(ex.syncRate) }]}>
                  {ex.syncRate}%
                </Text>
                <Text style={styles.syncLabel}>싱크로율</Text>
              </View>
            </View>
          ))}
        </View>

        {/* 보고서 버튼 */}
        <TouchableOpacity
          style={styles.reportBtn}
          onPress={() => router.push('/report/1' as any)}
          activeOpacity={0.8}
        >
          <FontAwesome name="file-text-o" size={16} color={COLORS.black} />
          <Text style={styles.reportBtnText}>운동 보고서 보기</Text>
        </TouchableOpacity>

        <View style={{ height: 80 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

function WeeklyStat({ icon, value, label }: { icon: string; value: string; label: string }) {
  return (
    <View style={styles.statCard}>
      <Text style={styles.statIcon}>{icon}</Text>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

function getSyncColor(rate: number) {
  if (rate >= 90) return COLORS.primary;
  if (rate >= 80) return COLORS.warning;
  return COLORS.error;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  header: { paddingHorizontal: SPACING.xxl, paddingTop: SPACING.md },
  title: { fontSize: FONT_SIZE.title, fontWeight: '800', color: COLORS.text },

  weekNav: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.lg,
  },
  weekRange: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },

  statsRow: {
    flexDirection: 'row',
    paddingHorizontal: SPACING.xxl,
    gap: SPACING.md,
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
  statIcon: { fontSize: 16, marginBottom: 4 },
  statValue: { fontSize: FONT_SIZE.xxl, fontWeight: '800', color: COLORS.text },
  statLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  changeRow: {
    flexDirection: 'row',
    paddingHorizontal: SPACING.xxl,
    marginTop: SPACING.xs,
    gap: SPACING.md,
  },
  changeText: {
    flex: 1,
    textAlign: 'center',
    fontSize: FONT_SIZE.xs,
    color: COLORS.textMuted,
  },

  section: { paddingHorizontal: SPACING.xxl, marginTop: SPACING.xxl },
  sectionTitle: { fontSize: FONT_SIZE.lg, fontWeight: '700', color: COLORS.text, marginBottom: SPACING.md },

  summaryCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.lg,
  },
  summaryText: { fontSize: FONT_SIZE.sm, fontWeight: '700', color: COLORS.text },

  chartContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
    height: 120,
    marginBottom: SPACING.lg,
  },
  chartCol: { flex: 1, alignItems: 'center' },
  barWrapper: { height: 80, justifyContent: 'flex-end' },
  bar: {
    width: 20,
    backgroundColor: COLORS.primary,
    borderRadius: 4,
  },
  chartLabel: { fontSize: 9, color: COLORS.textMuted, marginTop: 4 },

  exerciseCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.sm,
  },
  exerciseInfo: { flex: 1 },
  exerciseName: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  exerciseSets: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, marginTop: 2 },
  syncBadge: { alignItems: 'flex-end' },
  syncValue: { fontSize: FONT_SIZE.xl, fontWeight: '800' },
  syncLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary },

  reportBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
    backgroundColor: COLORS.primary,
    marginHorizontal: SPACING.xxl,
    marginTop: SPACING.xxl,
    paddingVertical: SPACING.lg,
    borderRadius: RADIUS.md,
  },
  reportBtnText: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.black },
});
