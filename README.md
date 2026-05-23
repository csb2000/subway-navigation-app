# subway-navigation-app

지하철역 보행지원 앱의 안드로이드 파트입니다. 시각장애인이 역 안에서 길을 찾을 수 있도록 Wi-Fi 신호로 위치를 추정하고 진동과 음성으로 방향을 안내합니다.

아주대학교 졸업프로젝트 (우당탕탕 팀, 2026)

## 개요

지하는 GPS가 안 잡혀서, 역 내부 Wi-Fi AP들의 신호 세기 패턴으로 현재 위치를 추정합니다. 위치를 알아내면 서버에서 목적지까지의 경로와 방향(각도)을 받아오고, 앱은 나침반 센서와 비교해서 사용자가 올바른 방향을 보고 있는지 진동으로 알려줍니다.

앱은 직접 계산하지 않고 센서 데이터를 서버에 보내 결과를 받아 안내만 합니다. 위치 추정(KNN), 경로 계산(Dijkstra), 방향 계산은 전부 서버 담당입니다.

## 동작 흐름

1. Wi-Fi 스캔으로 주변 AP 신호 수집
2. /locate 호출 → 현재 노드 확인
3. /route 호출 → 목적지까지 경로 받기
4. /direction 호출 → 다음 노드까지의 각도 받기
5. 나침반과 목표 각도를 비교해 진동으로 방향 유도
6. 올바른 방향이면 출발, 다음 노드 반복, 도착 시 안내

## 화면 구성

- MainActivity: 시작 화면. Wi-Fi 스캔 후 위치 측정, 경로 요청
- DirectionCheckActivity: 나침반으로 방향 확인. 목표 각도와 비교해 진동/음성 안내
- NavigationActivity: 경로를 따라 다음 노드로 안내
- ArriveActivity: 도착 안내

화면 간에는 경로(path), 간선 정보(edgeTypes), 현재 노드 인덱스를 Intent로 넘깁니다.

## 방향 안내 진동 패턴

서버가 알려준 절대 각도와 나침반 센서값의 차이에 따라 진동을 다르게 줍니다.

- 30도 초과: 진동 없음 (방향 탐색 중)
- 15~30도: 약한 진동
- 15도 이내: 강한 진동. 3초 유지하면 출발 가능

## 서버 API

Retrofit으로 서버의 3개 API를 호출합니다.

/locate — 현재 위치(노드) 확인

    POST /locate
    { "wifi": [ {"bssid": "...", "rssi": -65}, ... ] }
    → { "node": "B" }

/route — 경로 요청

    POST /route
    { "from": "A", "to": "F" }
    → { "path": ["A", "B", "C", "D", "F"] }

/direction — 방향(각도) 요청

    POST /direction
    { "from": "A", "to": "B" }
    → { "angle": 90 }

서버 주소는 ApiClient에서 설정합니다. 에뮬레이터는 10.0.2.2, 실기기는 실제 서버 IP를 써야 합니다.

## 권한

AndroidManifest.xml에 선언된 권한입니다.

- INTERNET: 서버 통신
- ACCESS_WIFI_STATE, CHANGE_WIFI_STATE: Wi-Fi 스캔
- ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION: Wi-Fi 스캔에 필요한 위치 권한 (런타임 요청)
- VIBRATE: 방향 안내 진동

위치 권한은 런타임 권한이라 앱 실행 시 팝업으로 허용을 받습니다.

## 폴더 구조

    app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/a260511/
    │   ├── MainActivity.kt
    │   ├── DirectionCheckActivity.kt
    │   ├── NavigationActivity.kt
    │   ├── ArriveActivity.kt
    │   └── ui/theme/apiclient.kt
    └── res/layout/

## 빌드 / 실행

    git clone https://github.com/csb2000/subway-navigation-app.git

Android Studio에서 열고 Gradle sync 후 에뮬레이터나 실기기에서 Run.

minSdk 24, targetSdk 36. 에뮬레이터는 가벼운 API 29(Pixel 4)를 권장합니다. 최신 이미지는 무거워서 렉이 심합니다.

## 에뮬레이터 테스트 (더미 모드)

에뮬레이터에는 실제 Wi-Fi AP가 없어서 측위가 안 됩니다. 그래서 MainActivity는 스캔 결과가 0개일 때 더미 위치(start_node → mid_node → down_platform)로 진행하도록 해놨습니다. 실기기에서는 실제 Wi-Fi가 잡히므로 더미 모드를 건너뛰고 서버 측위로 동작합니다.

## 기술 스택

- Kotlin
- Retrofit 2.9.0, Gson, OkHttp Logging Interceptor
- SensorManager (나침반), Vibrator, TextToSpeech

## 관련 저장소

서버: https://github.com/ajou-udangtangtang/subway-navigation-server
