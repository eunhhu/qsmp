# 운영자 런북

운영자 확인, 테스트, 복구 명령만 모았습니다.

## 기본 확인

서버가 꺼진 상태에서:

```bash
./server.sh check
./plugins.sh check
./world.sh check
```

현재 확인된 상태:

- Java 25 OK
- 서버 JAR 있음
- EULA 동의됨
- 서버 프로세스 stopped
- 관리 플러그인 해시 OK
- 관리 데이터팩 8개 injected

## 실제 기동 확인

서버 시작:

```bash
./server.sh start
```

콘솔에서:

```text
datapack list enabled
frontier status
tcp list
```

기대:

- `datapack list enabled`: Terralith, Terratonic, Continents, Dungeons and Taverns, Explorify, Structory, Nullscape, QSMP Rules 표시
- `frontier status`: 전장 상태와 거점 수 표시
- `tcp list`: 발견된 챔버 목록 표시

종료:

```text
stop
```

## 현재 콘텐츠 상태

2026-06-17 기준:

```text
QSMP Frontier | Warfront: dormant at 80, -240 | Expedition: sealed at 116, -204, wards 3, sentinels 0 | Outposts: 0
TrialChamberPro | No chambers registered yet
```

뜻:

- 야외 전장 위치는 스폰 테스트용 좌표에 건설되어 있습니다.
- 레이드가 진행 중이 아니며, 쿨다운도 없습니다.
- 봉인 유적은 스폰 테스트용 좌표에 건설되어 있고 아직 sealed 상태입니다.
- 아직 자연 트라이얼 챔버가 발견/등록되지 않았습니다.

## Frontier 운영 명령

상태:

```text
/frontier status
/frontier warfront status
/frontier expedition status
```

전장 테스트:

```text
/frontier warfront buildspawn
/frontier warfront start
/frontier warfront stop
```

주의:

- `buildspawn`은 스폰 근처에 테스트 전장을 만듭니다.
- 실제 탐험 루프를 보려면 `Frontier Compass`를 따라가게 두는 편이 낫습니다.
- `start`는 주변 참가자가 없어도 레이드를 시작합니다.
- 참가자가 `participant-grace-seconds` 동안 전장에 없으면 레이드는 실패하고 소환 몹과 보스바를 정리합니다.
- `stop`은 active 여부와 관계없이 남은 전장 엔티티와 보스바를 정리합니다.

검증된 QA 절차:

```text
frontier warfront status
frontier warfront buildspawn
frontier warfront start
frontier warfront status
```

기대:

- 시작 직후 `active stage 1, enemies <n>, participants <n>` 표시
- 플레이어 없이 시작하면 약 45초 뒤 `WARFRONT FAILED: The field was abandoned.` 표시
- 이후 `frontier warfront status`가 `dormant at 80, -240`로 돌아옴

레이드 관련 설정:

```yaml
warfront:
  participant-radius: 140.0
  participant-grace-seconds: 45
  transition-delay-ticks: 100
```

- `participant-radius`: 이 반경 안에 있으면 레이드 참가자로 등록되고 보스바 대상이 됩니다.
- `participant-grace-seconds`: 마지막 참가자가 전장을 벗어난 뒤 실패까지 기다리는 시간입니다.
- `transition-delay-ticks`: 웨이브가 비었을 때 다음 단계로 넘어가기 전 대기 시간입니다.

봉인 유적 테스트:

```text
/frontier expedition status
/frontier expedition buildspawn
/frontier expedition reset
```

주의:

- `buildspawn`은 스폰 근처에 봉인 유적을 만듭니다.
- 일반 플레이어는 `Frontier Compass`를 들고 웅크린 채 우클릭해서 유적을 찾습니다.
- ward는 `Crying Obsidian`입니다.
- ward 3개를 깨면 `Ruin Sentinel` 매복이 발생합니다.
- 모든 ward와 sentinel이 없어지면 vault가 열립니다.

검증된 QA 절차:

```text
frontier expedition status
frontier expedition buildspawn
frontier expedition status
```

기대:

- `sealed at <x>, <z>, wards 3, sentinels 0` 표시
- `plugins/QSMPFrontier/expedition.yml`에 vault와 ward 좌표 저장
- ward 블록이 사라지면 다음 tick에서 `RUIN: The vault seal is broken.` 표시

탐험 관련 설정:

```yaml
expeditions:
  discovery-radius: 96.0
  reward-radius: 48.0
  cooldown-minutes: 90
  sentinel-base-count: 2
  sentinel-count-per-player: 1
```

