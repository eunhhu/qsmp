# QSMP Docs

이 문서는 현재 서버가 어떤 아크 스타일 시스템을 가지고 있고, 친구들이 실제로 어떻게 진행하면 되는지 정리합니다.

## 읽는 순서

1. [player-onboarding.md](player-onboarding.md): 친구들에게 바로 보여줄 진행 가이드
2. [systems.md](systems.md): 바뀐 시스템 전체 목록과 사용법
3. [operator-runbook.md](operator-runbook.md): 운영자 확인 명령, 테스트 명령, 현재 서버 상태
4. [content-roadmap.md](content-roadmap.md): ARK식 협동, 성장, 레이드 확장 방향

## 현재 핵심 루프

1. 새 청크 탐험으로 지형, 던전, 트라이얼 챔버 찾기
2. 동물을 길들여 동료 성장 시작
3. `Tactical Whistle`로 동료 역할 배정
4. 자원 거점 설치해서 기지 생산 자동화
5. `Frontier Compass` 웅크린 우클릭으로 봉인 유적 원정
6. `Frontier Compass` 일반 우클릭으로 전장 원정
7. 트라이얼 챔버, 봉인 유적, 야외 전쟁 레이드를 반복

## 현재 서버 스냅샷

- 기준일: 2026-06-17
- 서버: Purpur `26.1.2`
- 클라이언트: 바닐라 Java 클라이언트 접속 가능
- 전장 좌표: `world`, `x=80`, `z=-240`
- 전장 상태: 건설 완료, dormant
- 봉인 유적 좌표: `world`, `x=116`, `z=-204`
- 봉인 유적 상태: 건설 완료, sealed, ward 3개
- 자원 거점 수: 0
- 등록된 TrialChamberPro 챔버 수: 0
- 플레이어 직업 플러그인: 없음
- 직업처럼 보이는 것: 동료 역할 시스템

## 최근 변경

- `QSMPFrontier` 워프론트 레이드 안정화 1차 적용
- `QSMPFrontier` 봉인 유적 탐험 1차 추가
- `Frontier Compass` 웅크린 우클릭으로 유적 신호 탐색
- 유적 ward 3개 파괴, sentinel 정리, vault 보상 루프 추가
- 레이드 참가자 추적, 보상 대상 고정, 보스바 동기화 추가
- 레이드 몹이 비정상 제거되어도 다음 단계로 넘어가도록 watchdog 추가
- 전장이 비면 `participant-grace-seconds` 이후 실패 처리

## 큰 주의점

지형/구조물 데이터팩은 이미 생성된 청크를 바꾸지 않습니다. 이미 많이 돌아다닌 월드라면 새로 탐험하는 지역부터 Terralith, 던전, 구조물이 보입니다.

완전히 새 지형 흐름으로 시작하려면 서버를 끄고 월드 리셋을 해야 합니다.
