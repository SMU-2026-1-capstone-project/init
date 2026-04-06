import { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Alert,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import { useRouter } from 'expo-router';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';
import type { ExerciseLevel, PersonaType, OnboardingData } from '@/types/user';

const TOTAL_STEPS = 4;

export default function OnboardingScreen() {
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [step, setStep] = useState(0);
  const [data, setData] = useState<OnboardingData>({
    exerciseLevel: null,
    targetWeight: 70,
    persona: null,
    referenceVideoUrl: '',
  });

  const canNext = () => {
    switch (step) {
      case 0: return data.exerciseLevel !== null;
      case 1: return true;
      case 2: return data.persona !== null;
      case 3: return true;
      default: return false;
    }
  };

  const handleNext = () => {
    if (step < TOTAL_STEPS - 1) {
      setStep(step + 1);
    } else {
      // TODO: API 호출로 온보딩 데이터 저장
      router.replace('/(tabs)');
    }
  };

  const handleBack = () => {
    if (step > 0) setStep(step - 1);
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* 상단 프로그레스 바 + 뒤로가기 */}
      <View style={styles.header}>
        {step > 0 ? (
          <TouchableOpacity onPress={handleBack} style={styles.backBtn}>
            <FontAwesome name="chevron-left" size={16} color={COLORS.text} />
          </TouchableOpacity>
        ) : (
          <View style={styles.backBtn} />
        )}
        <View style={styles.progressBar}>
          {Array.from({ length: TOTAL_STEPS }).map((_, i) => (
            <View
              key={i}
              style={[styles.progressDot, i <= step && styles.progressDotActive]}
            />
          ))}
        </View>
        <Text style={styles.stepText}>{step + 1}/{TOTAL_STEPS}</Text>
      </View>

      <ScrollView
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {step === 0 && <StepLevel data={data} setData={setData} />}
        {step === 1 && <StepWeight data={data} setData={setData} />}
        {step === 2 && <StepPersona data={data} setData={setData} />}
        {step === 3 && <StepVideo data={data} setData={setData} />}
      </ScrollView>

      {/* 하단 버튼 */}
      <View style={[styles.footer, { paddingBottom: SPACING.lg + insets.bottom }]}>
        <Button
          title={step === TOTAL_STEPS - 1 ? '완료' : '다음  >'}
          onPress={handleNext}
          disabled={!canNext()}
        />
      </View>
    </SafeAreaView>
  );
}

// ─── Step 1: 운동 수준 ──────────────────────────────
const LEVELS: { key: ExerciseLevel; label: string; desc: string }[] = [
  { key: 'BEGINNER', label: '입문', desc: '운동 자세, 운동 루틴 등 아무것도 몰라요' },
  { key: 'NOVICE', label: '초급', desc: '자세는 조금 알지만 무슨 운동을 해야 할지 몰라요' },
  { key: 'INTERMEDIATE', label: '중급', desc: '운동 자세를 잘 알고, 나만의 루틴이 있어요' },
  { key: 'ADVANCED', label: '고급', desc: '운동을 직업으로 삼을 만큼의 지식이 있어요' },
  { key: 'EXPERT', label: '전문가', desc: '운동 선수급의 지식과 경험을 갖고 있어요' },
];

