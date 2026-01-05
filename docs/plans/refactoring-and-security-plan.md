# Step-by-Step Refactoring Plan: ChatViewModel Separation

`ChatViewModel.kt`의 비대화를 해결하고 관심사를 분리하기 위한 단계별 계획입니다.

## 1단계: 통신 레이어(Network/WebSocket) 분리
*   **목표**: ViewModel에서 직접 호출하는 API 및 WebSocket 핸들링 로직을 서비스 클래스로 이동.
*   **작업**:
    *   `ChatService` 클래스 생성: `sendMessage`, `stopGeneration`, `loadMoreMessages` 로직 이동.
    *   `WebSocketHandler` 인터페이스 강화: `ChatViewModel` 내부의 `handleIncomingMessage` 로직을 별도의 클래스로 캡슐화.

## 2단계: 상태 관리(State Management) 최적화
*   **목표**: `ChatViewModel`의 수많은 `MutableStateFlow`를 하나의 `UiState` 데이터 클래스로 통합하거나 기능별로 분리.
*   **작업**:
    *   `ChatUiState` 정의: 메시지 목록, 로딩 상태, 에러 상태, 연결 상태 등을 포함.
    *   `MessageStore` 및 `SessionStateManager`와의 역할 분담 명확화.

## 3단계: 도메인 로직(Domain Logic/UseCase) 추출
*   **목표**: 비즈니스 규칙(예: 메시지 파싱, 툴 사용 트래킹)을 UseCase로 분리.
*   **작업**:
    *   `ParseMessageUseCase`: 메시지 텍스트에서 툴 호출/결과를 추출하는 로직.
    *   `ExecuteCommandUseCase`: CLI 명령어 실행 및 결과 처리 로직.

## 4단계: 테스트 코드 작성 및 검증
*   **목표**: 분리된 클래스들에 대해 단위 테스트 수행.
*   **작업**:
    *   `ChatServiceTest`: API 호출 실패 시 재시도 로직 검증.
    *   `ParseMessageUseCaseTest`: 다양한 마크다운 및 툴 호출 형식 파싱 검증.

---

# Multi-platform Secure Storage Architecture Plan

보안 취약점이 발견된 `TokenStorage`를 개선하기 위한 설계안입니다.

## 플랫폼별 구현 전략
1.  **Android**: `EncryptedSharedPreferences` (Jetpack Security) 활용.
2.  **iOS/macOS**: `Keychain` (Security framework) 활용.
3.  **Windows**: `Data Protection API (DPAPI)` 또는 `Credential Locker` 활용.
4.  **Linux**: `libsecret` (Secret Service API) 연동.
5.  **WASM(Web)**: `HttpOnly Cookie` (백엔드 지원 필요) 또는 제한적인 메모리 내 저장.

## 구현 단계
1.  `commonMain`에 `SecureStorage` 인터페이스 정의.
2.  각 플랫폼 모듈(`androidMain`, `desktopMain` 등)에서 `actual` 구현체 작성.
3.  Koin DI 설정을 통해 `TokenStorage`를 `SecureStorage` 기반으로 교체.
