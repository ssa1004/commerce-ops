# auth-service stub (JWK Set)

`mini-shop-observability` 의 *통합 데모* 에서 [auth-service](https://github.com/ssa1004/auth-service) 자리를 채우는 정적 stub. 실제 OAuth2 / OIDC 서버를 띄우지 않고, JWK Set endpoint 만 흉내 냅니다.

## 무엇을 노출하나

`docker-compose.integration.yml` 의 `auth-stub` 컨테이너 (nginx) 가 포트 `9000` 에서:

| 경로 | 응답 |
|---|---|
| `GET /.well-known/jwks.json` | RS256 demo public key 1 개 (`kid=minishop-demo-2026-05`) |
| `GET /.well-known/openid-configuration` | OIDC discovery 의 최소 형태 (issuer + jwks_uri 만) |

같은 호스트 네트워크에 있는 sister repo 의 service 들이 `spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://auth-stub:9000/.well-known/jwks.json` 으로 가리켜 JWT 검증을 통합 시연할 수 있게 하는 자리입니다.

## 왜 stub 인가

- 본 레포는 *starter provider* 지 IdP (Identity Provider — OAuth2 토큰 발행 주체) 가 아닙니다. 자체 multi-service (order/payment/inventory) 도 JWT 를 요구하지 않음
- 그러나 통합 데모에서 다른 7 레포가 본 레포의 옵저버빌리티 stack 위에 올라타는 그림을 보이려면, JWT 검증의 *접점* 인 JWK Set 이 어딘가에서 제공돼야 함
- 진짜 auth-service 를 띄우는 것은 컨테이너 이미지 / DB 마이그레이션 / Redis 까지 같이 끌고 와야 해서 데모 시연으로는 무거움 — JWK Set 은 정적 JSON 한 줄로 충분

## 데모 키 (committed)

`jwks.json` 의 RSA public key 는 *데모 전용* 으로 한 번 생성된 고정 값입니다. 같은 키페어의 private 절반은 *git 에 저장하지 않습니다* — 본 레포의 어떤 서비스도 이 키로 토큰을 *발행* 하지 않기 때문 (검증 측만 시연). 다른 레포 통합 시 진짜 auth-service 가 자기 JWK rotation 으로 발행한 토큰이 들어오는 시나리오를 가정합니다.

`scripts/integration-demo.sh` 는 trace 전파 시연 목적으로 *header decoration* 형태의 dummy JWT 만 붙입니다 (검증 안 함). 진짜 검증이 필요한 sister repo 통합은 그쪽 레포의 application 설정에서 `jwk-set-uri` 만 이 stub 으로 가리키면 됩니다.

## Rotate

데모 키를 새로 만들고 싶으면:

```bash
openssl genrsa -out /tmp/new_priv.pem 2048
openssl rsa -in /tmp/new_priv.pem -noout -modulus | sed 's/^Modulus=//' | xxd -r -p | base64 | tr '/+' '_-' | tr -d '='
# 위 출력을 jwks.json 의 "n" 에 넣고, "kid" 를 갱신
shred -u /tmp/new_priv.pem   # macOS: rm -P
```

`kid` 는 `minishop-demo-YYYY-MM` 형식. consumer 는 `kid` 매칭으로 키를 고르므로 rotate 시 옛 `kid` 도 일정 기간 함께 노출하면 무중단 — 데모 환경엔 단일 키로 충분.