function StepLevel({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  return (
    <View>
      <Text style={styles.stepIcon}>💪</Text>
      <Text style={styles.stepTitle}>운동 수준이 어떻게 되시나요?</Text>
      <Text style={styles.stepDesc}>적절한 운동 추천에 필요해요! 외부에 공개되지 않아요.</Text>

      {LEVELS.map((level) => (
        <TouchableOpacity
          key={level.key}
          style={[
            styles.optionCard,
            data.exerciseLevel === level.key && styles.optionCardActive,
          ]}
          onPress={() => setData({ ...data, exerciseLevel: level.key })}
          activeOpacity={0.7}
        >
          <Text style={[styles.optionLabel, data.exerciseLevel === level.key && styles.optionLabelActive]}>
            {level.label}
          </Text>
          <Text style={styles.optionDesc}>{level.desc}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ─── Step 2: 목표 몸무게 ─────────────────────────────
function StepWeight({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  const getMotivation = (weight: number) => {
    if (weight < 50) return '가벼운 몸을 목표로! 유산소 중심으로 도와드릴게요.';
    if (weight < 70) return '도전적인 목표: 몸무게의 70%로 중량이 목표예요!';
    if (weight < 90) return '탄탄한 체격을 목표로! 근력 운동을 추천해요.';
    return '파워 빌더! 고중량 훈련 루틴을 구성해드릴게요.';
  };

  return (
    <View>
      <Text style={styles.stepIcon}>🎯</Text>
      <Text style={styles.stepTitle}>목표 몸무게가 어떻게 되시나요?</Text>

      <View style={styles.motivationBox}>
        <Text style={styles.motivationIcon}>🏆</Text>
        <Text style={styles.motivationText}>{getMotivation(data.targetWeight)}</Text>
      </View>

      <Text style={styles.weightDisplay}>
        {data.targetWeight} <Text style={styles.weightUnit}>kg</Text>
      </Text>

      <Slider
        style={styles.slider}
        minimumValue={30}
        maximumValue={150}
        step={1}
        value={data.targetWeight}
        onValueChange={(v) => setData({ ...data, targetWeight: v })}
        minimumTrackTintColor={COLORS.primary}
        maximumTrackTintColor={COLORS.surfaceLight}
        thumbTintColor={COLORS.primary}
      />
      <View style={styles.sliderLabels}>
        <Text style={styles.sliderLabel}>30kg</Text>
        <Text style={styles.sliderLabel}>150kg</Text>
      </View>
    </View>
  );
}

// ─── Step 3: 트레이너 페르소나 ───────────────────────
const PERSONAS: { key: PersonaType; icon: string; label: string; desc: string }[] = [
  { key: 'FRIENDLY', icon: '🐣', label: '헬린이', desc: '친절하고 격려하는 초보 친화 코치' },
  { key: 'STRICT', icon: '🫡', label: 'FM 교관', desc: '엄격하고 체계적인 군대식 트레이너' },
  { key: 'REHAB', icon: '🏥', label: '재활 전문', desc: '안전 최우선, 부상 방지 중심 가이드' },
];

function StepPersona({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  return (
    <View>
      <Text style={styles.stepIcon}>👥</Text>
      <Text style={styles.stepTitle}>어떤 트레이너 스타일을 원하시나요?</Text>
      <Text style={styles.stepDesc}>페르소나에 따라 피드백 방식이 달라집니다.</Text>

      {PERSONAS.map((p) => (
        <TouchableOpacity
          key={p.key}
          style={[
            styles.optionCard,
            data.persona === p.key && styles.optionCardActive,
          ]}
          onPress={() => setData({ ...data, persona: p.key })}
          activeOpacity={0.7}
        >
          <View style={styles.personaRow}>
            <Text style={styles.personaIcon}>{p.icon}</Text>
            <View>
              <Text style={[styles.optionLabel, data.persona === p.key && styles.optionLabelActive]}>
                {p.label}
              </Text>
              <Text style={styles.optionDesc}>{p.desc}</Text>
            </View>
          </View>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ─── Step 4: 기준 영상 ──────────────────────────────
function StepVideo({
  data,
  setData,
}: {
  data: OnboardingData;
  setData: React.Dispatch<React.SetStateAction<OnboardingData>>;
}) {
  return (
    <View>
      <Text style={styles.stepIcon}>📹</Text>
      <Text style={styles.stepTitle}>기준 영상을 설정해주세요</Text>
      <Text style={styles.stepDesc}>따라 하고 싶은 운동 영상을 등록하세요. 나중에 변경할 수 있어요.</Text>

      {/* 촬영 가이드라인 */}
      <View style={styles.guideBox}>
        <Text style={styles.guideTitle}>📌 영상 촬영 가이드</Text>
        <View style={styles.guideItem}>
          <Text style={styles.guideBullet}>📐</Text>
          <Text style={styles.guideText}>정면 또는 측면(45°)에서 촬영된 영상</Text>
        </View>
        <View style={styles.guideItem}>
          <Text style={styles.guideBullet}>🧍</Text>
          <Text style={styles.guideText}>전신이 보이도록 1.5m 이상 거리 확보</Text>
        </View>
        <View style={styles.guideItem}>
          <Text style={styles.guideBullet}>💡</Text>
          <Text style={styles.guideText}>밝은 조명, 단색 배경 권장</Text>
        </View>
        <View style={styles.guideItem}>
          <Text style={styles.guideBullet}>🚫</Text>
          <Text style={styles.guideText}>거울 반사, 여러 사람이 보이는 영상은 피해주세요</Text>
        </View>
      </View>

      <Text style={styles.inputLabel}>🔗 유튜브 링크</Text>
      <TextInput
        style={styles.urlInput}
        placeholder="https://youtube.com/watch?v=..."
        placeholderTextColor={COLORS.textPlaceholder}
        value={data.referenceVideoUrl}
        onChangeText={(v) => setData({ ...data, referenceVideoUrl: v })}
        autoCapitalize="none"
        keyboardType="url"
      />

      <View style={styles.dividerRow}>
        <View style={styles.dividerLine} />
        <Text style={styles.dividerText}>또는</Text>
        <View style={styles.dividerLine} />
      </View>

      <Text style={styles.inputLabel}>📤 MP4 파일 업로드</Text>
      <TouchableOpacity
        style={styles.uploadBox}
        onPress={() => Alert.alert('파일 선택', '추후 구현 예정')}
        activeOpacity={0.7}
      >
        <FontAwesome name="cloud-upload" size={32} color={COLORS.textMuted} />
        <Text style={styles.uploadText}>탭하여 MP4 파일 선택</Text>
        <Text style={styles.uploadHint}>최대 100MB</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.background },
  scrollContent: { flexGrow: 1, paddingHorizontal: SPACING.xxl, paddingBottom: 100 },

  // Header
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
  },
  backBtn: { width: 32 },
  progressBar: { flex: 1, flexDirection: 'row', gap: 6, marginHorizontal: SPACING.md },
  progressDot: {
    flex: 1,
    height: 4,
    backgroundColor: COLORS.surfaceLight,
    borderRadius: 2,
  },
  progressDotActive: { backgroundColor: COLORS.primary },
  stepText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.sm, width: 32, textAlign: 'right' },

  // Step common
  stepIcon: { fontSize: 28, marginTop: SPACING.xl, marginBottom: SPACING.md },
  stepTitle: {
    fontSize: FONT_SIZE.xl,
    fontWeight: '800',
    color: COLORS.text,
    marginBottom: SPACING.sm,
  },
  stepDesc: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    marginBottom: SPACING.xxl,
  },

  // Option cards
  optionCard: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.md,
  },
  optionCardActive: {
    borderColor: COLORS.primary,
    backgroundColor: COLORS.primaryDim,
  },
  optionLabel: {
    fontSize: FONT_SIZE.md,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: 2,
  },
  optionLabelActive: { color: COLORS.primary },
  optionDesc: { fontSize: FONT_SIZE.sm, color: COLORS.textSecondary },

  // Persona
  personaRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.md },
  personaIcon: { fontSize: 28 },

  // Weight
  motivationBox: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    padding: SPACING.lg,
    marginBottom: SPACING.xxl,
    gap: SPACING.md,
  },
  motivationIcon: { fontSize: 24 },
  motivationText: { flex: 1, fontSize: FONT_SIZE.sm, color: COLORS.textSecondary, lineHeight: 20 },
  weightDisplay: {
    fontSize: 56,
    fontWeight: '800',
    color: COLORS.text,
    textAlign: 'center',
    marginBottom: SPACING.lg,
  },
  weightUnit: { fontSize: FONT_SIZE.xl, fontWeight: '400', color: COLORS.textSecondary },
  slider: { width: '100%', height: 40 },
  sliderLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  sliderLabel: { fontSize: FONT_SIZE.sm, color: COLORS.textMuted },

  // Video
  inputLabel: {
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    marginBottom: SPACING.sm,
  },
  urlInput: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    color: COLORS.text,
    fontSize: FONT_SIZE.md,
  },
  dividerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginVertical: SPACING.xxl,
    gap: SPACING.md,
  },
  dividerLine: { flex: 1, height: 1, backgroundColor: COLORS.cardBorder },
  dividerText: { color: COLORS.textMuted, fontSize: FONT_SIZE.sm },
  uploadBox: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    borderStyle: 'dashed',
    paddingVertical: SPACING.xxxl,
    alignItems: 'center',
    gap: SPACING.sm,
  },
  uploadText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.md },
  uploadHint: { color: COLORS.textMuted, fontSize: FONT_SIZE.xs },

  // Guide
  guideBox: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.md,
    borderWidth: 1,
    borderColor: COLORS.cardBorder,
    padding: SPACING.lg,
    marginBottom: SPACING.xxl,
    gap: SPACING.sm,
  },
  guideTitle: {
    fontSize: FONT_SIZE.sm,
    fontWeight: '700',
    color: COLORS.text,
    marginBottom: SPACING.xs,
  },
  guideItem: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: SPACING.sm,
  },
  guideBullet: { fontSize: 14 },
  guideText: {
    flex: 1,
    fontSize: FONT_SIZE.sm,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },

  // Footer
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingHorizontal: SPACING.xxl,
    paddingVertical: SPACING.lg,
    backgroundColor: COLORS.background,
  },
});
