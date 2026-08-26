import { Link } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import React from 'react';
import { Platform } from 'react-native';

export function ExternalLink(
  props: Omit<React.ComponentProps<typeof Link>, 'href'> & { href: string }
) {
  return (
    <Link
      target="_blank"
      {...props}
      // expo-router의 타입드 라우트(app.json experiments.typedRoutes)는 앱 안의 route만
      // 리터럴 유니온으로 만든다 — 외부 URL은 그 유니온에 못 들어간다. 그리고 그 유니온은
      // `.expo/types/router.d.ts`(gitignore 대상, expo start/export가 생성)가 있어야
      // 존재해서, 로컬(생성됨)과 CI(미생성)에서 타입 강도가 달라진다. `@ts-expect-error`는
      // 그 강도에 따라 "억제할 오류 있음/없음"이 갈려 어느 한쪽에서 "미사용 지시자" 오류가
      // 되므로(#327), 두 조건 모두에서 유효한 명시적 캐스트로 대체한다.
      href={props.href as React.ComponentProps<typeof Link>['href']}
      onPress={(e) => {
        if (Platform.OS !== 'web') {
          // Prevent the default behavior of linking to the default browser on native.
          e.preventDefault();
          // Open the link in an in-app browser.
          WebBrowser.openBrowserAsync(props.href as string);
        }
      }}
    />
  );
}
