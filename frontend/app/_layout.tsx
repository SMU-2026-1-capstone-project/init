import FontAwesome from '@expo/vector-icons/FontAwesome';
import { DarkTheme, ThemeProvider } from '@react-navigation/native';
import { useFonts } from 'expo-font';
import { Stack, useRouter, useSegments } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect } from 'react';
import 'react-native-reanimated';
import { COLORS } from '@/constants/Colors';
import { useAuthStore } from '@/stores/authStore';

export { ErrorBoundary } from 'expo-router';

export const unstable_settings = {
  initialRouteName: '(auth)',
};

SplashScreen.preventAutoHideAsync();

// ShadowFit 다크 테마
const ShadowFitTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    primary: COLORS.primary,
    background: COLORS.background,
    card: COLORS.surface,
    text: COLORS.text,
    border: COLORS.border,
  },
};

export default function RootLayout() {
  const [loaded, error] = useFonts({
    SpaceMono: require('../assets/fonts/SpaceMono-Regular.ttf'),
    ...FontAwesome.font,
  });

  useEffect(() => {
    if (error) throw error;
  }, [error]);

  useEffect(() => {
    if (loaded) SplashScreen.hideAsync();
  }, [loaded]);

  if (!loaded) return null;

  return <RootLayoutNav />;
}

function RootLayoutNav() {
  const router = useRouter();
  const segments = useSegments();
  const { isAuthenticated, isLoading, restoreSession } = useAuthStore();

  useEffect(() => {
    restoreSession();
  }, []);

  useEffect(() => {
    if (isLoading) return;

    const inAuth = segments[0] === '(auth)';

    if (isAuthenticated && inAuth) {
      router.replace('/(tabs)');
    } else if (!isAuthenticated && !inAuth && !__DEV__) {
      router.replace('/(auth)/login');
    }
  }, [isAuthenticated, isLoading, segments]);

  return (
    <ThemeProvider value={ShadowFitTheme}>
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="(auth)" />
        <Stack.Screen name="(onboarding)" />
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="report/[id]" />
        <Stack.Screen name="board/notice" />
        <Stack.Screen name="board/qna" />
        <Stack.Screen name="modal" options={{ presentation: 'modal' }} />
      </Stack>
    </ThemeProvider>
  );
}
