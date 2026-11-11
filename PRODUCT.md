# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack
Spring Boot 4.1.1 + Thymeleaf SSR, 바닐라 JS, 로컬 정적 리소스 (설계 문서 DESIGN.md 3장 기준)

## Users
보안 훈련과정 수강생·강사. 로컬에서 서비스를 구동하고 웹 보안과 보안 관제 자동화를 실습하는 사람. (추정: DESIGN.md 기준)

## Product Purpose
금융결제원 오픈뱅킹 테스트베드와 자체 원장 기반 가상계좌를 연동한 교육용 금융 서비스. 웹 애플리케이션 보안 설계와 보안 관제 자동화 연동(Graylog, Wazuh, n8n, pfSense)이 핵심 산출물.

## Positioning
실서비스가 아닌, 보안 설계를 처음부터 끝까지 보여 주는 학습용 금융 서비스.

## Capabilities and Constraints
- 계획된 기능: 회원가입·로그인(JWT HttpOnly 쿠키), 오픈뱅킹 계좌/잔액 조회, 가상계좌 발급, 원장 기반 입출금·이체
- 실제 배포와 실제 금융 거래 없음. 전 구성요소 로컬 구동
- CSP 호환: 인라인 script/style 금지, 정적 리소스는 로컬 서빙
- 기능은 단계적으로 구현 중 (현재 메인 화면 단계)

## Brand Commitments
- 이름: BastionPay
- 색상: 화이트 + 딥 네이비 (사용자 확정)
- 참조: 부산국제영화제(biff.kr)처럼 깔끔한 에디토리얼 레이아웃 (사용자 요청)

## Evidence on Hand
실제 고객, 후기, 수치, 수상 내역 없음. 화면의 계좌·거래 데이터는 모두 예시이며 "예시 데이터"로 표기해야 함.

## Product Principles
- 보안은 기능 위에 얹지 않고 설계의 출발점으로 둔다
- 교육용임을 숨기지 않는다
- 민감정보는 화면에서도 마스킹한다

## Accessibility & Inclusion
한국어 UI. 키보드 조작과 충분한 명도 대비 유지.
