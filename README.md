# QSMP Purpur 서버

Minecraft Java Edition `26.1.2`용 Purpur SMP 기본 구성입니다. 서버 쪽에서
플러그인을 사용할 수 있지만 친구들은 별도 모드 없이 바닐라 클라이언트로
접속할 수 있습니다.

친구들에게 보여줄 콘텐츠 사용법과 운영 문서는 [docs](docs)에 정리되어
있습니다.

## 현재 설정

- Minecraft/Purpur: `26.1.2`, 최신 빌드
- Java 요구 버전: `25` 이상, 64비트
- 메모리: 최소 `2G`, 최대 `4G`
- 포트: `25565`
- 필수 리소스팩 포트: `25566`
- Python 도구 실행: `uv` (`scripts/build_resource_pack.py` 의존성 자동 고정)
- 최대 인원: `10`
- 온라인 인증과 화이트리스트 활성화
- 난이도 `normal`, 게임 모드 `survival`

버전, 빌드, Java 경로, 메모리는 [server.env](server.env)에서 바꿉니다.
새 Minecraft 버전으로 올릴 때는 `MC_VERSION`을 변경한 후 업데이트 명령을
실행하세요. 플러그인 호환성과 월드 백업을 먼저 확인해야 합니다.

## 1. Java 25 준비

이 서버 버전은 Java 25 이상이 필요합니다. 시스템 Java를 바꾸지 않고 서버
폴더 안에 Eclipse Temurin 25를 자동 설치할 수 있습니다.

Windows:

```powershell
.\setup-java.ps1
```

Linux / macOS:

```bash
chmod +x setup-java.sh
./setup-java.sh
```

설치한 Java는 `runtime/java`에 저장되고 모든 관리 스크립트가 자동으로
사용합니다. 직접 설치한 Java를 쓰려면 [server.env](server.env)의
`JAVA_CMD`를 실행 파일 경로로 변경하세요.

설치 확인:

```text
java -version
```

출력 첫 줄의 버전이 `25` 이상이어야 합니다.

## 2. EULA 확인

