# Skills.sh 가이드 (AI 코딩 에이전트 스킬)

## Skills.sh란?
[skills.sh](https://skills.sh/)는 AI 코딩 에이전트(Claude Code, Cursor, Copilot 등)에 전문 지식을 추가하는 스킬 생태계입니다. 설치된 스킬은 AI가 해당 기술에 대해 더 정확한 코드를 생성하도록 도와줍니다.

## 설치 방법
```bash
# 기본 설치 명령어 (프로젝트 루트에서 실행)
npx skills add <owner/repo>

# 특정 스킬만 설치
npx skills add <owner/repo> --skill <skill-name>
```

## ShadowFit 프로젝트에 추천하는 스킬

### Anthropic 공식 스킬
```bash
# 프론트엔드 디자인 가이드
npx skills add anthropics/skills --skill frontend-design

# 웹앱 테스트
npx skills add anthropics/skills --skill webapp-testing
```

### React Native
```bash
# React Native 베스트 프랙티스 (Callstack 제공, 8.7K+ installs)
npx skills add callstackincubator/agent-skills
# 포함 스킬: react-native-best-practices, github-actions
```

### Spring Boot / Java
```bash
# Spring Boot 스킬
npx skills add sivaprasadreddy/sivalabs-agent-skills
# 또는
npx skills add kousen/claude-code-training
```

### API 설계
```bash
# REST API 설계 원칙 (REST, GraphQL, Auth, OpenAPI)
npx skills add supercent-io/skills-template --skill api-design
```

### MySQL / 데이터베이스
```bash
# MySQL 스킬 (스키마, 인덱싱, 쿼리 튜닝, InnoDB)
npx skills add planetscale/database-skills --skill mysql
```

### Git / GitHub
```bash
# GitHub 워크플로
npx skills add callstackincubator/agent-skills --skill github
```

## 일괄 설치 스크립트
```bash
# 프로젝트 루트(shadowfit/)에서 실행
npx skills add anthropics/skills --skill frontend-design
npx skills add anthropics/skills --skill webapp-testing
npx skills add callstackincubator/agent-skills
npx skills add sivaprasadreddy/sivalabs-agent-skills
npx skills add supercent-io/skills-template --skill api-design
npx skills add planetscale/database-skills --skill mysql
```

## 사용 팁
1. 프로젝트 루트에서 설치해야 해당 프로젝트에 적용됨
2. 스킬은 `.claude/` 또는 `.cursorrules` 등 에이전트 설정 디렉토리에 저장됨
3. 팀원 모두 동일한 스킬을 설치하면 일관된 코드 품질 유지 가능
4. `npx skills list`로 설치된 스킬 확인 가능

> **참고**: skills.sh의 스킬 목록은 계속 업데이트됩니다.
> 사이트에서 직접 검색하여 최신 스킬을 확인하세요.
