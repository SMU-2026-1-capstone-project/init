import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';

// TODO: API 연동 후 실제 데이터로 교체
const MOCK_REPORT = {
  date: '2026년 3월 23일 월요일',
  averageSyncRate: 89,
  totalReps: 156,
  totalMinutes: 35,
  totalCalories: 250,
  exercises: [
    { name: '숄더프레스', sets: 4, reps: 10, syncRate: 93 },
    { name: '사이드레터럴레이즈', sets: 3, reps: 15, syncRate: 88 },
    { name: '프론트레이즈', sets: 3, reps: 12, syncRate: 91 },
    { name: '페이스풀', sets: 3, reps: 15, syncRate: 85 },
  ],
  worstMoment: {
    exercise: '페이스풀',
    time: '22:10',
    syncRate: 70,
    issue: '어깨 승모근 과사용',
  },
  aiReport:
    '페이스풀 시 승모근이 과도하게 개입하고 있습니다. 팔꿈치를 높이 유지하고, 후면 삼각근에 집중하세요. 전반적으로 훌륭한 어깨 루틴이었습니다!',
};

export default function ReportScreen() {
  const router = useRouter();

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={styles.header}>
          <TouchableOpacity onPress={() => router.back()}>
            <FontAwesome name="chevron-left" size={16} color={COLORS.text} />
          </TouchableOpacity>
          <View>
            <Text style={styles.title}>운동 보고서</Text>
            <Text style={styles.date}>{MOCK_REPORT.date}</Text>
          </View>
          <TouchableOpacity>
            <FontAwesome name="download" size={18} color={COLORS.textSecondary} />
          </TouchableOpacity>
        </View>

        {/* 요약 카드 */}
        <View style={styles.summaryGrid}>
          <View style={[styles.summaryCard, styles.summaryCardPrimary]}>
            <Text style={styles.summaryIcon}>🎯</Text>
            <Text style={styles.summaryValue}>{MOCK_REPORT.averageSyncRate}%</Text>
            <Text style={styles.summaryLabel}>평균 싱크로율</Text>
          </View>
          <View style={styles.summaryCard}>
            <Text style={styles.summaryIcon}>📈</Text>
            <Text style={styles.summaryValue}>{MOCK_REPORT.totalReps}</Text>
            <Text style={styles.summaryLabel}>총 운동 횟수</Text>
          </View>
          <View style={styles.summaryCard}>
            <Text style={styles.summaryIcon}>⏱</Text>
            <Text style={styles.summaryValue}>{MOCK_REPORT.totalMinutes}분</Text>
            <Text style={styles.summaryLabel}>운동 시간</Text>
          </View>
          <View style={styles.summaryCard}>
            <Text style={styles.summaryIcon}>🔥</Text>
            <Text style={styles.summaryValue}>{MOCK_REPORT.totalCalories}</Text>
            <Text style={styles.summaryLabel}>소모 칼로리</Text>
          </View>
        </View>

        {/* 종목별 싱크로율 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>종목별 싱크로율</Text>
          {MOCK_REPORT.exercises.map((ex) => (
            <View key={ex.name} style={styles.exerciseRow}>
              <View style={styles.exerciseInfo}>
                <Text style={styles.exerciseName}>{ex.name}</Text>
                <Text style={styles.exerciseSets}>{ex.sets}세트 × {ex.reps}회</Text>
              </View>
              <Text style={[styles.exerciseSync, { color: getSyncColor(ex.syncRate) }]}>
                {ex.syncRate}%
              </Text>
            </View>
          ))}
          {/* 바 게이지 */}
          {MOCK_REPORT.exercises.map((ex) => (
            <View key={`bar-${ex.name}`} style={styles.barRow}>
              <Text style={styles.barLabel}>{ex.name}</Text>
              <View style={styles.barBg}>
                <View
                  style={[
                    styles.barFill,
                    { width: `${ex.syncRate}%`, backgroundColor: getSyncColor(ex.syncRate) },
                  ]}
                />
              </View>
              <Text style={[styles.barValue, { color: getSyncColor(ex.syncRate) }]}>
                {ex.syncRate}%
              </Text>
            </View>
          ))}
        </View>

        {/* Worst 구간 */}
        {MOCK_REPORT.worstMoment && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>⚠️ Worst 구간</Text>
            <View style={styles.worstCard}>
              <View style={styles.worstHeader}>
                <FontAwesome name="warning" size={16} color={COLORS.warning} />
                <Text style={styles.worstTitle}>
                  {MOCK_REPORT.worstMoment.exercise} — {MOCK_REPORT.worstMoment.time}
                </Text>
              </View>
              <Text style={styles.worstDesc}>
                싱크로율 {MOCK_REPORT.worstMoment.syncRate}% · {MOCK_REPORT.worstMoment.issue}
              </Text>
            </View>
          </View>
        )}

        {/* AI 안전 리포트 */}
        {MOCK_REPORT.aiReport && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>🤖 AI 안전 리포트</Text>
            <View style={styles.aiCard}>
              <Text style={styles.aiText}>{MOCK_REPORT.aiReport}</Text>
            </View>
          </View>
        )}

        <View style={{ height: 40 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

function getSyncColor(rate: number) {
  if (rate >= 90) return COLORS.primary;
  if (rate >= 80) return COLORS.warning;
  return COLORS.error;
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
  title: { fontSize: FONT_SIZE.lg, fontWeight: '800', color: COLORS.text },
  date: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 2 },

  // Summary
  summaryGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: SPACING.md,
    paddingHorizontal: SPACING.xxl,
    marginTop: SPACING.md,
  },
  summaryCard: {
    width: '47%',
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    alignItems: 'center',
  },
  summaryCardPrimary: { borderColor: COLORS.primary },
  summaryIcon: { fontSize: 18, marginBottom: 4 },
  summaryValue: { fontSize: FONT_SIZE.xxl, fontWeight: '800', color: COLORS.text },
  summaryLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, marginTop: 4 },

  // Section
  section: { paddingHorizontal: SPACING.xxl, marginTop: SPACING.xxl },
  sectionTitle: { fontSize: FONT_SIZE.lg, fontWeight: '700', color: COLORS.text, marginBottom: SPACING.md },

  // Exercise rows
  exerciseRow: {
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
  exerciseSync: { fontSize: FONT_SIZE.xl, fontWeight: '800' },

  // Bar gauge
  barRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: SPACING.md,
    gap: SPACING.sm,
  },
  barLabel: { fontSize: FONT_SIZE.xs, color: COLORS.textSecondary, width: 80 },
  barBg: { flex: 1, height: 8, backgroundColor: COLORS.surfaceLight, borderRadius: 4 },
  barFill: { height: 8, borderRadius: 4 },
  barValue: { fontSize: FONT_SIZE.sm, fontWeight: '700', width: 40, textAlign: 'right' },

  // Worst
  worstCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
  },
  worstHeader: { flexDirection: 'row', alignItems: 'center', gap: SPACING.sm, marginBottom: SPACING.sm },
  worstTitle: { fontSize: FONT_SIZE.md, fontWeight: '700', color: COLORS.text },
  worstDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },

  // AI Report
  aiCard: {
    backgroundColor: COLORS.card,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
  },
  aiText: { fontSize: FONT_SIZE.md, color: COLORS.text, lineHeight: 24 },
});