[Minecraft EULA](https://aka.ms/MinecraftEULA)를 읽고 동의하는 경우에만
[eula.txt](eula.txt)의 값을 다음처럼 변경합니다.

```properties
eula=true
```

## 3. 다운로드와 실행

### Windows

1. `update.bat`을 실행해 Purpur를 다운로드합니다.
2. `check.bat`으로 준비 상태를 확인합니다.
3. `start.bat`으로 서버를 실행합니다.

PowerShell에서 직접 실행할 수도 있습니다.

```powershell
.\server.ps1 update
.\server.ps1 check
.\server.ps1 start
```

### Linux / macOS

처음 한 번 실행 권한을 설정합니다.

```bash
chmod +x server.sh
./server.sh update
./server.sh check
./server.sh start
```

## CI와 유지보수 확인

GitHub Actions는 push, pull request, 수동 실행 때 [scripts/ci.sh](scripts/ci.sh)를
실행합니다. 이 스크립트는 fresh clone에서도 `server.env`와 `eula.txt`가 없으면
CI 전용 기본값을 만들고, Purpur JAR 준비, 플러그인 매니저 self-test, 서버 업데이트
회귀 테스트, 플러그인 검증, 리소스팩 빌드, 커스텀 플러그인 빌드, `server.sh check`를
순서대로 수행합니다.

로컬에서 운영 전 빠른 확인:

```bash
bash scripts/ci.sh
```

서버가 이미 실행 중이면 플러그인 다운로드 같은 중단 필요 작업은 건너뛰고 현재 상태를
검사합니다. fresh clone이나 CI runner에서는 필요한 파일을 자동 준비합니다.

## 친구 접속 허용

서버 콘솔에서 각 친구의 Java Edition 닉네임을 화이트리스트에 추가합니다.

```text
whitelist add PlayerName
```

같은 집 밖에서 접속하려면 공유기에서 TCP `25565` 포트를 서버 PC로
포트 포워딩하고, 운영체제 방화벽에서도 해당 포트를 허용해야 합니다.
공인 IP를 공개 채널에 올리지 마세요.

QSMP 전용 리소스팩도 필수입니다. 외부 친구가 접속하려면 TCP `25566`도
열고, [server.env](server.env)의 `RESOURCE_PACK_PUBLIC_URL`을 서버 공인
IP나 도메인으로 바꿉니다.

```text
RESOURCE_PACK_PUBLIC_URL=http://your-domain.example:25566/qsmp-frontier-pack.zip
```

## 데이터팩 자동 주입

[datapacks.lock](datapacks.lock)에 고정된 Modrinth 데이터팩은 서버 시작,
주입, 상태 확인 때 누락된 ZIP을 자동 다운로드하고 SHA-512로 검증합니다.
[datapacks](datapacks) 폴더에 직접 넣은 ZIP 또는 압축을 푼 데이터팩
폴더도 함께 관리합니다. 각 팩의 최상위에는 `pack.mcmeta`가 있어야 합니다.
서버 시작 직전에 자동 검증한 뒤 현재 월드의 `datapacks` 폴더로 동기화합니다.
맵을 리셋할 때도 백업과 삭제가 끝난 직후 같은 원본에서 다시 주입하므로,
월드 폴더가 몇 번 바뀌어도 이 폴더의 데이터팩 구성은 유지됩니다.

수동 동기화와 상태 확인:

```powershell
.\world.ps1 inject
.\world.ps1 check
```

```bash
./world.sh inject
./world.sh check
```

원본 폴더에서 제거한 관리형 데이터팩은 다음 동기화 때 월드에서도
제거됩니다. 직접 월드 폴더에 넣은 비관리형 데이터팩은 건드리지 않습니다.

현재 모험 월드 구성은 다음과 같습니다.

- `Terralith + Terratonic`: 약 100개 오버월드 바이옴과 거대한 산맥/협곡
- `Continents`: 대륙 사이를 넓은 바다로 분리해 장거리 탐험과 원정 강화
- `Dungeons and Taverns`: 던전, 주점, 전투·탐험 구조물
- `Explorify`: 바닐라 분위기의 추가 탐험 구조물
- `Structory`: 폐허, 탑, 정착지 등 풍경 중심 구조물 추가
- `Nullscape`: 엔드 지형과 바이옴 전면 개편
- `True Ending`: 엔더 드래곤 전투와 엔딩 연출 개편
- `True Ending 26 Compat`: Minecraft 26.1 predicate 호환 패치
- `QSMP Rules`: 접속 인원의 50%가 잠들면 밤을 넘기는 영구 QoL 규칙

정확한 버전과 해시는 [datapacks.lock](datapacks.lock)에 고정합니다.
지형 팩은 생성된 월드에서 제거하면 월드가 손상될 수 있으므로, 업데이트나
교체 전에는 반드시 서버를 끄고 월드를 백업하세요.

## 맵 리셋

서버 콘솔에서 `stop`으로 정상 종료한 뒤 실행합니다. 현재 월드는
`backups/worlds`에 ZIP으로 백업하고 새 월드를 만들 준비를 합니다.

Windows:

```text
reset-world.bat
```

Linux / macOS:

```bash
./world.sh reset CONFIRM
```

리셋 명령은 데이터팩을 즉시 새 월드 폴더에 주입합니다. 다음 서버 시작
때도 한 번 더 동기화한 뒤 [server.properties](server.properties)의
`level-seed`와 `level-name` 설정으로 새 월드가 생성됩니다. 플러그인
설정과 복구 데이터는 월드 밖의 `plugins` 폴더에 있으므로 리셋해도
유지됩니다.

## 플러그인

기본 관리형 플러그인 팩:

- `Chunky`: 청크 사전 생성
- `AntiVillagerLag`: `Optimize` 이름표를 붙인 거래소 주민만 AI 비활성화
- `AttributeSwapFix`: Paper 계열의 속성 교체 동작을 바닐라 방식으로 복원
- `AxGraves`: 사망 지점에 30분간 본인 전용 무덤 생성, 경험치는 절반만 회수
- `AxInventoryRestore`: OP 전용 인벤토리/엔더 상자 복구 기록을 14일간 보관
- `LevelledMobs`: 야생 몹 1~50레벨, 거리·깊이·플레이어 진행도 기반 강화
- `QSMPCompanions`: 동료 레벨, 범용 갑옷, 자가 재생, 크라이오포드
- `QSMPFrontier`: 패링·구르기, 동료 역할, 자원 거점, 야외 전쟁 레이드
- `QSMP resource pack`: 현실풍 텍스처, UI sprite, 전투/레이드 사운드 레이어

Purpur에는 `spark` 프로파일러가 이미 포함되므로 별도 Spark JAR은 설치하지
않습니다. ClearLag류 플러그인은 엔티티를 삭제하거나 게임 틱을 바꾸므로
기본 팩에서 제외했습니다.

플러그인 버전 확인과 설치:

```powershell
.\plugins.ps1 resolve
.\plugins.ps1 update
.\plugins.ps1 check
```

```bash
chmod +x server.sh plugins.sh update-all.sh
./plugins.sh resolve
./plugins.sh update
./plugins.sh check
```

Windows에서는 각각 `plugins-resolve.bat`, `plugins-update.bat`,
`plugins-check.bat`을 실행해도 됩니다. Purpur와 플러그인을 모두 갱신하려면
Windows는 `update-all.bat`, Linux/macOS는 `./update-all.sh`를 사용합니다.

Minecraft 버전을 변경하면 먼저 `resolve`를 실행해야 합니다. 해당 버전을
지원하는 정식 릴리스가 하나라도 없으면 기존 잠금 파일을 유지하고
업데이트를 중단합니다.

### 모험 밸런스

무덤은 아이템을 즉시 돌려주거나 플레이어를 순간이동시키지 않습니다.
사망 지점까지 직접 돌아가야 하며, 30분이 지나면 내용물이 바닥에
떨어집니다. 경험치는 절반만 무덤에 저장됩니다. `keepInventory`, `/back`,
`/home`, 무작위 순간이동은 기본 구성에서 제외했습니다.

`AxInventoryRestore`는 일반 플레이어용 보험이 아니라 운영자 사고 복구
도구입니다. 복구 권한은 기본적으로 OP에게만 있고, 컨테이너를 닫을 때마다
백업하는 기능은 저장 공간을 아끼기 위해 꺼 두었습니다.

### Frontier 전투와 레이드

- 방패를 들고 손 바꾸기 키(`F`): 짧은 판정의 패링
- 달리는 중 웅크리기 한 번: 무적 판정이 포함된 구르기
- `Frontier Compass` 우클릭: 야생에 잠든 전장 방향 추적
- `Frontier Compass` 웅크린 우클릭: 봉인 유적 방향 추적
- `Tactical Whistle` 우클릭: 동료 역할 선택, 동료에게 우클릭해 지정

전장은 스폰에서 700~1100블록 떨어진 위치에 결정되며, 서버 시작 때 먼
청크를 강제로 생성하지 않습니다. 플레이어가 실제로 탐험해 주변을 로드하면
자연 지형 높이에 맞춰 길, 야영지, 요새가 건설됩니다. 전장 반경에 들어가
15초 동안 머무르면 전쟁 나팔과 함께 레이드가 자동 시작됩니다.

현재 로컬 전장은 QA용 `buildspawn`으로 `world x=80, z=-240`에 건설되어
있습니다. 레이드는 참가자를 추적하고, 중간 합류자를 보상/보스바 대상으로
추가하며, 전장이 45초 동안 비면 실패 처리 후 소환 몹을 정리합니다.

봉인 유적은 현재 `world x=116, z=-204`에 건설되어 있습니다. 플레이어는
나침반을 들고 웅크린 채 우클릭해 유적을 찾고, 주변 ward 3개를 파괴한 뒤
매복한 `Ruin Sentinel`을 정리해야 vault를 열 수 있습니다.

3번의 야외 공격 웨이브와 괴수 돌파전 뒤 `Iron Tyrant` 보스가 등장합니다.
보스는 참가 인원에 따라 체력이 증가하고 70%, 40% 체력에서 증원·격노
페이즈로 전환하며 광역 강타와 화살 일제사격을 사용합니다.

동료 역할은 `Vanguard` 전열 공격/방어, `Ranger` 후열 사격, `Medic`
범위 회복, `Gatherer` 채굴·벌목 드롭과 자원 거점 생산 증가로 나뉩니다.
길들인 생물과 전장 전용 몹은 `LevelledMobs`의 중복 능력치 강화를 받지
않습니다.

### 영속 자원 거점

벌목장, 채석장, 광산, 목장, 온실, 어장, 창고 코어를 제작해 배럴 형태의
자원 거점을 설치할 수 있습니다. 티어 II/III 업그레이드 키트로 생산량을
늘릴 수 있고, 넘친 생산물은 64블록 안의 소유 또는 공용 창고로 이동합니다.
광산 깊이, 어장의 물, 온실 채광, 주변 목재와 `Gatherer` 동료 레벨에 따라
생산 효율이 달라집니다.

아래 명령은 일반 플레이가 아니라 운영 복구와 테스트용입니다.

```text
frontier give <player> <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>
frontier outpost buildspawn <type>
frontier outpost pulse
frontier warfront buildspawn
frontier warfront start
frontier warfront stop
frontier expedition status
frontier expedition buildspawn
frontier expedition reset
```

맵 리셋 시 자원 거점 기록과 전장 좌표도 월드 백업에 포함된 뒤 초기화되며,
다음 월드에서 다시 건설할 수 있습니다.

### Chunky 최초 사용

서버를 시작한 뒤 콘솔에서 원하는 반경을 정하고 청크를 생성합니다.
친구 몇 명이 하는 SMP는 우선 반경 `5000` 블록 정도가 무난합니다.

```text
chunky world world
chunky radius 5000
chunky start
```

Nether와 End도 필요하면 해당 차원에서 별도로 실행합니다. 생성 중에는
CPU와 디스크를 많이 사용하므로 플레이어가 없을 때 실행하세요.

### 주민 최적화

거래소에 고정한 주민에게 `Optimize`라는 이름표를 붙이면 해당 주민만 AI가
비활성화됩니다. 이름을 다른 것으로 바꾸면 다시 활성화됩니다. 일반 주민,
주민 번식장, 철 농장 주민은 자동으로 변경하지 않습니다.

### 성능 진단

렉이 생기면 추측으로 플러그인을 더 설치하지 말고 서버 콘솔에서 실행합니다.

```text
spark profiler start --timeout 600
```

10분 후 생성되는 보고서로 실제 병목을 확인합니다.

`Terralith + Terratonic`에서는 `/locate biome`가 넓은 범위의 지형 노이즈를
동기 탐색하므로 서버가 약 30초 멈출 수 있습니다. 플레이 중에는 사용하지
말고, 필요한 위치는 서버가 비어 있을 때 찾으세요. 일반 청크 생성과
`/locate structure`는 이보다 훨씬 가볍습니다.

## 업데이트

먼저 콘솔에서 `stop`을 입력해 서버를 정상 종료한 뒤 실행합니다.

```powershell
.\server.ps1 update
```

```bash
./server.sh update
```

다운로드한 파일이 기존 파일과 다를 때만 교체하며, 이전 JAR은 `backups/`에
보관합니다. 맵 리셋 명령 외의 정기 월드/플러그인 데이터 백업은 별도로
운영해야 합니다.

Purpur 자동 다운로드에는 공식 API를 사용합니다:
`https://api.purpurmc.org/v2/purpur/`
