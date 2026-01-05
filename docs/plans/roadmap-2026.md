# Claude Code Native Roadmap 2026

## Phase 5: Advanced Intelligence & Performance (고도화)

### 1. 성능 최적화: 스트리밍 및 대용량 처리
*   **목표**: 대용량 로그/코드 분석 시 WebSocket 지연 시간 최소화.
*   **계획**:
    *   **Binary WebSocket 도입**: JSON 대신 Protobuf 또는 MessagePack을 사용하여 페이로드 크기 축소.
    *   **Gzip 압축**: 텍스트 스트리밍 데이터에 대한 압축 전송 활성화.
    *   **Incremental Parsing**: 프론트엔드에서 전체 메시지가 아닌 변경된 델타만 렌더링하도록 최적화.
*   **난관 및 해결**:
    *   *난관*: KMP(Kotlin/JS) 환경에서의 Protobuf 라이브러리 호환성 및 오버헤드.
    *   *해결*: `kotlinx.serialization`과 호환되는 경량 Protobuf 구현체 사용 및 성능 벤치마킹.

### 2. 기능 확장: 멀티 세션 및 자동 요약
*   **목표**: 개발 생산성을 위한 관리 기능 강화.
*   **계획**:
    *   **Tab-based Multi-session**: 여러 프로젝트나 대화를 동시에 관리할 수 있는 탭 UI 구현.
    *   **Context Summary**: Claude API를 활용하여 장기 대화의 핵심 내용을 요약하고 세션 시작 시 컨텍스트로 주입.
    *   **Local File Watcher**: `fsnotify`(Go)를 사용하여 로컬 파일 변경 시 Claude에게 자동 통지 옵션 제공.
*   **난관 및 해결**:
    *   *난관*: 다중 프로세스 관리 시 백엔드 리소스(CPU, Memory) 증가.
    *   *해결*: Idle 세션 자동 일시정지(Hibernation) 로직 도입 및 세션 데이터 SQLite 영속화.

---

## Phase 6: Distribution & Ecosystem (배포 및 최적화)

### 1. 인프라: 배포 자동화 및 업데이트
*   **목표**: 안정적인 배포 프로세스와 사용자 자가 업데이트 지원.
*   **계획**:
    *   **CI/CD Pipeline**: GitHub Actions를 통해 Docker 이미지 빌드 및 각 플랫폼별(Desktop, Android) 바이너리 자동 서명 및 릴리스.
    *   **Auto-upgrade**: Sparkle(macOS/Windows) 및 AppCenter(Android) 기반의 인앱 업데이트 기능 통합.
    *   **Dockerize Everything**: 백엔드와 데이터베이스를 한 번에 띄울 수 있는 최적화된 Docker Compose 환경 구축.
*   **난관 및 해결**:
    *   *난관*: KMP 데스크톱 앱의 플랫폼별 서명(Signing) 및 공증(Notarization) 복잡성.
    *   *해결*: Gradle `compose.desktop` 플러그인의 배포 설정을 활용하고, CI 상에서 Secrets를 통한 자동 서명 구축.

### 2. UI/UX: 개발자 경험 극대화
*   **목표**: 네이티브 앱만의 부드러운 UI와 즉각적인 피드백 제공.
*   **계획**:
    *   **Code Highlighting 2.0**: `Treesitter` 기반의 고성능 구문 강조 도입 (Desktop 한정).
    *   **Global Error Handling**: 전역 Snackbar 및 비동기 작업 에러 발생 시 상세 다이얼로그(Retry 로직 포함) 제공.
    *   **Keyboard First Navigation**: 모든 기능을 키보드 단축키로 제어할 수 있는 명령어 팔레트(Command Palette) 도입.
*   **난관 및 해결**:
    *   *난관*: 대용량 코드 렌더링 시 UI 프리징 현상.
    *   *해결*: `LazyColumn` 최적화 및 렌더링 가상화(Virtualization), 텍스트 렌더링 엔진 튜닝.