거점 지급:

```text
/frontier give <player> <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>
```

공용 거점 생성:

```text
/frontier outpost buildspawn <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>
```

생산 강제 실행:

```text
/frontier outpost pulse
```

동료 역할 수리:

```text
/frontier role <vanguard|ranger|medic|gatherer>
```

플레이어가 바라보는 내 동료에게 역할을 붙입니다. 일반 플레이에서는 휘슬 사용을 권장합니다.

## Companion 운영 명령

동료 정보:

```text
/companion info
```

아이템 지급:

```text
/companion give <player> <cryopod|iron|diamond|netherite|charm>
```

Happy Ghast 수리 결속:

```text
/companion claim
```

설정 리로드:

```text
/companion reload
```

## TrialChamberPro 운영 명령

권한 주의:

- `tcp.stats`, `tcp.leaderboard`는 기본 공개 권한입니다.
- 현재 JAR 메타데이터에는 `/tcp` 루트 명령 권한이 `tcp.admin`으로도 선언되어 있습니다.
- 비OP가 `/tcp stats`나 `/tcp leaderboard`를 못 쓰면 권한 레이어에서 `/tcp` 접근을 풀어야 합니다.

목록:

```text
/tcp list
```

정보:

```text
/tcp info [chamber]
```

GUI:

```text
/tcp menu
```

강제 리셋:

```text
/tcp reset <chamber>
```

스냅샷:

```text
/tcp snapshot create <chamber>
/tcp snapshot update <chamber>
/tcp snapshot restore <chamber>
```

챔버 일시 정지:

```text
/tcp pause <chamber>
/tcp resume <chamber>
```

보상 테이블:

```text
/tcp loot list
/tcp loot info <chamber>
/tcp loot set <chamber> <normal|ominous> <table>
/tcp loot clear <chamber> [normal|ominous|all]
```

키 지급:

```text
/tcp key give <player> <amount> [normal|ominous]
```

리로드:

```text
/tcp reload
```

주의:

- 현재 WorldEdit/FAWE가 없어서 schematic paste 기능은 비활성입니다.
- 자연 챔버 반복 플레이에는 WorldEdit이 필요 없습니다.

## Chunky 운영

오버월드 5000블록 예시:

```text
chunky world world
chunky radius 5000
chunky start
```

네더/엔드도 별도 실행합니다.

```text
chunky world world_nether
chunky radius 2500
chunky start
```

```text
chunky world world_the_end
chunky radius 2500
chunky start
```

중단:

```text
chunky pause
chunky cancel
```

## 데이터팩 관리

주입:

```bash
./world.sh inject
```

상태:

```bash
./world.sh check
```

월드 리셋:

```bash
./world.sh reset CONFIRM
```

주의:

- 리셋은 기존 월드를 `backups/worlds`에 백업하고 삭제합니다.
- TrialChamberPro 월드 상태와 Frontier 전장/거점 상태도 초기화됩니다.
- 완전히 새 지형을 원할 때만 사용합니다.

## 플러그인 관리

최신 호환 버전 resolve:

```bash
./plugins.sh resolve
```

다운로드/설치:

```bash
./plugins.sh update
```

해시 확인:

```bash
./plugins.sh check
```

현재 lock:

- Chunky `1.5.3`
- AntiVillagerLag `3.0.8`
- AttributeSwapFix `1.1.0`
- AxGraves `1.28.1`
- AxInventoryRestore `3.13.0`
- TrialChamberPro `1.5.9-mc26`
- LevelledMobs `4.5.3`

## 테스트 체크리스트

새 콘텐츠 확인:

1. 서버 시작
2. `datapack list enabled`로 데이터팩 활성 확인
3. 새 청크 탐험
4. `Frontier Compass` 우클릭 확인
5. 동물 길들이기
6. `/companion info` 또는 빈손 웅크리기 우클릭 확인
7. `Tactical Whistle` 역할 순환/배정 확인
8. 거점 코어 지급 후 설치 확인
9. `/frontier outpost pulse`로 생산 확인
10. 전장 주변 로드 후 자동 건설 확인
11. 자연 트라이얼 챔버 발견 후 `/tcp list` 확인

서버 로그에서 무시 가능한 것:

- `Could not save messages.yml because messages.yml already exists`
- `Could not save loot.yml because loot.yml already exists`
- `WorldEdit/FAWE not found - schematic features disabled`

무시하면 안 되는 것:

- `messages.yml missing key`
- `config could not be parsed`
- `Datapack check failed`
- `Plugin lock mismatch`
- `Java version 21 found`
