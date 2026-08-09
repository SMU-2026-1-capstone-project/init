// 세션 정체성 버전 (이슈 #170).
//
// «지금 화면에 로그인돼 있는 사람» 이 바뀔 때마다 올라가는 단조 카운터다. 로그인·로그아웃·
// 강제 로그아웃 세 곳에서만 올린다 — 재발급은 세션을 «이어서 쓰는 것» 이라 올리지 않는다.
//
// 왜 필요한가: 재발급은 refresh 를 읽은 뒤 네트워크를 기다리는데, 그 사이에 사용자가
// 로그아웃하거나 다른 계정으로 로그인할 수 있다. 대조할 값이 없으면 옛 세션의 응답이
// **새 세션의 토큰을 덮어쓴다** — 화면에는 B 가 떠 있는데 Authorization 은 A 가 된다.
//
// 왜 authStore 가 아니라 별도 모듈인가: `api.ts` 가 `authStore` 를 정적으로 import 하면
// authStore → authService → api 순환이 생긴다(그래서 지금 코드가 `require()` 를 쓴다).
// 이 모듈은 의존성이 0 이라 양쪽이 그냥 import 할 수 있고, 버전 읽기는 **모든 요청마다**
// 도는 자리라 더 단순한 쪽을 둔다.
let version = 0;

export function currentSessionVersion(): number {
  return version;
}

export function bumpSessionVersion(): void {
  version += 1;
}